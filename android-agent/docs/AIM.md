# AIM: Aniob Intelligence & Architecture Master Specification (Source of Truth)

> **Document Status**: Canonical Source of Truth.  
> If any other file, prompt, or code conflicts with this document, **AIM.md wins**.

---

## 1. System Vision & Core Tenets

Aniob is an autonomous, on-device + hybrid cloud Android Natural Language UI Automation Agent.
It empowers users to control and automate any Android application through natural language commands with human-in-the-loop disambiguation ("Grill-Me"), instant deterministic fastpaths, and smart routing between on-device SLMs and Omniroute cloud models.

### Non-Negotiable Tenets
1. **Zero Billing & Zero Ads**: Absolute ban on Google Play Billing (`com.android.vending.BILLING`), payment SDKs, and ad SDKs.
2. **Deterministic-First Execution**:
   - Level 0: Intent Shortcuts (`ACTION_CALL`, `ACTION_VIEW`, `ACTION_SENDTO`) -> 0ms LLM.
   - Level 1: FastPath exact task replay with verified fingerprints -> 0ms LLM.
   - Level 2: Local SLM (Qwen2.5-1.5B via llama.cpp/Ollama) for structured text navigation.
   - Level 3: Omniroute Cloud (`api.omniroute.ai/v1`) for vision, complex canvas, or unfamiliar apps.
3. **Pin-to-Pin Betterment Hierarchy**: Every subsystem must exceed industry baselines (ARTEMIS, MobileAgent, AppAgent, PrivateAgent, DroidRun).
4. **Safety & Privacy**: Unconfirmed sensitive actions (sending messages, payments, deleting files) are intercepted by `AniobSafetyGate`.
5. **No Hallucinated Actions**: Max 60 actionable nodes extracted via `AniobTokenOptimizer`. One step at a time JSON schema.

---

## 2. Global Architecture & Project Layout

```
android-agent/
├── docs/
│   └── AIM.md                           <- This canonical specification
├── configs/
│   ├── providers.yaml                  <- Provider configs (Omniroute, Ollama, Local)
│   ├── agent.yaml                      <- Agent runtime settings, thresholds & timeouts
│   └── prompts/
│       ├── system_prompt.txt           <- Actor & Planner system prompts
│       ├── grill_me_prompt.txt         <- Dynamic Question/Option generator
│       └── reflector_prompt.txt        <- Failure reflection prompt
├── core-agent/                          <- Pure Kotlin JVM (ZERO android.* dependencies)
│   └── src/main/kotlin/com/aniob/core/
│       ├── domain/                     <- Task, Plan, Action, Node, ScreenState, MetricSnapshot
│       ├── router/                     <- AniobAutoRouter (SLM vs Omniroute logic)
│       ├── grillme/                    <- AniobGrillMeEngine (dynamic clarification)
│       ├── diff/                       <- AniobScreenDiff (4-level waterfall logic)
│       ├── optimizer/                  <- AniobTokenOptimizer (max 60 nodes)
│       ├── ladder/                     <- AniobExecutionRouter (Intent -> FastPath -> Local -> Cloud)
│       ├── verifier/                   <- Deterministic Verifier (<10ms) & AniobWatchdog
│       └── safety/                     <- AniobSafetyGate & AniobFingerprint
├── app/                                 <- Android Application (minSdk 30, targetSdk 35)
│   └── src/main/java/com/aniob/app/
│       ├── service/                    <- AniobAccessibilityService (ARTEMIS / PrivateAgent flags)
│       ├── overlay/                    <- Floating Pill & Status indicator
│       ├── ui/                         <- Jetpack Compose (Chat, Tracker, GrillMeSheet, Stats, Settings)
│       ├── db/                         <- Room Database (SessionScore, AppKnowledgeBase, Tips)
│       ├── provider/                   <- AniobOmniRouteProvider & OnDeviceProvider
│       └── metrics/                    <- MetricsCollector & EventLogger
├── testapp/                             <- Target mock app for M0 & CI verification
├── bridge/                              <- Local WebSocket/HTTP bridge for adb & web inspector
├── benchmark/                           <- AndroidWorld/M3A style latency & accuracy testbench
├── prompts/                             <- Prompts 01 through 09 documentation
└── scripts/                             <- CI gate, no-billing check, gradle validation
```

---

## 3. Subsystem Specifications & Pin-to-Pin Betterments

### 3.1 Screen Capture & Perception (AniobScreenDiff)
- **Worst**: `adb shell screencap` (DroidBot) ~500ms latency, high battery drain.
- **Better**: `MediaProjection` API - requires user consent dialog on every session.
- **Best**: `AccessibilityService.takeScreenshot()` (Android 11 / API 30+, ARTEMIS) ~30-80ms.
- **Aniob Betterment (4-Level Waterfall)**:
  1. *Level 1 (Event-driven skip)*: If no accessibility window/content change event fired since last action, skip screenshot entirely (0ms).
  2. *Level 2 (Tree hash cache)*: Compute perceptual hash of actionable accessibility node tree. If identical, return cached screen state.
  3. *Level 3 (Region crop)*: If only a sub-tree changed (e.g. keyboard appeared or list scrolled), crop to changed bounding box.
  4. *Level 4 (Downscaled JPEG q80)*: Scale full bitmap to max dimension ≤1120px at 80% JPEG quality (MobileAgent trick), reducing payload by 85% with zero OCR degradation.

