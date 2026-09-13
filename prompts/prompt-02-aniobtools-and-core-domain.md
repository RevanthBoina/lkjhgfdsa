# Prompt 02: Aniob Tools & Core Domain Models

## Objective
Implement pure Kotlin JVM domain models and utilities in `:core-agent` with ZERO `android.*` dependencies.

## Key Files:
1. `AniobAction`: Sealed hierarchy (Click, InputText, Swipe, PressKey, Wait, Finish, Fail).
2. `AniobNode`: Normalized representation of an interactive UI element (id, className, text, contentDescription, bounds, isClickable, isEditable, isScrollable).
3. `AniobScreenState`: Actionable tree snapshot, perceptual hash, activity name, package name, timestamp.
4. `AniobTask`: Task definition, user query, status, step history.
5. `AniobPlan`: Step-by-step reasoning decomposition.
6. `AniobFingerprint`: Structural hash computed from package + activity + relative layout anchor geometry.
7. `AniobSafetyGate`: Sensitive action interceptor (blocks unconfirmed money transfer, delete, system modification).
8. `AniobBatteryHealth`: Battery level and thermal status tracker.
