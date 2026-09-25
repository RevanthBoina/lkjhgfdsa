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

    private val seenIntentTokens = mutableSetOf<String>()

    fun parse(intent: Intent?): ParsedIncomingContent? {
        if (intent == null) return null
        val action = intent.action ?: return null

        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            return null
        }

        // Generate intent fingerprint to prevent duplicate handling on recreation
        val token = "${intent.action}|${intent.type}|${intent.dataString}|${intent.getStringExtra(Intent.EXTRA_TEXT)?.hashCode()}|${intent.flags}"
        if (seenIntentTokens.contains(token)) {
            return null
        }
        seenIntentTokens.add(token)

        val mimeType = intent.type ?: "text/plain"

        if (action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.take(WorkflowLimits.TEXT_BRIEF_MAX_BYTES)
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            val uris = if (uri != null) listOf(uri) else emptyList()
            return ParsedIncomingContent(
                mimeType = mimeType,
                text = text,
                uris = uris,
                isMultiple = false
            )
        } else {
            // ACTION_SEND_MULTIPLE
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            val boundedUris = uris.take(WorkflowLimits.MAX_ATTACHMENTS)
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.take(WorkflowLimits.TEXT_BRIEF_MAX_BYTES)
            return ParsedIncomingContent(
                mimeType = mimeType,
                text = text,
                uris = boundedUris,
                isMultiple = true
            )
        }
    }

    fun clearSeenTokens() {
        seenIntentTokens.clear()
    }
}
