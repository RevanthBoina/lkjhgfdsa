package com.aniob.core.intent

/**
 * Pure JVM Intent Gate Classifier.
 * Classifies tasks BEFORE entering the UI automation loop to avoid hallucinated taps
 * for knowledge questions, web research, or personal memory queries.
 */
enum class AniobIntent {
    DEVICE_AUTOMATION,    // open, tap, send, book, etc. -> UI automation ladder
    KNOWLEDGE_QA,         // what is, who is, explain, summarize -> external AI
    VAULT_QUERY,          // my preference, remember, what did I -> SharedKnowledgeStore
    WEB_RESEARCH,         // search the web, latest news, find information -> external AI with web
    EXTERNAL_AI_QUERY     // general generation, writing, coding -> external AI
}

object AniobTaskIntentClassifier {

    private val deviceVerbs = listOf(
        "open", "tap", "click", "press", "swipe", "type", "input", "send", "book", "launch",
        "turn on", "turn off", "enable", "disable", "go to", "navigate", "play", "search in",
        "call", "text", "message", "set alarm", "create", "delete", "install", "uninstall",
        "close", "restart", "toggle", "switch"
    )

    private val knowledgePrefixes = listOf(
        "what is", "who is", "when is", "where is", "why", "how to", "how does", "explain",
        "summarize", "capital of", "who invented", "what are", "define", "meaning of",
        "translate", "tell me about"
    )

    private val vaultKeywords = listOf(
        "my preference", "remember", "what did i", "my favorite", "personal", "my name",
        "my birthday", "do you remember", "what do you know about me", "save my"
    )

    private val webKeywords = listOf(
        "search the web", "research", "latest news", "find information", "google this",
        "web search", "what's happening", "current events", "today's news"
    )

    fun classify(prompt: String): AniobIntent {
        val lower = prompt.lowercase().trim()
        if (lower.isBlank()) return AniobIntent.EXTERNAL_AI_QUERY

        val hasQuestionWord = lower.startsWith("what") || lower.startsWith("who") ||
                lower.startsWith("when") || lower.startsWith("where") ||
                lower.startsWith("why") || lower.startsWith("how") ||
                lower.contains("?")

        // 1. Vault query check first - personal memory
        if (vaultKeywords.any { lower.contains(it) }) {
            return AniobIntent.VAULT_QUERY
        }

        // 2. Web research
        if (webKeywords.any { lower.contains(it) }) {
            return AniobIntent.WEB_RESEARCH
        }

        // 3. Knowledge QA - what is, who is, explain, etc.
        // e.g., "What is open settings?" is KNOWLEDGE_QA, not DEVICE_AUTOMATION
        if (knowledgePrefixes.any { lower.startsWith(it) } ||
            (hasQuestionWord && !deviceVerbs.any { lower.startsWith(it) || lower.startsWith("please $it") })
        ) {
            return AniobIntent.KNOWLEDGE_QA
        }

        // 4. Device automation requires imperative command at start, not inside question
        // "Open Settings" -> DEVICE, "What is capital?" -> KNOWLEDGE_QA
        val hasImperativeDeviceVerb = deviceVerbs.any { verb ->
            lower.startsWith(verb) || lower.startsWith("please $verb")
        }

        if (hasImperativeDeviceVerb) {
            return AniobIntent.DEVICE_AUTOMATION
        }

        // Default to external AI query for general generation
        return AniobIntent.EXTERNAL_AI_QUERY
    }
}
