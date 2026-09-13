package com.aniob.core.memory

/**
 * 3-Layer Working Memory Architecture:
 * Layer 1: Short-term dialogue buffer (last-K turns)
 * Layer 2: Structured facts & cross-chat key-values (AniobSharedKnowledgeStore)
 * Layer 3: Historical episodic memory & learned procedures (AniobLearnedProcedureStore)
 */
class AniobWorkingMemory(
    val shortTermMemory: AniobContextualMemory = AniobContextualMemory(maxMessages = 15),
    val structuredFacts: AniobSharedKnowledgeStore = InMemorySharedKnowledgeStore(),
    val episodicStore: AniobLearnedProcedureStore = AniobLearnedProcedureStore()
) {
    /**
     * Builds integrated context payload for model planning.
     */
    fun compileContext(currentGoal: String, packageName: String): String {
        val facts = structuredFacts.getAll()
        val playbook = episodicStore.findPlaybook(currentGoal)

        return buildString {
            appendLine("=== CONTEXTUAL WORKING MEMORY ===")
            if (facts.isNotEmpty()) {
                appendLine("[Structured Facts]")
                facts.forEach { (k, v) -> appendLine(" - $k: $v") }
            }
            if (playbook != null) {
                appendLine("[Proven Historical Playbook Found]")
                appendLine(" - Executed: ${playbook.executionCount} times in ${playbook.packageName}")
            }
            appendLine("[Recent Dialogue]")
            shortTermMemory.getRecentHistory().takeLast(5).forEach { msg ->
                appendLine(" - ${msg.role}: ${msg.content}")
            }
        }
    }
}
