package com.aniob.core.workflow

/**
 * Result of the workflow routing decision.
 */
sealed class WorkflowDecision {
    data class DirectAction(val action: WorkflowAction) : WorkflowDecision()
    data class NeedsChooser(
        val candidates: List<Destination>,
        val prompt: String,
        val briefText: String? = null
    ) : WorkflowDecision()
    data class NeedsClarification(val question: String) : WorkflowDecision()
    data class DelegateToExisting(val prompt: String) : WorkflowDecision()
}

/**
 * Goal & capability router for everyday workflows.
 * Zero android.* imports.
 */
object WorkflowRouter {

    val DEFAULT_BUILDER_OPTIONS: List<Destination> = listOf(
        Destination(
            handler = "lovable.dev",
            url = "https://lovable.dev/",
            category = DestinationCategory.BUILDER_SERVICE,
            provenance = DestinationProvenance.BUILTIN
        ),
        Destination(
            handler = "bolt.new",
            url = "https://bolt.new/",
            category = DestinationCategory.BUILDER_SERVICE,
            provenance = DestinationProvenance.BUILTIN
        ),
        Destination(
            handler = "replit.com",
            url = "https://replit.com/",
            category = DestinationCategory.BUILDER_SERVICE,
            provenance = DestinationProvenance.BUILTIN
        )
    )

    val DEFAULT_REASONING_DESTINATION: Destination = Destination(
        handler = "chatgpt.com",
        url = "https://chatgpt.com/",
        category = DestinationCategory.REASONING_SERVICE,
        provenance = DestinationProvenance.BUILTIN
    )

    /**
     * Inspects natural language prompt and makes a routing decision.
     */
    fun route(
        prompt: String,
        taskId: String = "wf_${System.currentTimeMillis()}",
        userPreferredDestination: Destination? = null
    ): WorkflowDecision {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) {
            return WorkflowDecision.DelegateToExisting(trimmed)
        }

        val normalized = trimmed.lowercase()

        // 1. Distinguish explaining vs building
        if (isExplanationRequest(normalized)) {
            // Explaining how to build a website/app is informational Q&A -> existing flow
            return WorkflowDecision.DelegateToExisting(trimmed)
        }

        // 2. Direct website URL opening or browsing
        val extractedUrl = extractUrl(trimmed)
        if (extractedUrl != null) {
            val validatedUrl = validateAndSanitizeUrl(extractedUrl)
            if (validatedUrl != null) {
                val host = extractHost(validatedUrl)
                val destination = Destination(
                    handler = host,
                    url = validatedUrl,
                    category = DestinationCategory.WEBSITE,
                    provenance = DestinationProvenance.USER_INPUT
                )
                val action = WorkflowAction.OpenWebsite(
                    actionId = "action_${System.currentTimeMillis()}",
                    destination = destination,
                    briefText = null
                )
                return WorkflowDecision.DirectAction(action)
            } else {
                return WorkflowDecision.NeedsClarification("The provided website link has an invalid or insecure address format.")
            }
        }

        // 3. Hosted reasoning requests
        if (isHostedReasoningRequest(normalized)) {
            val destination = userPreferredDestination ?: DEFAULT_REASONING_DESTINATION
            val action = WorkflowAction.OpenWebsite(
                actionId = "action_${System.currentTimeMillis()}",
                destination = destination,
                briefText = trimmed
            )
            return WorkflowDecision.DirectAction(action)
        }

        // 4. Website / app creation requests
        if (isWebsiteCreationRequest(normalized)) {
            if (userPreferredDestination != null) {
                val action = WorkflowAction.OpenWebsite(
                    actionId = "action_${System.currentTimeMillis()}",
                    destination = userPreferredDestination,
                    briefText = trimmed
                )
                return WorkflowDecision.DirectAction(action)
            }
            return WorkflowDecision.NeedsChooser(
                candidates = DEFAULT_BUILDER_OPTIONS,
                prompt = trimmed,
                briefText = trimmed
            )
        }

        // 5. Note taking / editing requests
        if (isNoteCreationRequest(normalized)) {
            val noteDestination = Destination(
                handler = "com.google.android.keep",
                category = DestinationCategory.APP,
                provenance = DestinationProvenance.SYSTEM_RESOLVED
            )
            val action = WorkflowAction.OpenApp(
                actionId = "action_${System.currentTimeMillis()}",
                destination = noteDestination
            )
            return WorkflowDecision.DirectAction(action)
        }

        // Fallback: delegate to existing pipeline (Q&A or screen automation)
        return WorkflowDecision.DelegateToExisting(trimmed)
    }

    private fun isExplanationRequest(normalized: String): Boolean {
        return normalized.startsWith("how to ") ||
            normalized.startsWith("explain ") ||
            normalized.startsWith("what is ") ||
            normalized.startsWith("tell me about ") ||
            normalized.contains("how do i create a website") ||
            normalized.contains("how does a website work")
    }

    private fun isWebsiteCreationRequest(normalized: String): Boolean {
        return (normalized.startsWith("create a website") ||
            normalized.startsWith("build a website") ||
            normalized.startsWith("make a website") ||
            normalized.startsWith("create website") ||
            normalized.startsWith("build website") ||
            normalized.startsWith("make website") ||
            normalized.startsWith("create a web app") ||
            normalized.startsWith("build a web app") ||
            normalized.startsWith("make a web app")) &&
            !normalized.startsWith("how to")
    }

    private fun isHostedReasoningRequest(normalized: String): Boolean {
        return normalized.contains("chatgpt") ||
            normalized.startsWith("ask reasoning") ||
            normalized.startsWith("use chatgpt") ||
            normalized.startsWith("open chatgpt")
    }

    private fun isNoteCreationRequest(normalized: String): Boolean {
        return normalized.startsWith("create a note") ||
            normalized.startsWith("create note") ||
            normalized.startsWith("take a note") ||
            normalized.startsWith("take note") ||
            normalized.startsWith("open notes") ||
            normalized.startsWith("write a note")
    }

    /**
     * Extracts URL from original input to preserve case sensitivity of paths and query parameters.
     */
    fun extractUrl(originalInput: String): String? {
        val tokens = originalInput.split("\\s+".toRegex())
        for (token in tokens) {
            val clean = token.trim().trimEnd('.', ',', ';', '!', '?')
            if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
                return clean
            }
            if (clean.startsWith("www.", ignoreCase = true)) {
                return "https://$clean"
            }
        }
        return null
    }

    /**
     * Validates and sanitizes a URL.
     * Enforces HTTPS, rejects control characters, invalid schemes, and embedded credentials.
     */
    fun validateAndSanitizeUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        // Check for control characters
        for (c in trimmed) {
            if (c.code in 0..31 || c.code == 127) return null
        }

        // Scheme check
        val lower = trimmed.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("data:")) {
            return null
        }

        val targetUrl = if (lower.startsWith("http://")) {
            "https://" + trimmed.substring(7)
        } else if (!lower.startsWith("https://")) {
            "https://$trimmed"
        } else {
            trimmed
        }

        // Check for embedded credentials user:pass@
        val afterScheme = targetUrl.substring("https://".length)
        val hostPart = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostPart.contains('@')) {
            return null
        }

        if (hostPart.isBlank()) return null

        return targetUrl
    }

    private fun extractHost(url: String): String {
        val afterScheme = if (url.startsWith("https://", ignoreCase = true)) {
            url.substring(8)
        } else if (url.startsWith("http://", ignoreCase = true)) {
            url.substring(7)
        } else {
            url
        }
        return afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    }
}
