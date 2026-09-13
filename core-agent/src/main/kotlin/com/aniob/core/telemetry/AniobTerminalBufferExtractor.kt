package com.aniob.core.telemetry

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState

/**
 * Extracts and cleans terminal / console buffer content from emulator or terminal apps (e.g. Termux).
 * Pure JVM design.
 */
class AniobTerminalBufferExtractor {

    data class TerminalBuffer(
        val rawLines: List<String>,
        val lastCommand: String?,
        val lastOutput: String?,
        val isPromptReady: Boolean
    )

    fun extractBuffer(screenState: AniobScreenState): TerminalBuffer {
        val terminalNodes = screenState.nodes.filter { node ->
            node.className.contains("Terminal", ignoreCase = true) ||
            node.className.contains("Console", ignoreCase = true) ||
            screenState.packageName.contains("term", ignoreCase = true) ||
            node.text.contains("$") ||
            node.text.contains("#")
        }

        val lines = terminalNodes.map { it.text.trim() }.filter { it.isNotBlank() }
        val lastLine = lines.lastOrNull() ?: ""
        val promptReady = lastLine.endsWith("$") || lastLine.endsWith("#") || lastLine.endsWith(">")

        return TerminalBuffer(
            rawLines = lines,
            lastCommand = lines.getOrNull(lines.size - 2),
            lastOutput = lines.lastOrNull(),
            isPromptReady = promptReady
        )
    }
}
