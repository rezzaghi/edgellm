# EdgeLLM SDK — Concrete Usage Example (v1 design sketch)

The design principle: **the developer describes *what* they want; the SDK owns the ops** (download, integrity, RAM fit, backend choice, thermals). Engine is an implementation detail behind an interface.

---

## 1. Setup — two Gradle lines

```kotlin
// build.gradle.kts
plugins {
    id("io.github.rezzaghi.edgellm") version "0.1.0"   // Gradle plugin (packaging)
}

dependencies {
    implementation("io.github.rezzaghi:edgellm-core:0.1.0")
    runtimeOnly("io.github.rezzaghi:edgellm-engine-llamacpp:0.1.0") // v1: one engine
    // later: edgellm-engine-litert, edgellm-engine-mlc — same API
}
```

## 2. Declare a model (build-time, via the plugin)

```kotlin
// build.gradle.kts
edgeLlm {
    models {
        create("phi4Mini") {
            source = huggingFace(
                repo = "microsoft/Phi-4-mini-instruct-gguf",
                file = "phi-4-mini-instruct-Q4_K_M.gguf"
            )
            sha256 = "ab34…"
            delivery = Delivery.OnDemand   // or Delivery.AssetPack (PAD install-time)
        }
    }
}
```

The plugin generates a type-safe accessor: `EdgeLlmModels.phi4Mini` — with size, checksum, and RAM requirement baked in. No magic strings at runtime.

## 3. Runtime — the happy path

```kotlin
val llm = EdgeLlm.create(context)

// 1) Will it even fit? (prevents the silent OOM kill)
when (val fit = llm.checkFit(EdgeLlmModels.phi4Mini)) {
    is Fit.Ok -> Unit
    is Fit.TightRam -> warnUser(fit.availableMb)      // loads, but degraded
    is Fit.WontFit  -> return fallbackToCloud()        // developer's call
}

// 2) Download with resume + checksum verification
llm.download(EdgeLlmModels.phi4Mini).collect { p ->
    progressBar.value = p.fraction                     // Flow<DownloadProgress>
}

// 3) Load — backend selected per device
val session = llm.load(EdgeLlmModels.phi4Mini) {
    backend = Backend.Auto        // NPU if reachable, else Vulkan, else CPU
    contextLength = 2048
    thermal = ThermalPolicy.Adaptive   // slow down instead of cooking the SoC
}

// 4) Generate — plain Kotlin Flow
session.generate("Summarize this article: $text")
    .collect { event ->
        when (event) {
            is Token      -> append(event.text)
            is Throttled  -> showChip("Slowing down — device is hot")
            is Done       -> logStats(event.tokensPerSec, event.ttfbMs)
        }
    }
```

That's the whole integration. ~25 lines for download-to-streaming, versus the current status quo (NDK build, JNI bridge, hand-rolled downloader, no fit check).

## 4. What `Backend.Auto` actually does (the hidden value)

```
DeviceProfile (built at first launch)
├── SoC: Snapdragon 8 Elite → NPU path available? → engine supports it? → NPU
├── SoC: Dimensity 9400    → Vulkan (30–40% over CPU)
├── SoC: Tensor G5         → CPU (Google reserves NPU for 1st-party)
└── Unknown / low RAM      → CPU, conservative thread count
```

This routing table is exactly the tribal knowledge scattered across blog posts today. Encoding it in the SDK **is** the product.

## 5. The engine adapter interface (why engines are swappable)

```kotlin
interface InferenceEngine {
    val id: String                                  // "llamacpp", "litert", "mlc"
    val supportedFormats: Set<ModelFormat>          // GGUF, LITERT, MLC
    fun capabilities(device: DeviceProfile): EngineCapabilities  // NPU? Vulkan?
    suspend fun load(file: ModelFile, cfg: EngineConfig): EngineSession
}

interface EngineSession {
    fun generate(request: GenerationRequest): Flow<GenerationEvent>
    suspend fun close()
}
```

`edgellm-core` never touches JNI. Each engine module owns its native build. Adding an engine = implementing two interfaces.

## 6. Module layout

```
edgellm/
├── edgellm-core/            # API, download manager, fit check, device profiler, thermal
├── edgellm-engine-llamacpp/ # JNI bridge + prebuilt .so (arm64-v8a)
├── edgellm-engine-litert/   # (v2)
├── edgellm-gradle-plugin/   # model DSL, codegen, PAD wiring
└── sample/                  # chat app demonstrating everything above
```

---

### Open design questions (next discussion)

1. **Fallback contract** — should the SDK offer a first-class `CloudFallback` interface (bring-your-own API), or stay strictly on-device and leave hybrid to the app?
2. **Model registry** — curated known-good model list with tested per-tier configs (Firebase-style), or fully bring-your-own?
3. **Process model** — inference in-process vs. a bound service in an isolated process (survives better under memory pressure, cleaner kill semantics).
4. **Prompt templating** — chat template handling per model family (this is a constant footgun in llama.cpp land). Ship it, or punt to the developer?
