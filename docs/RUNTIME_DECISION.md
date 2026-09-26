# Runtime Decision Record

**Date**: 2026-09-26  
**Status**: Accepted  
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
