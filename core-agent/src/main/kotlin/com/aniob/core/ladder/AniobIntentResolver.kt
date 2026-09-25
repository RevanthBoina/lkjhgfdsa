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
    const val ACTION_FLASHLIGHT = "android.intent.action.FLASHLIGHT"

    // System actions surfaced through extras so the executor can dispatch
    // them without an explicit Android Intent (pure JVM core-agent).
    const val EXTRA_SYSTEM_ACTION = "aniob_system_action"
    const val SYSTEM_ACTION_FLASHLIGHT = "turn_on_flashlight"
    const val SYSTEM_ACTION_GET_DEVICE_INFO = "get_device_info"

    /**
     * Resolves natural language commands to direct Android Intent actions if possible.
     */
    fun resolve(command: String): ResolvedIntentShortcut? {
        val lower = command.lowercase().trim().trimEnd('?', '.', '!', ' ', ',', ';')

        return when {
            lower == "turn on flashlight" || lower == "flashlight on" || lower == "open flashlight" || lower == "turn on the flashlight" || lower == "open the flashlight" -> {
                ResolvedIntentShortcut(
                    action = ACTION_FLASHLIGHT,
                    extras = mapOf(EXTRA_SYSTEM_ACTION to SYSTEM_ACTION_FLASHLIGHT)
                )
            }
            lower == "check battery" || lower == "battery status" || lower == "what is my battery" || lower == "check battery level" -> {
                ResolvedIntentShortcut(
                    action = ACTION_MAIN,
                    extras = mapOf(EXTRA_SYSTEM_ACTION to SYSTEM_ACTION_GET_DEVICE_INFO)
                )
            }
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
                    category = "android.intent.category.APP_CALCULATOR"
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
            lower.startsWith("open website ") -> {
                val prefixLen = command.indexOf("open website ", ignoreCase = true) + "open website ".length
                val raw = command.substring(prefixLen).trim()
                val validated = sanitizeAndValidateUrl(raw)
                if (validated != null) {
                    ResolvedIntentShortcut(
                        action = ACTION_VIEW,
                        uriString = validated
                    )
                } else null
            }
            lower.startsWith("browse ") -> {
                val prefixLen = command.indexOf("browse ", ignoreCase = true) + "browse ".length
                val raw = command.substring(prefixLen).trim()
                val validated = sanitizeAndValidateUrl(raw)
                if (validated != null) {
                    ResolvedIntentShortcut(
                        action = ACTION_VIEW,
                        uriString = validated
                    )
                } else null
            }
            else -> null
        }
    }

    fun sanitizeAndValidateUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        for (c in trimmed) {
            if (c.code in 0..31 || c.code == 127) return null
        }

        val lower = trimmed.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("data:")) {
            return null
        }

        val targetUrl = if (lower.startsWith("http://") || lower.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val schemeEnd = targetUrl.indexOf("://")
        if (schemeEnd == -1) return null
        val afterScheme = targetUrl.substring(schemeEnd + 3)
        val hostPart = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostPart.contains('@') || hostPart.isBlank()) {
            return null
        }

        return targetUrl
    }
}
