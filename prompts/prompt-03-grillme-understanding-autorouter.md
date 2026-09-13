# Prompt 03: GrillMe Dynamic Disambiguation & AutoRouter

## Objective
Implement dynamic human-in-the-loop task clarification ("Grill-Me") and hybrid model routing ("AutoRouter").

## Key Components:
1. `AniobGrillMeEngine`:
   - Evaluates task ambiguity dynamically.
   - Prompts LLM to generate structured questions with chip/radio options and free text fallback.
   - Returns structured `AniobGrillMeResult`.
2. `AniobAutoRouter`:
   - Decision rules:
     - If FastPath hit -> bypass LLM (0ms, 0 tokens).
     - If battery < 20% -> cloud (offload device compute).
     - If device offline -> local-small SLM.
     - If screen requires vision (canvas, unlabelled custom icons) -> Omniroute Cloud (`gpt-4o`).
     - If simple text navigation in standard Android views -> local-small SLM.
   - Logs decision reason to `MetricSnapshot.providerDecisionReason`.
