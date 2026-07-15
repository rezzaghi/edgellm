# EdgeLLM

[![CI](https://github.com/rezzaghi/edgellm/actions/workflows/ci.yml/badge.svg)](https://github.com/rezzaghi/edgellm/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Run local LLMs on Android with a clean Kotlin API. You describe *what* you
want; the SDK owns the ops:

- **Model delivery** — resumable downloads with SHA-256 verification and a
  disk-space guard, from a curated catalog or any GGUF URL.
- **RAM fit check** — know before loading whether a model fits this device.
- **Streaming generation** — Kotlin `Flow` of tokens; cancel the collection
  to stop generation.
- **Chat out of the box** — conversations formatted with the model's own
  chat template, no prompt-format guesswork.
- **Backend routing** — `Backend.AUTO` picks CPU or GPU (OpenCL) per device,
  verifies what actually loaded, and falls back to CPU if the GPU driver
  fails.
- **llama.cpp inside** — statically linked, exceptions contained, logs
  bridged to logcat.

## Quickstart

```kotlin
val llm = EdgeLlm.create(context)          // one per app
val model = Catalog.qwen25_05b             // or your own ModelSpec

if (llm.checkFit(model) is Fit.WontFit) error("Pick a smaller model")

llm.download(model).collect { /* it.fraction → progress UI */ }

val session = llm.load(model)
session.chat(listOf(ChatMessage.user("Why is the sky blue?")))
    .collect { event ->
        when (event) {
            is GenerationEvent.Token -> print(event.text)
            is GenerationEvent.Done -> println("\n${event.tokensPerSec} tok/s")
        }
    }
session.close()
```

The [sample app](sample/) is a complete Compose chat app showing the
recommended lifecycle (ViewModel-owned session, rotation-safe streaming).

## Installation

Artifacts are on GitHub Packages (Maven Central planned):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/rezzaghi/edgellm")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.token").orNull
            }
        }
    }
}

// app/build.gradle.kts
implementation("io.github.rezzaghi:edgellm-core:0.1.0")
runtimeOnly("io.github.rezzaghi:edgellm-engine-llamacpp:0.1.0")
```

GitHub Packages requires authentication even for public packages — see
[Getting started](docs/getting-started.md) for the one-time token setup.

**Requirements:** minSdk 28, arm64-v8a devices, `INTERNET` permission for
downloads.

## Model catalog

Curated, tested models with verified checksums:

| Model | Quant | Size |
|---|---|---|
| Qwen 2.5 0.5B Instruct | Q4_K_M | 491 MB |
| Llama 3.2 1B Instruct | Q4_K_M | 808 MB |
| Qwen 2.5 1.5B Instruct | Q4_K_M | 1.1 GB |

Not limited to the catalog — build a `ModelSpec` for any GGUF model; see
[Usage](docs/usage.md#custom-models).

## Documentation

- [Getting started](docs/getting-started.md) — install, first chat
- [Usage guide](docs/usage.md) — models, downloads, sessions, lifecycle
- [Backends](docs/backends.md) — CPU/GPU routing, what's verified where

## Building from source

```bash
git clone --recursive https://github.com/rezzaghi/edgellm.git
# open in Android Studio (needs NDK + CMake from SDK Manager), or:
./gradlew :sample:assembleDebug
```

## License

Apache License 2.0 — see [LICENSE](LICENSE). Third-party submodules under
`third_party/` keep their own licenses; see [NOTICE](NOTICE).
