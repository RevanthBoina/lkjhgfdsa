package com.aniob.core.ladder

import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.knowledge.AppLookup

/**
 * A zero-token way to satisfy a goal.
 *
 * [requiresObservation] is the field that fixes the "Open Clock" bug: a shortcut that opens an app
 * can only be called done by looking at the screen afterwards, so it must never be turned into a
 * `DirectIntent` (which finishes without a post-condition and is therefore reported as FAILED).
 * Reflex shortcuts — flashlight, battery, dial, open URL — are the opposite: their effect *is* the
 * dispatch, and they may finish immediately.
 */
data class ResolvedIntentShortcut(
    val action: String,
    val targetPackage: String? = null,
    val uriString: String? = null,
    val category: String? = null,
    val extras: Map<String, String> = emptyMap(),
    /** Fully-qualified `<pkg>/<pkg>.Activity` when the registry knew it; explicit beats implicit. */
    val launchActivity: String? = null,
    /** True when the only honest way to confirm this is to observe the screen after dispatch. */
    val requiresObservation: Boolean = false,
    /** App label for the transcript ("Opening Clock…"), when the target is an app. */
    val label: String? = null
)

/**
 * System Intent Shortcut Resolver.
 *
 * Resolves standard system commands directly to Android Intent targets in <15ms with 0 tokens.
 *
 * **What changed and why.** This object used to own a hand-written table of app names
 * (`"open clock" -> SHOW_ALARMS`, `"open instagram" -> com.instagram.android`, …) and that table was
 * the root of the launch failures:
 *
 *  - `"open clock"` returned an action with `targetPackage = null`, so nothing could be
 *    installed-checked and nothing could be observed afterwards; the step ended FAILED even when the
 *    clock app was open;
 *  - every app not in the table fell through to the model, i.e. the *default* way to open an app
 *    cost tokens;
 *  - a hardcoded package (`com.instagram.android`) fired an unobservable intent on a device where
 *    that package does not exist, instead of escalating.
 *
 * App opening is now a **lookup**: [AniobLaunchHub] resolves the name against [AniobAppRegistry]
 * (built at startup from the platform's own launcher inventory) and returns a package — plus the
 * launcher component when known. Only genuine *system reflexes* (torch, battery, dial, URL, geo)
 * remain literal here, because for those the dispatch really is the outcome.
 */
object AniobIntentResolver {

    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_DIAL = "android.intent.action.DIAL"
    const val ACTION_SENDTO = "android.intent.action.SENDTO"
    const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    const val ACTION_FLASHLIGHT = "android.intent.action.FLASHLIGHT"

    const val ACTION_SET_ALARM = "android.intent.action.SET_ALARM"
    const val CATEGORY_APP_CALCULATOR = "android.intent.category.APP_CALCULATOR"
    const val CATEGORY_APP_CALENDAR = "android.intent.category.APP_CALENDAR"
    const val CATEGORY_APP_BROWSER = "android.intent.category.APP_BROWSER"
    const val CATEGORY_APP_FILES = "android.intent.category.APP_FILES"
    const val ACTION_IMAGE_CAPTURE = "android.media.action.IMAGE_CAPTURE"

    // System actions surfaced through extras so the executor can dispatch
    // them without an explicit Android Intent (pure JVM core-agent).
    const val EXTRA_SYSTEM_ACTION = "aniob_system_action"
    const val SYSTEM_ACTION_FLASHLIGHT = "turn_on_flashlight"
    const val SYSTEM_ACTION_GET_DEVICE_INFO = "get_device_info"

    private const val ACTION_GEO = "geo:0,0?q="

    /**
     * Resolves natural language commands to direct Android Intent actions if possible.
     *
     * Returns null when the command is not a shortcut at all — the ladder then treats it as a normal
     * task. A **non-null result is never a promise that the goal is met**: check
     * [ResolvedIntentShortcut.requiresObservation] before finishing on it.
     */
    fun resolve(command: String): ResolvedIntentShortcut? {
        val raw = command.trim()
        if (raw.isEmpty()) return null
        val lower = raw.lowercase().trim().trimEnd('?', '.', '!', ' ', ',', ';')

        return systemReflex(lower)
            ?: dial(lower)
            ?: url(raw, lower)
            ?: maps(raw, lower)
            ?: appViaRegistry(raw)
            ?: clockOrAlarm(lower)
            ?: appCategory(lower)
    }

