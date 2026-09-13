package com.aniob.core.ladder

/**
 * System Intent Shortcut Resolver.
 * Resolves standard system commands directly to Android Intent targets in <15ms with 0 tokens.
 */
data class ResolvedIntentShortcut(
    val action: String,
    val targetPackage: String? = null,
    val uriString: String? = null,
    val category: String? = null,
    val extras: Map<String, String> = emptyMap()
)

object AniobIntentResolver {

    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_DIAL = "android.intent.action.DIAL"
    const val ACTION_SENDTO = "android.intent.action.SENDTO"
    const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"

    /**
     * Resolves natural language commands to direct Android Intent actions if possible.
     */
    fun resolve(command: String): ResolvedIntentShortcut? {
        val lower = command.lowercase().trim()

        return when {
            lower == "open settings" || lower == "settings" || lower == "launch settings" -> {
                ResolvedIntentShortcut(
                    action = ACTION_MAIN,
                    targetPackage = "com.android.settings",
                    category = CATEGORY_LAUNCHER
                )
            }
            lower == "open calculator" || lower == "calculator" -> {
                ResolvedIntentShortcut(
                    action = ACTION_MAIN,
                    targetPackage = "com.google.android.calculator",
                    category = CATEGORY_LAUNCHER
                )
            }
            lower == "open camera" || lower == "launch camera" -> {
                ResolvedIntentShortcut(
                    action = "android.media.action.IMAGE_CAPTURE"
                )
            }
            lower.startsWith("call ") -> {
                val number = lower.removePrefix("call ").filter { it.isDigit() || it == '+' }
                if (number.isNotBlank()) {
                    ResolvedIntentShortcut(
                        action = ACTION_DIAL,
                        uriString = "tel:$number"
                    )
                } else null
            }
            lower.startsWith("open website ") || lower.startsWith("browse ") -> {
                val url = lower.substringAfter(" ").trim()
                val targetUrl = if (url.startsWith("http")) url else "https://$url"
                ResolvedIntentShortcut(
                    action = ACTION_VIEW,
                    uriString = targetUrl
                )
            }
            else -> null
        }
    }
}
