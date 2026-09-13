# Prompt 09: TestApp, Tests, M0 Smoke Test, & CI Gate

## Objective
Establish target testapp, comprehensive unit tests, M0 smoke verification, and automated CI gate.

## Key Components:
1. `testapp`:
   - Contains a standard target test application with settings, cab booking, forms, buttons, and switches for reliable testing.
2. Unit & Integration Tests:
   - `AniobAutoRouterTest`: Validates decision routing (offline -> local, battery < 20% -> cloud, vision -> cloud).
   - `AniobTokenOptimizerTest`: Verifies capping at 60 nodes and Set-of-Mark labeling.
   - `AniobScreenDiffTest`: Validates 4-level waterfall stages.
   - `AniobGrillMeEngineTest`: Verifies dynamic question generation for ambiguous queries.
   - `AniobWatchdogTest`: Verifies loop detection and reflection trigger.
   - `AniobIntentResolverTest`: Validates "Open Settings" intent mapping.
3. CI Gate Verification (`scripts/ci_gate.sh`):
   - Scans entire repository for forbidden `BILLING` permission or payment SDKs.
   - Verifies `minSdk >= 30` and `targetSdk >= 35`.
   - Runs `./gradlew :core-agent:test`.
4. M0 & M0-Grill Execution Verification:
   - "Open Settings" completes via Intent within 15s.
   - "Book a cab" triggers `AniobGrillMeSheet` with questions + options + free text.
   - SessionScore logged to Room database.
   - Tracker displays Correct/Failed filter with 0 extra LLM calls.
