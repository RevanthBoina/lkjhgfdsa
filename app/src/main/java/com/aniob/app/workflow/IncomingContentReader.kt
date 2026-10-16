package com.aniob.app.workflow

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aniob.core.workflow.WorkflowLimits

data class ParsedIncomingContent(
    val mimeType: String,
    val text: String? = null,
    val uris: List<Uri> = emptyList(),
    val isMultiple: Boolean = false
)

/**
 * IncomingContentReader: safely parses and bounds incoming ACTION_SEND and ACTION_SEND_MULTIPLE intents.
 *
 * Invariant: Received shares create a draft review state, never implicit approval or dispatch.
 */
class IncomingContentReader(private val context: Context) {

    private val seenIntentTokens = object : LinkedHashMap<String, Boolean>(MAX_SEEN_TOKENS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > MAX_SEEN_TOKENS
        }
    }

    @Synchronized
    fun parse(intent: Intent?): ParsedIncomingContent? {
        if (intent == null) return null
        val action = intent.action ?: return null

        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return null
        }

        val rawText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val textDigest = rawText?.let { WorkflowPayloadStore.sha256(it.toByteArray(Charsets.UTF_8)).take(16) } ?: "null"

        // Generate collision-resistant intent fingerprint to prevent duplicate handling on recreation
        val token = "${intent.action}|${intent.type}|${intent.dataString}|$textDigest|${intent.flags}"
        if (seenIntentTokens.containsKey(token)) {
            return null
        }
        seenIntentTokens[token] = true

        val mimeType = intent.type ?: "text/plain"
        val boundedText = rawText?.let {
            WorkflowPayloadStore.boundUtf8Bytes(it, WorkflowLimits.TEXT_BRIEF_MAX_BYTES)
        }

        if (action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.takeIf { isSafeExternalUri(it) }
            val uris = if (uri != null) listOf(uri) else emptyList()
            return ParsedIncomingContent(
                mimeType = mimeType,
                text = boundedText,
                uris = uris,
                isMultiple = false
            )
        } else {
            // ACTION_SEND_MULTIPLE
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            val boundedUris = uris.filter { isSafeExternalUri(it) }.take(WorkflowLimits.MAX_ATTACHMENTS)
            return ParsedIncomingContent(
                mimeType = mimeType,
                text = boundedText,
                uris = boundedUris,
                isMultiple = true
            )
        }
    }

    /**
     * Blocks confused-deputy self-file exfiltration: rejects raw file:// URIs and any URI
     * resolving into the application's private data directory.
     */
    private fun isSafeExternalUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "file") return false
        if (scheme != "content") return false
        val path = uri.path ?: return true
        val dataDir = runCatching { context.applicationInfo?.dataDir }.getOrNull()
        if (!dataDir.isNullOrBlank() && path.contains(dataDir)) return false
        if (path.contains("..")) return false
        return true
    }

    @Synchronized
    fun clearSeenTokens() {
        seenIntentTokens.clear()
    }

    companion object {
        private const val MAX_SEEN_TOKENS = 128
    }
}
