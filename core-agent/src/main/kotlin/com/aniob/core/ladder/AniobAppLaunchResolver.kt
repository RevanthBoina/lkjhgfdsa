package com.aniob.core.ladder

import com.aniob.core.knowledge.AppCatalogEntry

/** An app the task names, resolved from the installed-app catalog without asking a model. */
data class AppLaunchTarget(val packageName: String, val appName: String)

/**
 * Resolves "open <app> and …" to a direct app launch, so step 0 costs zero model calls.
 *
 * The model is expensive and, on a cold task, it is asked something it cannot know better: which
 * package the user means by "Clock". The catalog already knows. Resolving it here turns the first
 * action of most real tasks into a single `OpenApp` intent dispatch, and the model only sees the
 * screen that actually matters.
 *
 * Matching is deliberately conservative:
 * - the prompt must begin with an open/launch/start verb (the same imperative test the intent gate
 *   uses), so a mention of an app inside a sentence never triggers a launch;
 * - the app name must appear at the **start** of the remainder, on a word boundary;
 * - the longest matching name wins, and a tie is treated as ambiguous and ignored.
 */
object AniobAppLaunchResolver {

    private val LAUNCH_PREFIX = Regex(
        "^(?:please\\s+)?(?:open|launch|start|run|go\\s+to)\\s+(?:the\\s+|my\\s+|your\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )

    /**
     * @param prompt the task text as typed by the user.
     * @param entries installed apps; an empty list disables the shortcut.
     * @param isInstalled optional check so a stale catalog entry cannot launch a removed app.
     */
    fun resolve(
        prompt: String,
        entries: List<AppCatalogEntry>,
        isInstalled: (String) -> Boolean = { true }
    ): AppLaunchTarget? {
        val trimmed = prompt.trim()
        val remainder = LAUNCH_PREFIX.find(trimmed)?.groupValues?.get(1)?.trim() ?: return null
        if (remainder.isBlank()) return null
        val lower = remainder.lowercase()

        var best: AppLaunchTarget? = null
        var bestLength = 0
        var tied = false

        for (entry in entries) {
            if (entry.packageName.isBlank() || entry.appName.isBlank()) continue
            for (name in listOf(entry.appName) + entry.aliases) {
                val candidate = name.trim().lowercase()
                if (candidate.length < 2) continue
                if (!startsWithWord(lower, candidate)) continue
                when {
                    candidate.length > bestLength -> {
                        best = AppLaunchTarget(entry.packageName, entry.appName)
                        bestLength = candidate.length
                        tied = false
                    }
                    candidate.length == bestLength && best?.packageName != entry.packageName -> tied = true
                }
            }
        }
        val target = best ?: return null
        if (tied) return null
        if (!isInstalled(target.packageName)) return null
        return target
    }

    /** True when [text] starts with [word] and the character after it is not a letter or digit. */
    private fun startsWithWord(text: String, word: String): Boolean {
        if (!text.startsWith(word)) return false
        if (text.length == word.length) return true
        return !text[word.length].isLetterOrDigit()
    }
}
