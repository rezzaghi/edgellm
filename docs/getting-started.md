# Getting started

## Requirements

- Android 9+ (minSdk 28)
- arm64-v8a device (that's every mainstream Android phone since ~2017;
  emulators on Apple Silicon work too)
- `INTERNET` permission if you use the built-in downloader

## Install

Artifacts are on GitHub Packages, which requires authentication even for
public packages (Maven Central is planned). One-time setup:

1. Create a GitHub personal access token (classic) with the
   **`read:packages`** scope: <https://github.com/settings/tokens>
2. Put it in `~/.gradle/gradle.properties` (never in the repo):

   ```properties
   gpr.user=YOUR_GITHUB_USERNAME
   gpr.token=ghp_xxxxxxxxxxxxxxxxxxxx
   ```

3. Add the repository in `settings.gradle.kts`:

   ```kotlin
   dependencyResolutionManagement {
       repositories {
           google()
           mavenCentral()
           maven {
               url = uri("https://maven.pkg.github.com/rezzaghi/edgellm")
               credentials {
                   username = providers.gradleProperty("gpr.user").orNull
                   password = providers.gradleProperty("gpr.token").orNull
               }
           }
       }
   }
   ```

4. Add the dependencies:

   ```kotlin
   dependencies {
       implementation("io.github.rezzaghi:edgellm-core:0.1.0")
       runtimeOnly("io.github.rezzaghi:edgellm-engine-llamacpp:0.1.0")
   }
   ```

   `edgellm-core` is the API you code against. The engine module carries the
   native llama.cpp library (~15 MB, arm64 only) and is discovered at
   runtime — `runtimeOnly` keeps it out of your compile classpath.

5. Add the permission to `AndroidManifest.xml`:

   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   ```

## First chat

```kotlin
// One instance for the whole app (e.g. in your Application class).
val llm = EdgeLlm.create(context)

suspend fun demo() {
    val model = Catalog.qwen25_05b // smallest catalog model, 491 MB

    // 1. Will it run on this device?
    when (val fit = llm.checkFit(model)) {
        is Fit.Ok -> Unit
        is Fit.TightRam -> Log.w(TAG, "Will run, but memory is tight")
        is Fit.WontFit -> {
            Log.e(TAG, "Needs ~${fit.requiredMb} MB, only ${fit.availableMb} MB free")
            return
        }
    }

    // 2. Download (resumable, checksum-verified; instant if already on disk).
    llm.download(model).collect { progress ->
        Log.i(TAG, "download ${(progress.fraction * 100).toInt()}%")
    }

    // 3. Load and chat.
    val session = llm.load(model)
    try {
        session.chat(listOf(ChatMessage.user("Why is the sky blue?")))
            .collect { event ->
                when (event) {
                    is GenerationEvent.Token -> print(event.text)
                    is GenerationEvent.Done ->
                        Log.i(TAG, "${event.totalTokens} tokens @ ${event.tokensPerSec} tok/s")
                }
            }
    } finally {
        session.close() // frees ~500 MB+ of native memory
    }
}
```

That's the whole API for the common case. In a real app you'll want the
session to survive configuration changes — see the
[lifecycle section of the usage guide](usage.md#lifecycle-in-a-real-app) and
the [sample app](../sample/), a complete Compose chat app.

## Did it work?

- `session.backend` tells you what's actually serving the session (`CPU` or
  `OPENCL`), verified after load — not what was requested.
- Native-level detail is in logcat: `adb logcat -s llama.cpp edgellm`.

## Next

- [Usage guide](usage.md) — models, downloads, sessions, lifecycle
- [Backends](backends.md) — CPU/GPU routing and what's verified where
