# EdgeLLM

Run local LLMs on Android with a clean Kotlin API. The developer describes
*what* they want; the SDK owns the ops — download, integrity, RAM fit,
backend choice, thermals.

**Status: pre-alpha spike.** Current goal: prove the llama.cpp JNI path on a
real device. See [docs/design.md](docs/design.md) for the API vision.

## Modules

- `edgellm-engine-llamacpp` — JNI bridge + statically linked llama.cpp (arm64-v8a)
- `sample` — minimal app driving the bridge directly
- `edgellm-core` — (next) public API, download manager, fit check, device profiler

## Building

```
git clone --recursive <repo>
# open in Android Studio (needs NDK + CMake from SDK Manager), or:
./gradlew :sample:assembleDebug
```

The sample expects a GGUF model at
`/sdcard/Android/data/io.github.lucas.edgellm.sample/files/model.gguf`
(push one with `adb push model.gguf /sdcard/Android/data/io.github.lucas.edgellm.sample/files/`).

## Stability

Nothing is stable yet. Once published: the app-facing API aims for
compatibility; the engine SPI is explicitly unstable until 1.0.
