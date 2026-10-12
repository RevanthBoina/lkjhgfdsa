# Runtime Decision Record

**Date**: 2026-09-26  
**Status**: Accepted  
**Implemented**: 2026-10-11

Option A is now in the tree:

- `:app` depends on `com.google.ai.edge.litertlm:litertlm-android:0.16.0` (Google Maven), the version proven in the reference implementation rather than the newest published (0.18.0).
- `com.aniob.app.model.AniobLiteRtLmEngine` implements `core-agent`'s `AniobNativeLlmEngine` against the real Kotlin API (`EngineConfig`/`Engine`/`Conversation`/`SamplerConfig`). No hand-written JNI.
- `AniobViewModel` injects it through `AniobLiteRtProvider`'s existing `engineFactory` seam, with a model path from `AniobModelDownloader.resolveInstalledModelPath()` instead of the stale adb default `/data/local/tmp/qwen2.5-1.5b-gpu.bin`.
- The catalog now ships `.litertlm` files with real SHA-256 values (previously all empty, so the integrity check silently never ran).
- `core-agent`'s `AniobLiteRtEngine` is deliberately retained: it is the JVM-testable unbacked seam the 311-test suite exercises without a device.

**Verified**: `lib/arm64-v8a/liblitertlm_jni.so` (20.5 MB) is packaged in `app-debug.apk`. It was absent before this change, which is precisely why `System.loadLibrary("litertlm")` threw `UnsatisfiedLinkError` on every device while all CI guards stayed green.

**Not verified**: actual on-device inference. No emulator or device was available (no KVM in the build sandbox), so generation quality, GPU-backend behaviour and memory headroom remain untested. CPU backend is the default for that reason.

**Decision**: Option A (Prebuilt Maven LiteRT Runtime) with Option C (Cloud Fallback & Auto Routing)

## Context & Problem Statement
On-device models require a native inference runtime on Android. Currently, `AniobLlamaCppEngine` expects `System.loadLibrary("llama")` which throws `UnsatisfiedLinkError` if no native shared library is bundled. We must decide between:
1. Option A: Prebuilt Maven runtime (LiteRT via `com.google.ai.edge.litert`)
2. Option B: Building `llama.cpp` from source with CMake/NDK
3. Option C: Cloud-only inference

## Decision Drivers
- Proven stability in the reference implementation (ReturnGift).
- Zero NDK/CMake build toolchain fragility in CI.
- Models <= 4B (Gemma 3 1B/4B, Phi-4 Mini) are well-supported by Google AI Edge LiteRT.
- Graceful degradation: When on-device models or native libraries are missing, `AniobLocalActionResolver` and `AniobExecutionRouter` cleanly report unavailability or route to Cloud/Auto without fabricating actions.

## Considered Options
- **Option A — Prebuilt Maven LiteRT**: Fast integration, precompiled native libraries, standard Gradle distribution.
- **Option B — Build llama.cpp from source**: Adds 50MB+ native binaries per ABI, requires Android NDK and custom CMake build scripts, prone to CI cache misses.
- **Option C — Cloud-only**: Completely drops on-device execution. Rejected as on-device autonomy is a core privacy pillar of Aniob.

## Outcome
Adopt **Option A** as primary on-device runtime architecture. Keep `AniobNativeLlmEngine` contract so `AniobLiteRtEngine` and `AniobLlamaCppEngine` share the same interface. Support Cloud fallback (Option C) through `AniobHybridEngineRouter`.
