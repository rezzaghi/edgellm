// EdgeLLM spike: minimal JNI bridge over llama.cpp.
// Blocking calls; token pieces are delivered to Kotlin as raw UTF-8 bytes
// because a single piece may end mid-way through a multi-byte character.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#include "ggml-backend.h"
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

Session *toSession(jlong handle) { return reinterpret_cast<Session *>(handle); }

// llama.cpp logs to stderr, which Android discards; forward into logcat.
void forwardLlamaLog(ggml_log_level level, const char *text, void * /*user*/) {
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default:                   prio = ANDROID_LOG_INFO;  break;
    }
    __android_log_write(prio, "llama.cpp", text);
}

} // namespace

// llama.cpp (and especially its Vulkan path) throws C++ exceptions; an
// exception escaping a JNI function aborts the entire app process. Every
// entry point below catches and degrades to an error return instead.

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_rezzaghi_edgellm_engine_llamacpp_LlamaBridge_nativeHasGpuBackend(
        JNIEnv * /*env*/, jobject /*thiz*/) {
    try {
        llama_log_set(forwardLlamaLog, nullptr);
        llama_backend_init();
        return ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_GPU) != nullptr;
    } catch (...) {
        return false;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_rezzaghi_edgellm_engine_llamacpp_LlamaBridge_nativeLoadModel(
        JNIEnv *env, jobject /*thiz*/, jstring jpath, jint nCtx, jint nGpuLayers) {
    const char *pathC = env->GetStringUTFChars(jpath, nullptr);
    std::string path(pathC);
    env->ReleaseStringUTFChars(jpath, pathC);

    try {
        llama_log_set(forwardLlamaLog, nullptr);
        llama_backend_init();

        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = nGpuLayers; // 0 = CPU only; ggml picks Vulkan when > 0

        llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
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

        auto *session = new Session();
        session->model = model;
        session->ctx = ctx;
        session->vocab = llama_model_get_vocab(model);
        LOGI("model loaded: n_ctx=%d threads=%d gpu_layers=%d",
             (int) nCtx, threads, (int) nGpuLayers);
        return reinterpret_cast<jlong>(session);
    } catch (const std::exception &e) {
        LOGE("load failed with native exception: %s", e.what());
        return 0;
    } catch (...) {
        LOGE("load failed with unknown native exception");
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_rezzaghi_edgellm_engine_llamacpp_LlamaBridge_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring jprompt,
        jint maxTokens, jfloat temperature, jobject callback) {
    auto *session = toSession(handle);
    if (!session) return -1;
    session->stop = false;

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "([B)V");
    if (!onToken) return -2;

    const char *promptC = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(promptC);
    env->ReleaseStringUTFChars(jprompt, promptC);

    try {
    // Fresh conversation per call: drop previous KV state.
    llama_memory_clear(llama_get_memory(session->ctx), true);

    // Two-pass tokenize: first call reports required size as a negative count.
    int promptTokens = -llama_tokenize(session->vocab, prompt.c_str(), (int) prompt.size(),
                                       nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (promptTokens <= 0) {
        LOGE("tokenize sizing failed");
        return -3;
    }
    std::vector<llama_token> tokens(promptTokens);
    if (llama_tokenize(session->vocab, prompt.c_str(), (int) prompt.size(),
                       tokens.data(), promptTokens, true, true) < 0) {
        LOGE("tokenize failed");
        return -3;
    }
    LOGI("prompt: %d tokens", promptTokens);

    llama_batch batch = llama_batch_get_one(tokens.data(), (int) tokens.size());
    if (llama_decode(session->ctx, batch) != 0) {
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
    while (generated < maxTokens && !session->stop) {
        llama_token tok = llama_sampler_sample(smpl, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, tok)) break;

        const int len = llama_token_to_piece(session->vocab, tok, piece, sizeof(piece), 0, true);
        if (len > 0) {
            jbyteArray arr = env->NewByteArray(len);
            env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(piece));
            env->CallVoidMethod(callback, onToken, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) break; // callback threw; abort generation
        }

        ++generated;

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(session->ctx, next) != 0) {
            LOGE("decode failed at token %d", generated);
            break;
        }
    }

    llama_sampler_free(smpl);
    return generated;
    } catch (const std::exception &e) {
        LOGE("generation failed with native exception: %s", e.what());
        return -5;
    } catch (...) {
        LOGE("generation failed with unknown native exception");
        return -5;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_rezzaghi_edgellm_engine_llamacpp_LlamaBridge_nativeApplyChatTemplate(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jobjectArray roles,
        jobjectArray contents, jboolean addAssistant) {
    auto *session = toSession(handle);
    if (!session) return nullptr;

    try {
    const char *tmpl = llama_model_chat_template(session->model, /*name=*/nullptr);
    if (!tmpl) return nullptr;

    const jsize msgCount = env->GetArrayLength(roles);
    std::vector<std::string> storage(2 * msgCount); // keeps the bytes alive for the call
    std::vector<llama_chat_message> msgs(msgCount);
    for (jsize i = 0; i < msgCount; ++i) {
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
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgCount, addAssistant,
                                            buf.data(), (int32_t) buf.size());
    if (len > (int32_t) buf.size()) { // buffer was too small; retry sized
        buf.resize(len);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgCount, addAssistant,
                                        buf.data(), (int32_t) buf.size());
    }
    if (len < 0) {
        LOGE("chat template application failed");
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(len);
    env->SetByteArrayRegion(out, 0, len, (jbyte *) buf.data());
    return out;
    } catch (const std::exception &e) {
        LOGE("chat template failed with native exception: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("chat template failed with unknown native exception");
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_rezzaghi_edgellm_engine_llamacpp_LlamaBridge_nativeTokenCount(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring jtext) {
    auto *session = toSession(handle);
    if (!session) return -1;
    const char *text = env->GetStringUTFChars(jtext, nullptr);
    int tokenCount = -1;
    try {
        tokenCount = -llama_tokenize(session->vocab, text, (int) strlen(text),
                                     nullptr, 0, true, true);
    } catch (...) {
        LOGE("tokenCount failed with native exception");
    }
    env->ReleaseStringUTFChars(jtext, text);
    return tokenCount;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_rezzaghi_edgellm_engine_llamacpp_LlamaBridge_nativeStop(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (auto *session = toSession(handle)) session->stop = true;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_rezzaghi_edgellm_engine_llamacpp_LlamaBridge_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto *session = toSession(handle);
    if (!session) return;
    try {
        if (session->ctx) llama_free(session->ctx);
        if (session->model) llama_model_free(session->model);
    } catch (...) {
        LOGE("free failed with native exception; leaking session");
    }
    delete session;
}
