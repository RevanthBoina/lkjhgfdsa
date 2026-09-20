# AGENTS.md

Persistent, repo-specific knowledge for agents working in this repository.

## Build & test

Gradle wrapper is not present; use an installed Gradle. This environment uses:

```bash
export JAVA_HOME=/opt/jdk17
export ANDROID_HOME=$HOME/android-sdk
export PATH=/tmp/setup/gradle-dist/gradle-9.3.1/bin:$PATH

gradle :core-agent:test :app:testDebugUnitTest --console=plain
gradle :app:assembleDebug --console=plain
```

- `:core-agent` is pure Kotlin/JVM. Guarded by CI preflight: **zero `android.*` imports**. Keep it
  platform-free; put Android-runtime dependencies behind interfaces in `app`.
- `:app` unit tests use Robolectric (`@Config(sdk = [33])`) and run on the JVM. `robolectric-nativeruntime`
  may log a harmless "Failed to destroy temp directory" on shutdown.
- `:app:assembleDebug` requires a debug keystore that is not committed. Generate it locally if missing:
  `keytool -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"`
- `google-services.json` is intentionally absent; `MissingGoogleServicesStrategy.WARN` tolerates it.

## CI preflight

`bash scripts/ci-preflight.sh` runs five compliance guards (no billing/payment SDKs, skill-assets sync,
core-agent purity, empty-catch scan, Prompt-4 pins). Run it before proposing changes; it must end with
`ALL PREFLIGHT CI GUARDS PASSED SUCCESSFULLY`.

## Architecture invariants

- **Never fabricate an action.** A local SLM step must originate from real model output
  (`AniobGenerationResult.Ready`). Missing engine/model, generation timeout, and unparseable output all
  resolve to `AniobAction.Fail` with a user-actionable reason via `AniobLocalActionResolver`. Taps are
  validated against node ids present on the captured screen before dispatch.
- Native accelerators (`AniobLlamaCppEngine`, `AniobLiteRtEngine`) share the same
  `load`/`generate`/`release` contract so routers can swap engines.
- Gestures are gated on UI quiescence (`AniobWaitForIdle`) before dispatch; the observation policy
  deduplicates repeated action signatures to catch in-flight animations.
- The Tracker screen is a pure view over Room/EventLogger state: opening it must perform zero screen
  captures and zero model calls (`TrackerZeroCaptureTest`).