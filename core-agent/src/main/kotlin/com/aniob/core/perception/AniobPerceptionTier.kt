package com.aniob.core.perception

/**
 * User-chosen perception depth tier. The agent never sees this setting;
 * it operates behind a uniform perception and grounding contract.
 */
enum class AniobPerceptionTier {
    FAST,   // One-shot detection on the current screenshot, no reasoning loop
    DEEP,   // ~3-turn loop: UI-tree search + coordinate audit + detection
    MAX     // ~8-turn loop adding zoom, OCR and pixel-level inspection
}