### 3.2 Dynamic Ambiguity Resolution ("Grill-Me")
- **Baseline**: Hardcoded clarification templates or blindly picking an arbitrary app.
- **Aniob Betterment**: `AniobGrillMeEngine`:
  1. User natural language prompt analyzed for missing slots (e.g. "Book a cab" -> missing: destination, ride type, budget).
  2. Dynamic LLM call generates structured questions with discrete radio/chip options PLUS free-text fallback.
  3. Presented as an accessible Jetpack Compose bottom sheet (`AniobGrillMeSheet`).
  4. User answers merged into clarified execution intent before task execution begins.

### 3.3 Token & Tree Optimization (AniobTokenOptimizer)
- **Problem**: Raw Android accessibility trees can contain 1,500+ nodes, blowing LLM context windows and increasing latency.
- **Aniob Rule**:
  - Filter out invisible, zero-area, or non-actionable nodes without text/description.
  - Collapse single-child layout wrappers.
  - Retain strictly up to **60 top actionable nodes**, tagged with numeric Set-of-Mark (SoM) indices `[1..60]`.
  - Structured compact string representation: `[ID] ClassName "Text/Desc" (x, y, w, h) {Clickable, Editable}`.

### 3.4 Execution Router Ladder & FastPath (AniobExecutionRouter)
Execution cascades through 4 deterministic rungs:
1. **Rung 0: Android Intent Shortcuts (`AniobIntentResolver`)**
   - E.g., "Call mom" -> `Intent(ACTION_CALL, Uri.parse("tel:..."))`
   - "Open Settings" -> `Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER).setPackage("com.android.settings")`
   - Latency: <15ms, 0 tokens.
2. **Rung 1: FastPath Replay (`AniobReplayEngine`)**
   - If (packageName, taskTemplateHash) matches a verified past successful trajectory in `AppKnowledgeBase`, replay macro steps directly.
   - Guarded by node fingerprint verification before each tap.
3. **Rung 2: Local SLM (`OnDeviceProvider`)**
   - Qwen2.5-1.5B via local llama.cpp / Ollama bridge.
   - Used for simple text navigation, scrolling, and list selection.
4. **Rung 3: Omniroute Cloud (`OmniRouteProvider`)**
   - Base URL: `https://api.omniroute.ai/v1/chat/completions`
   - Model: `gpt-4o` with multimodal vision.
   - Invoked when visual reasoning is required (canvas, icons without content-description, unlabelled components).

### 3.5 AutoRouter Decision Matrix (AniobAutoRouter)
`AniobAutoRouter.route(task, screenState, deviceState)` evaluates:
- **Vision Need**: Contains custom views/canvas or unlabelled nodes? -> Cloud.
- **Battery Health (`AniobBatteryHealth`)**: Battery level < 20% and not charging? -> Cloud (offload compute).
- **Network State**: Offline? -> Local SLM only.
- **Complexity**: Multi-app workflow or novel layout? -> Cloud. Simple single-app text navigation? -> Local.
- Every decision logs reason string to `MetricSnapshot.providerDecisionReason`.

### 3.6 Deterministic Verifier & Watchdog (AniobWatchdog)
- After every action, execute `DeterministicVerifier` in <10ms:
  - Check if target node disappeared or text changed as predicted.
- **Watchdog Anti-Loop**:
  - Track `(screenHash, action)` pairs in sliding window of size 5.
  - If identical state repeated 3 times -> abort loop and trigger `Reflector` to replan with higher temperature or alternative pathway.

---

## 4. Accessibility Service Configuration

Must be declared in `AndroidManifest.xml` with:
- `android:name="com.aniob.app.service.AniobAccessibilityService"`
- `android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`
- `android:exported="true"`
- Metadata resource referencing `res/xml/aniob_accessibility_service_config.xml`:
  - `canRetrieveWindowContent="true"`
  - `canPerformGestures="true"`
  - `canTakeScreenshot="true"`
  - `accessibilityFeedbackType="feedbackGeneric"`
  - `accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews|flagReportViewIds"`

---

## 5. Persistence & Metric Schemas (Room)

Database name: `aniob_database.db`
Entities:
1. `SessionScore`:
   - `id: Long (PK)`
   - `taskId: String`
   - `userPrompt: String`
   - `status: String` ("SUCCESS", "FAILED", "ABORTED")
   - `totalSteps: Int`
   - `durationMs: Long`
   - `tokensUsed: Int`
   - `providerUsed: String` ("INTENT", "FASTPATH", "LOCAL_SLM", "OMNIROUTE_CLOUD")
   - `decisionReason: String`
   - `timestamp: Long`
2. `AppKnowledgeBase`:
   - `packageFingerprint: String (PK)`
   - `taskSignature: String`
   - `macroStepsJson: String`
   - `successCount: Int`
   - `lastUpdated: Long`
3. `TipEntity`:
   - `id: Long (PK)`
   - `packageName: String`
   - `tipText: String`
   - `confidence: Float`
4. `LogEventEntity`:
   - `id: Long (PK)`
   - `level: String`
   - `tag: String`
   - `message: String`
   - `timestamp: Long`

---

## 6. M0 & M0-Grill Verification Criteria
1. **M0 Smoke**: Natural language query `"Open Settings"` launches `com.android.settings` within 15 seconds using Intent / FastPath ladder.
2. **M0-Grill Smoke**: `"Book a cab"` dynamically invokes `AniobGrillMeSheet` showing at least 2 structured questions with option chips and free-text inputs.
3. **Tracker Screen**: Real-time display with filters: `[All]`, `[Correct]`, `[Failed]`. Zero extra LLM invocations.
4. **No Billing**: Static manifest and code scan confirms zero billing or payment classes.
