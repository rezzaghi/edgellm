# Usage guide

The API in one line: `EdgeLlm` picks and delivers models; `EdgeLlmSession`
generates text. Everything streams through Kotlin `Flow`.

## The EdgeLlm entry point

Create one instance per app and reuse it — it holds no heavy state, but
there's no reason to have two:

```kotlin
class MyApp : Application() {
    val edgeLlm by lazy { EdgeLlm.create(this) }
}
```

## Models

### Catalog

`Catalog` is a hand-curated list of models that have been tested with this
SDK, with verified URLs, sizes, and checksums:

```kotlin
val model = Catalog.qwen25_05b        // pick one directly
val all = llm.catalog()               // or list them (e.g. for a picker UI)
```

### Custom models

Any GGUF model with a direct download URL works:

```kotlin
val model = Catalog.custom(
    id = "phi-3-mini-q4",            // stable id; also the on-disk file name
    url = "https://huggingface.co/.../resolve/main/phi-3-mini-4k-instruct-q4.gguf",
    sha256 = "8a83c7fb9049a9b2e92266fa7ad04933bb53aa1e85136b7b30f1b8000ff2edef",
    sizeBytes = 2_393_232_608,
)
```

For Hugging Face files, `sha256` and `sizeBytes` are in the LFS pointer at
`https://huggingface.co/<repo>/raw/main/<file>` (note `raw`, not `resolve`).

### Will it fit?

`checkFit` compares the model's memory needs against the device's RAM
*right now*:

```kotlin
when (val fit = llm.checkFit(model)) {
    is Fit.Ok -> Unit                 // comfortable headroom
    is Fit.TightRam -> warnUser(fit)  // loads, but the OS may kill background apps
    is Fit.WontFit -> pickSmaller(fit) // loading would likely be OOM-killed
}
```

`TightRam` and `WontFit` carry `availableMb`/`requiredMb` for user-facing
messages. Available RAM changes constantly, so check near load time, not at
app start.

### Downloading

```kotlin
llm.download(model).collect { progress ->
    updateUi(progress.fraction)       // 0f..1f
}
```

- Safe to call unconditionally: completes instantly (one progress emission)
  if the model is already on disk.
- Interrupted downloads resume from where they stopped on the next call.
- The file is SHA-256 verified before being moved into place; a checksum
  mismatch deletes the partial file and throws.
- Fails upfront (rather than at 90%) if disk space won't cover the download.
- Errors surface as exceptions from `collect` — catch them there.

Files live in the app's external files dir (`modelFile(model)` tells you
where); they are removed when the app is uninstalled and don't need storage
permissions.

## Sessions

### Loading

```kotlin
val session = llm.load(model)                       // defaults: 2048 ctx, Backend.AUTO
val session = llm.load(model, EngineConfig(contextLength = 4096))
```

Loading a model takes seconds and hundreds of MB of native memory — hold on
to the session instead of re-loading per request. `Backend.AUTO` resolves to
CPU or GPU per device, and a GPU load that fails hard is retried on CPU
automatically; see [Backends](backends.md). `session.backend` tells you what
you actually got.

### Chat

`chat` formats the conversation with the model's own chat template (from the
GGUF metadata) and streams the reply:

```kotlin
val history = listOf(
    ChatMessage.system("You are a concise assistant."),
    ChatMessage.user("What's a monad?"),
)
session.chat(history).collect { event ->
    when (event) {
        is GenerationEvent.Token -> append(event.text)
        is GenerationEvent.Done -> logStats(event.totalTokens, event.tokensPerSec)
    }
}
```

The SDK is stateless between calls: pass the full history (including
previous assistant replies) every time. `chat` throws if the model ships no
chat template — rare for instruct models; fall back to `generate` with a
manually formatted prompt.

### Raw generation

`generate` sends a prompt verbatim — no templating:

```kotlin
session.generate("Once upon a time", maxTokens = 128, temperature = 0.9f)
    .collect { /* ... */ }
```

`temperature <= 0` selects greedy (deterministic) sampling.

### Stopping, limits, cleanup

- **Stop**: cancel the collecting coroutine — generation stops promptly on
  the native side. This is the intended stop mechanism.
- **One at a time**: a session runs one generation at a time; starting a
  second while one is in flight is a caller bug and throws.
- **Token budget**: `session.tokenCount(text)` counts tokens under the
  loaded model's tokenizer, for keeping history within the context length.
- **Close**: `session.close()` frees the native memory. Fire-and-forget and
  idempotent — safe to call from `ViewModel.onCleared`, even mid-generation.

## Lifecycle in a real app

The pattern the [sample app](../sample/) demonstrates:

- `EdgeLlm` lives in the `Application` (created lazily).
- The session lives in a `ViewModel`, so it survives rotation; close it in
  `onCleared()`.
- Streaming state (messages, progress) is exposed as `StateFlow`, so the UI
  re-attaches cleanly after configuration changes.

```kotlin
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val llm = (app as MyApp).edgeLlm
    private var session: EdgeLlmSession? = null

    // ... checkFit → download → load → chat, as in Getting started ...

    override fun onCleared() {
        session?.close()
    }
}
```

Deliberately not in the sample: dependency injection frameworks and
WorkManager-based downloads. The SDK doesn't require them; add them if your
app already uses them.
