# Prompt 05: Execution Router Ladder, Intent Shortcuts, & FastPath

## Objective
Implement execution dispatch ladder maximizing speed and minimizing unnecessary LLM calls.

## Execution Ladder:
1. **Rung 0: `AniobIntentResolver`**
   - Direct Android Intent shortcuts: `ACTION_CALL`, `ACTION_VIEW`, `ACTION_SENDTO`, and app launch intents.
   - Example: "Open Settings" -> resolves directly to `com.android.settings` launcher intent in <15ms.
2. **Rung 1: `AniobReplayEngine` (FastPath)**
   - Checks `AppKnowledgeBase` for matching (package, taskSignature).
   - If trajectory exists with >= 1 success, replays sequence of actions.
   - Validates node fingerprint before each action; falls back to LLM if UI diverges.
3. **Rung 2: Local SLM**
   - Dispatches step to local Qwen2.5-1.5B model.
4. **Rung 3: Omniroute Cloud**
   - Dispatches step with optimized screenshot and tree to Omniroute Cloud.

## Verification & Watchdog:
- `DeterministicVerifier`: Executes in <10ms without LLM, checking if target node state changed.
- `AniobWatchdog`: Detects loop (same fingerprint/action 3 times) -> triggers Reflector replan.
