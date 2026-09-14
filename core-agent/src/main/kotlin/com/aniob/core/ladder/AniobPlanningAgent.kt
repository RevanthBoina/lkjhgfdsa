package com.aniob.core.ladder

data class TaskProgress(val completed: List<String>, val pending: List<String>, val summary: String)

class AniobPlanningAgent {
    fun updateProgress(
        userInstruction: String,
        previousOperation: String?,
        previousProgress: TaskProgress?,
        focusContent: String?
    ): TaskProgress {
        val completed = previousProgress?.completed?.toMutableList() ?: mutableListOf()
        if (previousOperation != null && previousOperation.isNotBlank()) {
            if (completed.size < 10) completed.add(previousOperation)
        }
        val pending = mutableListOf<String>()
        val lower = userInstruction.lowercase()
        if (completed.isEmpty()) {
            pending.add(userInstruction)
        } else {
            pending.add("Continue: $userInstruction")
            if (focusContent != null) pending.add("Use focus: $focusContent")
        }
        val summary = buildString {
            append("Task: $userInstruction | ")
            append("Completed ${completed.size}: ${completed.takeLast(3).joinToString(", ")} | ")
            append("Pending: ${pending.joinToString(", ")}")
        }
        return TaskProgress(completed, pending, summary)
    }
}
