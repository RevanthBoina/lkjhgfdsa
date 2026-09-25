package com.aniob.app.workflow

import android.content.Context
import com.aniob.core.workflow.PayloadReference
import com.aniob.core.workflow.WorkflowLimits
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * WorkflowPayloadStore: manages private, bounded draft files and attachments.
 * Enforces size limits and atomic writes. Never stores credentials or tokens.
 */
class WorkflowPayloadStore(private val context: Context) {

    private val draftsDir = File(context.filesDir, "workflow_drafts").apply { mkdirs() }
    private val stagingDir = File(context.filesDir, "workflow_staging").apply { mkdirs() }
    private val exportsDir = File(context.cacheDir, "workflow_exports").apply { mkdirs() }

    /**
     * Atomically saves brief text into private draft storage.
     */
    fun saveDraft(taskId: String, briefText: String): PayloadReference {
        val bytes = briefText.toByteArray(Charsets.UTF_8)
        require(bytes.size <= WorkflowLimits.TEXT_BRIEF_MAX_BYTES) {
            "Brief exceeds limit of ${WorkflowLimits.TEXT_BRIEF_MAX_BYTES} bytes (actual: ${bytes.size})"
        }

        val targetFile = File(draftsDir, "$taskId.txt")
        val tempFile = File(draftsDir, "$taskId.tmp")
        tempFile.writeBytes(bytes)
        if (targetFile.exists()) {
            targetFile.delete()
        }
        tempFile.renameTo(targetFile)

        val hash = sha256(bytes)
        return PayloadReference(
            id = taskId,
            mimeType = "text/plain",
            byteCount = bytes.size.toLong(),
            contentHash = hash,
            isSensitive = false,
            requiresConsent = true,
            opaqueUri = targetFile.toURI().toString()
        )
    }

    fun readDraft(taskId: String): String? {
        val file = File(draftsDir, "$taskId.txt")
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    fun deleteDraft(taskId: String) {
        val file = File(draftsDir, "$taskId.txt")
        if (file.exists()) file.delete()
        val tmp = File(draftsDir, "$taskId.tmp")
        if (tmp.exists()) tmp.delete()
    }

    /**
     * Stages an attachment stream up to bounds.
     */
    fun stageAttachment(
        taskId: String,
        attachmentId: String,
        input: InputStream,
        expectedLength: Long
    ): PayloadReference {
        val taskDir = File(stagingDir, taskId).apply { mkdirs() }
        val currentAttachments = taskDir.listFiles()?.size ?: 0
        require(currentAttachments < WorkflowLimits.MAX_ATTACHMENTS) {
            "Attachment count exceeds limit of ${WorkflowLimits.MAX_ATTACHMENTS}"
        }

        val targetFile = File(taskDir, "$attachmentId.bin")
        val tempFile = File(taskDir, "$attachmentId.tmp")

        var totalRead = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)

        tempFile.outputStream().use { out ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                totalRead += read
                if (totalRead > WorkflowLimits.MAX_ATTACHMENT_TOTAL_BYTES) {
                    tempFile.delete()
                    throw IllegalArgumentException("Total attachments exceed maximum allowed bytes (${WorkflowLimits.MAX_ATTACHMENT_TOTAL_BYTES})")
                }
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
            }
            out.flush()
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        tempFile.renameTo(targetFile)

        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return PayloadReference(
            id = attachmentId,
            mimeType = "application/octet-stream",
            byteCount = totalRead,
            contentHash = hash,
            isSensitive = false,
            requiresConsent = true,
            opaqueUri = targetFile.toURI().toString()
        )
    }

    /**
     * Creates an export file in cacheDir/workflow_exports/ for FileProvider sharing.
     */
    fun createExportCopy(filename: String, bytes: ByteArray): File {
        val exportFile = File(exportsDir, filename)
        exportFile.writeBytes(bytes)
        return exportFile
    }

    fun clearExports() {
        exportsDir.listFiles()?.forEach { it.delete() }
    }

    fun clearAll(taskId: String) {
        deleteDraft(taskId)
        File(stagingDir, taskId).deleteRecursively()
    }

    companion object {
        fun sha256(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(bytes).joinToString("") { "%02x".format(it) }
        }

        fun boundUtf8Bytes(text: String, maxBytes: Int): String {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return text
            val truncatedBytes = bytes.copyOf(maxBytes)
            val decoded = String(truncatedBytes, Charsets.UTF_8)
            return decoded.trimEnd('\uFFFD')
        }
    }
}
