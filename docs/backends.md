# Backends

Which hardware runs inference. The default is `Backend.AUTO`; most apps
never need to touch this page beyond that.

## AUTO routing

```kotlin
val session = llm.load(model)          // Backend.AUTO
Log.i(TAG, "serving on ${session.backend}")
```

`AUTO` resolves to a concrete backend per device:

- SoCs where GPU inference is known-good route to `OPENCL`.
- Unknown or unverified SoCs route to `CPU` — conservative on purpose: a
  slow answer beats a crashed app.
- If a GPU load fails hard (driver crash, out of GPU memory), the SDK
  retries on CPU automatically. The app never sees the failure.

`session.backend` is the **verified** result after load, never what was
merely requested — llama.cpp can silently fall back to CPU, so the SDK
checks which device the model actually landed on.

## Forcing a backend

```kotlin
llm.load(model, EngineConfig(backend = Backend.CPU))     // always works
llm.load(model, EngineConfig(backend = Backend.OPENCL))  // falls back to CPU on hard failure
```

## Verifying at the native level

`adb logcat -s llama.cpp` shows llama.cpp's own backend report (the SDK
bridges its logs to logcat). For OpenCL you'll see the device line, e.g.
`... assigned to device GPUOpenCL` and the Adreno device name.

## Current support matrix

| Backend | Status |
|---|---|
| CPU | Always available; the fallback for everything. |
| OpenCL (Adreno) | Working; verified on Snapdragon 8 Gen 2 (Galaxy S23). |
| OpenCL (Mali) | Untested — the vendor-driver loader currently only probes `libOpenCL.so`. |
| Vulkan | Builds, but disabled: Adreno drivers rejected llama.cpp's compute shaders at runtime. |
| NPU | Deferred; the `Backend` enum reserves the slot. |

Two honest caveats:

- **GPU is not automatically faster.** On Snapdragon 8 Gen 2 with a 0.5B
  Q4_K_M model, OpenCL and CPU are at parity (~15–40 tok/s). llama.cpp's
  Adreno-optimized kernels target Adreno 750+; small models are also
  memory-bound, which favors CPU. GPU wins grow with model size and newer
  SoCs.
- **The routing table is small.** It grows as devices are verified; reports
  from real hardware (the logcat lines above plus your SoC model) are the
  most useful contribution.

## How the OpenCL path works (background)

Android vendors ship `libOpenCL.so` as a system library, but their drivers
don't support the ICD-loader mechanism desktop OpenCL uses. The engine
therefore loads the vendor driver directly at runtime (`dlsym`-forwarding
stub) and declares `<uses-native-library android:name="libOpenCL.so"
android:required="false"/>` — on devices without the library, everything
transparently stays on CPU.
