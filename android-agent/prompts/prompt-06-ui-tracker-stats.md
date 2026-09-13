# Prompt 06: UI, Tracker, Stats, GrillMeSheet, & Overlay Pill

## Objective
Build modern Material 3 Jetpack Compose UI for Aniob:
1. **Chat Screen**: Interactive task input with natural language execution trigger.
2. **AniobGrillMeSheet**: Dynamic modal bottom sheet showing questions, options (chips/radios), and free-text inputs.
3. **Execution Tracker**: Step-by-step progress monitor with filters:
   - `[All]`
   - `[Correct]` (verified steps)
   - `[Failed]` (reflector/watchdog triggered)
   - Displays latency, tokens used, provider chosen, and deterministic verification results.
4. **Overlay Pill**: Floating minimal status pill (`SYSTEM_ALERT_WINDOW`) shown only during task execution.
5. **Stats & History Screen**: Session score charts, total tokens saved via FastPath, average execution time.
6. **Settings Screen**: Provider selector (Omniroute Cloud vs Local SLM), Omniroute API Key input, FastPath toggle.