    // ------------------------------------------------------------------ #
    // reflexes: dispatch IS the outcome, nothing to observe
    // ------------------------------------------------------------------ #

    private fun systemReflex(lower: String): ResolvedIntentShortcut? = when (lower) {
        "turn on flashlight", "flashlight on", "open flashlight", "turn on the flashlight",
        "open the flashlight", "flashlight" -> ResolvedIntentShortcut(
            action = ACTION_FLASHLIGHT,
            extras = mapOf(EXTRA_SYSTEM_ACTION to SYSTEM_ACTION_FLASHLIGHT)
        )
        "check battery", "battery status", "what is my battery", "check battery level", "battery" ->
            ResolvedIntentShortcut(
                action = ACTION_MAIN,
                extras = mapOf(EXTRA_SYSTEM_ACTION to SYSTEM_ACTION_GET_DEVICE_INFO)
            )
        else -> null
    }

    private fun dial(lower: String): ResolvedIntentShortcut? {
        if (!lower.startsWith("call ")) return null
        val number = lower.removePrefix("call ").filter { it.isDigit() || it == '+' }
        if (number.isBlank()) return null
        return ResolvedIntentShortcut(action = ACTION_DIAL, uriString = "tel:$number")
    }

    private fun url(raw: String, lower: String): ResolvedIntentShortcut? {
        // These prefixes are ASCII, so their length is identical in `raw` and in `lower`.
        for (prefix in listOf("open website ", "open url ", "go to website ", "browse ")) {
            if (!lower.startsWith(prefix)) continue
            val validated = sanitizeAndValidateUrl(raw.substring(prefix.length).trim()) ?: return null
            return ResolvedIntentShortcut(action = ACTION_VIEW, uriString = validated)
        }
        return null
    }

    private fun maps(raw: String, lower: String): ResolvedIntentShortcut? = when {
        lower == "open maps" || lower == "maps" || lower == "launch maps" || lower == "open map" ->
            ResolvedIntentShortcut(action = ACTION_VIEW, uriString = ACTION_GEO)
        lower.startsWith("navigate to ") -> {
            // Destination, not a URL: keep the user's capitalisation, escape what a URI cannot hold.
            val destination = raw.trim().substring("navigate to ".length).trim()
            if (destination.isBlank()) null
            else ResolvedIntentShortcut(action = ACTION_VIEW, uriString = ACTION_GEO + escapeQuery(destination))
        }
        else -> null
    }

    /** `navigate to X` carries a destination, not a URL: escape it instead of validating it. */
    private fun escapeQuery(text: String): String =
        text.replace("%", "%25").replace(" ", "%20").replace("#", "%23").replace("&", "%26")

    // ------------------------------------------------------------------ #
    // apps: a lookup, never a table
    // ------------------------------------------------------------------ #

    /**
     * The one path for "open <app>", at any point in a task.
     *
     * The registry is preferred; a seed of stock-Android package ids is the last resort so a cold
     * cache degrades to "ask the platform", not to "no idea what Clock means". The caller still
     * confirms the package is launchable, so a wrong seed can never dispatch.
     */
    private fun appViaRegistry(raw: String): ResolvedIntentShortcut? {
        val plan = AniobLaunchHub.plan(raw, AniobAppRegistry.getInstance()) ?: return null
        return appShortcut(plan)
    }

    private fun appShortcut(plan: LaunchPlan): ResolvedIntentShortcut = ResolvedIntentShortcut(
        action = ACTION_MAIN,
        targetPackage = plan.packageName,
        category = CATEGORY_LAUNCHER,
        launchActivity = plan.launchActivity,
        requiresObservation = true,
        label = plan.label
    )

