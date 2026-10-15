package com.aniob.core.tools

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import java.security.MessageDigest

/**
 * Computes structural fingerprints for screens and nodes.
 * Used by FastPath for verifying UI state consistency before replaying macro actions.
 */
object AniobFingerprint {

    // Matches volatile status-bar clocks (e.g., "10:42", "10:42 AM") and battery/progress percentages ("87%")
    private val VOLATILE_TIME_OR_PCT_REGEX = Regex("\\b\\d{1,2}:\\d{2}(?:\\s*[AaPp][Mm])?\\b|\\b\\d{1,3}%")

    private fun normalizeVolatileText(raw: String): String {
        if (raw.isEmpty()) return raw
        return VOLATILE_TIME_OR_PCT_REGEX.replace(raw, "#")
    }

    fun computeScreenFingerprint(screen: AniobScreenState): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(screen.packageName.toByteArray())
        digest.update(screen.activityName.toByteArray())

        // Sort nodes by relative position to make fingerprint robust against minor timing shifts
        screen.nodes
            .sortedWith(compareBy({ it.bounds.top / 50 }, { it.bounds.left / 50 }))
            .take(30)
            .forEach { node ->
                val normText = normalizeVolatileText(node.text)
                val normDesc = normalizeVolatileText(node.contentDescription)
                val descriptor = "${node.className}:$normText:$normDesc:${node.viewId}"
                digest.update(descriptor.toByteArray())
            }

        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    fun computeNodeFingerprint(node: AniobNode, packageName: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val descriptor = "$packageName|${node.className}|${node.text}|${node.contentDescription}|${node.viewId}"
        return digest.digest(descriptor.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
    }
}
