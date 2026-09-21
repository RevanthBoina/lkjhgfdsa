package com.aniob.app.ui

/**
 * Navigation routes (UX-0 §2). CHAT is the only screen where Back may exit (double-back).
 * SKILLS and TRUST are placeholders owned by UX-5 / UX-4.
 */
enum class AniobRoute {
    CHAT,
    HISTORY,
    STATS,
    MODELS,
    SETTINGS,
    ONBOARDING,
    SKILLS,
    TRUST
}
