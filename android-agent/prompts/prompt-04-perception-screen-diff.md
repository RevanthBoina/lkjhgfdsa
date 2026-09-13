# Prompt 04: Perception, 4-Level Waterfall ScreenDiff, & Token Optimizer

## Objective
Implement perception pipeline with pin-to-pin betterments over ARTEMIS, MobileAgent, and AppAgent.

## Key Components:
1. `AniobScreenDiff`:
   - 4-Level Waterfall:
     1. Event-driven skip: If no a11y window or view state change occurred, skip screenshot (0ms).
     2. Perceptual tree hash comparison: If tree hash is identical, reuse cached screenshot.
     3. Changed region crop: If change is localized, extract bounding box.
     4. Downscaled JPEG q80: Scale bitmap so max dimension <= 1120px at quality 80.
2. `AniobTokenOptimizer`:
   - Prunes invisible, zero-area, or uninformative wrapper views.
   - Restricts total actionable nodes to at most **60 nodes**.
   - Tags each node with Set-of-Mark (SoM) integer identifier [1..60].
   - Compiles ultra-dense structural text representation for minimal LLM token consumption.