    /**
     * "set an alarm" / "show clock" / "clock".
     *
     * These used to be implicit intents (`SET_ALARM`, `SHOW_ALARMS`) with no package. They now open
     * the *clock app* when one is known, because "the app is in the foreground" is verifiable and
     * "some activity handled SET_ALARM" is not. When no clock package is known the implicit intent is
     * still returned, marked unobservable, so the caller can decide to escalate instead of claiming
     * success.
     */
    private fun clockOrAlarm(lower: String): ResolvedIntentShortcut? {
        val alarmPhrases = listOf("set alarm", "set an alarm", "set the alarm", "add an alarm")
        val clockPhrases = listOf("clock", "show clock", "open clock app")
        val isAlarm = alarmPhrases.any { lower == it || lower.startsWith("$it ") }
        val isClock = clockPhrases.any { lower == it }
        if (!isAlarm && !isClock) return null

        val registry = AniobAppRegistry.getInstance()
        val clock = registry.appForRole(AniobAppRegistry.ROLE_CLOCK) ?: when (val found = registry.resolveWithSeed("clock")) {
            is AppLookup.Match -> found.app
            else -> null
        }
        if (clock != null) {
            return ResolvedIntentShortcut(
                action = ACTION_MAIN,
                targetPackage = clock.packageName,
                category = CATEGORY_LAUNCHER,
                launchActivity = clock.launchActivity,
                extras = if (isAlarm) mapOf("android.intent.extra.alarm_editor" to "true") else emptyMap(),
                requiresObservation = true,
                label = clock.label
            )
        }
        return ResolvedIntentShortcut(
            action = if (isAlarm) ACTION_SET_ALARM else "android.intent.action.SHOW_ALARMS",
            requiresObservation = false,
            label = "Clock"
        )
    }

    /** Last resort for the platform's own app categories: name a package, never a bare category. */
    private fun appCategory(lower: String): ResolvedIntentShortcut? {
        val role = when {
            lower == "open calculator" || lower == "calculator" || lower == "launch calculator" -> AniobAppRegistry.ROLE_CALCULATOR
            lower == "open calendar" || lower == "calendar" || lower == "launch calendar" -> AniobAppRegistry.ROLE_CALENDAR
            lower == "open browser" || lower == "browser" || lower == "launch browser" -> AniobAppRegistry.ROLE_BROWSER
            lower == "open files" || lower == "files" || lower == "launch files" -> AniobAppRegistry.ROLE_FILES
            lower == "open camera" || lower == "launch camera" || lower == "camera" -> AniobAppRegistry.ROLE_CAMERA
            lower == "open settings" || lower == "settings" || lower == "launch settings" -> AniobAppRegistry.ROLE_SETTINGS
            else -> null
        } ?: return null

        val registry = AniobAppRegistry.getInstance()
        val app = registry.appForRole(role) ?: when (val found = registry.resolveWithSeed(role)) {
            is AppLookup.Match -> found.app
            else -> null
        }
        if (app != null) {
            return ResolvedIntentShortcut(
                action = ACTION_MAIN,
                targetPackage = app.packageName,
                category = CATEGORY_LAUNCHER,
                launchActivity = app.launchActivity,
                requiresObservation = true,
                label = app.label
            )
        }
        // No package known: hand back the category intent, unobservable, so the caller escalates
        // rather than firing an intent whose effect it cannot check.
        val categoryIntent = when (role) {
            AniobAppRegistry.ROLE_CALCULATOR -> CATEGORY_APP_CALCULATOR
            AniobAppRegistry.ROLE_CALENDAR -> CATEGORY_APP_CALENDAR
            AniobAppRegistry.ROLE_BROWSER -> CATEGORY_APP_BROWSER
            AniobAppRegistry.ROLE_FILES -> CATEGORY_APP_FILES
            AniobAppRegistry.ROLE_CAMERA -> ACTION_IMAGE_CAPTURE
            else -> null
        }
        if (categoryIntent == null) return null
        return ResolvedIntentShortcut(action = ACTION_MAIN, category = categoryIntent, requiresObservation = true)
    }

    /** Kept public: URL validation is a security-relevant pure function, and it is unit-tested. */
    fun sanitizeAndValidateUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        for (c in trimmed) {
            if (c.code in 0..31 || c.code == 127) return null
        }

        val lower = trimmed.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("file:") ||
            lower.startsWith("content:") || lower.startsWith("data:")
        ) {
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
