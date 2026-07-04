// EdgeLLM spike: minimal JNI bridge over llama.cpp.
// Blocking calls; token pieces are delivered to Kotlin as raw UTF-8 bytes
// because a single piece may end mid-way through a multi-byte character.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"

#define TAG "edgellm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> stop{false};
};

Session *toSession(jlong h) { return reinterpret_cast<Session *>(h); }

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_lucas_edgellm_engine_llamacpp_LlamaBridge_nativeLoadModel(
        JNIEnv *env, jobject /*thiz*/, jstring jpath, jint nCtx) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only for the spike

    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) {
        LOGE("failed to load model");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = nCtx;
    cparams.n_batch = nCtx; // whole prompt in one decode call
    const int threads = (int) std::max(4u, std::thread::hardware_concurrency() / 2);
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto *s = new Session();
    s->model = model;
    s->ctx = ctx;
    s->vocab = llama_model_get_vocab(model);
    LOGI("model loaded: n_ctx=%d threads=%d", (int) nCtx, threads);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_lucas_edgellm_engine_llamacpp_LlamaBridge_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring jprompt,
        jint maxTokens, jfloat temperature, jobject callback) {
    auto *s = toSession(handle);
    if (!s) return -1;
    s->stop = false;

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "([B)V");
    if (!onToken) return -2;

    const char *promptC = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(promptC);
    env->ReleaseStringUTFChars(jprompt, promptC);

    // Fresh conversation per call: drop previous KV state.
    llama_memory_clear(llama_get_memory(s->ctx), true);

    // Two-pass tokenize: first call reports required size as a negative count.
    int n = -llama_tokenize(s->vocab, prompt.c_str(), (int) prompt.size(),
                            nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (n <= 0) {
        LOGE("tokenize sizing failed");
        return -3;
    }
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(s->vocab, prompt.c_str(), (int) prompt.size(),
                       tokens.data(), n, true, true) < 0) {
        LOGE("tokenize failed");
        return -3;
    }
    LOGI("prompt: %d tokens", n);

    llama_batch batch = llama_batch_get_one(tokens.data(), (int) tokens.size());
    if (llama_decode(s->ctx, batch) != 0) {
        LOGE("prompt decode failed");
        return -4;
    }

    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    }

    int generated = 0;
    char piece[256];
    while (generated < maxTokens && !s->stop) {
        llama_token tok = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, tok)) break;

        const int len = llama_token_to_piece(s->vocab, tok, piece, sizeof(piece), 0, true);
        if (len > 0) {
            jbyteArray arr = env->NewByteArray(len);
            env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(piece));
            env->CallVoidMethod(callback, onToken, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) break; // callback threw; abort generation
        }

        ++generated;

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(s->ctx, next) != 0) {
            LOGE("decode failed at token %d", generated);
            break;
        }
    }

    llama_sampler_free(smpl);
    return generated;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_lucas_edgellm_engine_llamacpp_LlamaBridge_nativeApplyChatTemplate(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jobjectArray roles,
        jobjectArray contents, jboolean addAssistant) {
    auto *s = toSession(handle);
    if (!s) return nullptr;

    const char *tmpl = llama_model_chat_template(s->model, /*name=*/nullptr);
    if (!tmpl) return nullptr;

    const jsize n = env->GetArrayLength(roles);
    std::vector<std::string> storage(2 * n); // keeps the bytes alive for the call
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; ++i) {
        auto jrole = (jbyteArray) env->GetObjectArrayElement(roles, i);
        auto jcontent = (jbyteArray) env->GetObjectArrayElement(contents, i);

        jsize rlen = env->GetArrayLength(jrole);
        jsize clen = env->GetArrayLength(jcontent);
        storage[2 * i].resize(rlen);
        storage[2 * i + 1].resize(clen);
        env->GetByteArrayRegion(jrole, 0, rlen, (jbyte *) storage[2 * i].data());
        env->GetByteArrayRegion(jcontent, 0, clen, (jbyte *) storage[2 * i + 1].data());
        env->DeleteLocalRef(jrole);
        env->DeleteLocalRef(jcontent);

        msgs[i] = {storage[2 * i].c_str(), storage[2 * i + 1].c_str()};
    }

    std::vector<char> buf(4096);
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), n, addAssistant,
                                            buf.data(), (int32_t) buf.size());
    if (len > (int32_t) buf.size()) { // buffer was too small; retry sized
        buf.resize(len);
        len = llama_chat_apply_template(tmpl, msgs.data(), n, addAssistant,
                                        buf.data(), (int32_t) buf.size());
    }
    if (len < 0) {
        LOGE("chat template application failed");
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(len);
    env->SetByteArrayRegion(out, 0, len, (jbyte *) buf.data());
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_lucas_edgellm_engine_llamacpp_LlamaBridge_nativeTokenCount(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring jtext) {
    auto *s = toSession(handle);
    if (!s) return -1;
    const char *text = env->GetStringUTFChars(jtext, nullptr);
    const int n = -llama_tokenize(s->vocab, text, (int) strlen(text),
                                  nullptr, 0, true, true);
    env->ReleaseStringUTFChars(jtext, text);
    return n;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_lucas_edgellm_engine_llamacpp_LlamaBridge_nativeStop(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (auto *s = toSession(handle)) s->stop = true;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_lucas_edgellm_engine_llamacpp_LlamaBridge_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *s = toSession(handle);
    if (!s) return;
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}
