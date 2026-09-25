package com.aniob.core.memory

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SwipeDirection
import java.io.File

/**
 * Short-term, append-only trajectory log that consolidates into long-term
 * Skill Format v2.1 YAML skills after task success — the "hippocampus → cortex
 * sleep consolidation" analogy (BrowserUse AgentHistory + ActionResult pattern).
 *
 * Failed trajectories are pruned on [onFailure] (natural selection of trajectories):
 * only fully-verified episodes consolidate into the skills directory (YAML files).
 * Pure JVM (zero android.* imports — CI gate).
 */
class AniobHippocampusTracker(
    private val skillsDir: File = File("skill_library/skills"),
    private val assetSkillsDir: File? = null,
    private val procedureStore: AniobLearnedProcedureStore = AniobLearnedProcedureStore()
) {

    data class HippocampusStep(
        val stepIndex: Int,
        val observation: String,
        val thought: String,
        val action: AniobAction,
        val latencyMs: Long,
        val screenHash: String,
        val provider: String,
        val verified: Boolean,
        val textEvidence: String? = null,
        val targetBounds: com.aniob.core.domain.AniobRect? = null
    )

    private val steps = mutableListOf<HippocampusStep>()
    private var currentTaskId: String = ""
    private var currentPrompt: String = ""

    val learnedProcedureStore: AniobLearnedProcedureStore get() = procedureStore

    // Security canary patterns mirroring AniobSkillDistiller — distillation is refused
    // when a candidate slot matches injection-shaped text.
    private val CANARY_INJECTION_PATTERNS = listOf(
        Regex("(?i)ignore\\s+(?:all\\s+)?previous\\s+instructions"),
        Regex("(?i)system\\s+prompt"),
        Regex("(?i)eval\\s*\\("),
        Regex("(?i)<script"),
        Regex("(?i)override\\s+safety"),
        Regex("(?i)act\\s+as\\s+(?:an?\\s+)?admin")
    )

    /** Starts a fresh short-term memory episode for the given task. */
    @Synchronized
    fun beginTask(taskId: String, prompt: String) {
        currentTaskId = taskId
        currentPrompt = prompt
        steps.clear()
    }

    /** Append-only step recording (BrowserUse AgentHistory stream). */
    @Synchronized
    fun recordStep(step: HippocampusStep) {
        steps.add(step)
    }

    @Synchronized
    fun getSteps(): List<HippocampusStep> = ArrayList(steps)

    /** Screen-hash trajectory (stateTrace) captured so far. */
    @Synchronized
    fun currentStateTrace(): List<String> = steps.map { it.screenHash }

    /** Prunes the episode — a failed trajectory does not survive. */
    @Synchronized
    fun onFailure() {
        steps.clear()
    }

    @Synchronized
    fun abort() = onFailure()

    /**
     * Sleep-time consolidation: only a non-empty, fully-verified episode distills into a
     * `learned_*` Skill Format v2.1 YAML and registers a playbook in the long-term store.
     *
     * Returns the emitted skill name, or null when consolidation is refused (empty episode,
     * any unverified step, or canary-injection text in an action slot).
     */
    @Synchronized
    fun consolidateOnSuccess(
        taskId: String,
        prompt: String,
        screenReads: Int,
        actions: Int,
        escalations: Int,
        elapsedMs: Long
    ): String? {
        if (steps.isEmpty() || steps.any { !it.verified }) return null
        if (hasCanaryText()) return null

        val skillName = "learned_" + prompt.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
        val riskTier = deriveRiskTier(steps.map { it.action })
        val yaml = buildSkillYaml(skillName = skillName, prompt = prompt, riskTier = riskTier)

        skillsDir.mkdirs()
        File(skillsDir, "$skillName.yaml").writeText(yaml)
        assetSkillsDir?.let { dir ->
            dir.mkdirs()
            File(dir, "$skillName.yaml").writeText(yaml)
        }

        val signature = prompt.trim().lowercase()
        val packageName = steps.firstNotNullOfOrNull { (it.action as? AniobAction.OpenApp)?.packageName } ?: "unknown_package"
        procedureStore.registerSuccess(
            taskSignature = signature,
            packageName = packageName,
            actions = steps.map { it.action },
            totalLatencyMs = elapsedMs
        )
        return skillName
    }

    private fun hasCanaryText(): Boolean {
        for (step in steps) {
            val candidateText = when (val a = step.action) {
                is AniobAction.InputText -> a.text
                is AniobAction.ConfirmWithUser -> a.message
                else -> step.thought
            }
            for (pattern in CANARY_INJECTION_PATTERNS) {
                if (pattern.containsMatchIn(candidateText)) return true
            }
        }
        return false
    }

    private fun sanitize(s: String): String {
        var out = s
        for (pattern in CANARY_INJECTION_PATTERNS) {
            out = pattern.replace(out, "[sanitized]")
        }
        return out.trim()
    }

    private fun deriveRiskTier(actions: List<AniobAction>): String {
        if (actions.any { it is AniobAction.ConfirmWithUser }) return "HIGH"
        if (actions.any { it is AniobAction.InputText }) return "MEDIUM"
        return "LOW"
    }

    private fun buildSkillYaml(skillName: String, prompt: String, riskTier: String): String {
        val description = sanitize(prompt).ifBlank { "Learned multi-step automation" }
        val trigger = prompt.trim().lowercase()
        return buildString {
            appendLine("name: \"$skillName\"")
            appendLine("version: \"2.1\"")
            appendLine("description: \"$description\"")
            appendLine("risk_tier: \"$riskTier\"")
            appendLine("confirmation_required: ${riskTier == "HIGH"}")
            appendLine("triggers:")
            appendLine("  - \"$trigger\"")
            appendLine("steps:")
            steps.forEach { s ->
                appendLine("  - step: ${s.stepIndex}")
                appendStepFields(this, s.action, s.thought)
            }
        }
    }

    private fun appendStepFields(builder: StringBuilder, action: AniobAction, thought: String) {
        builder.appendLine("    action: \"${action.toolName}\"")
        when (action) {
            is AniobAction.OpenApp -> builder.appendLine("    package: \"${action.packageName}\"")
            is AniobAction.Tap -> appendTarget(builder, action.target)
            is AniobAction.LongPress -> {
                appendTarget(builder, action.target)
                builder.appendLine("    duration_ms: ${action.durationMs}")
            }
            is AniobAction.InputText -> {
                appendTarget(builder, action.target)
                builder.appendLine("    text: \"${sanitizeLine(action.text)}\"")
            }
            is AniobAction.Swipe -> builder.appendLine("    direction: \"${action.direction.name}\"")
            is AniobAction.SystemKey -> builder.appendLine("    key: \"${action.key.name}\"")
            is AniobAction.PressKey -> builder.appendLine("    key: \"${action.key.name}\"")
            is AniobAction.ConfirmWithUser -> builder.appendLine("    text: \"${sanitizeLine(action.message)}\"")
            else -> Unit
        }
        builder.appendLine("    description: \"${sanitizeLine(thought)}\"")
    }

    /** Emits the v2.1 `target:` block. Only semantic kinds are ever written — never coordinates. */
    private fun appendTarget(builder: StringBuilder, target: SemanticTarget) {
        builder.appendLine("    target:")
        when (target) {
            is SemanticTarget.SomIndex -> {
                builder.appendLine("      kind: \"som_index\"")
                builder.appendLine("      value: ${target.index}")
            }
            is SemanticTarget.ResourceId -> {
                builder.appendLine("      kind: \"resource_id\"")
                builder.appendLine("      value: \"${sanitizeLine(target.id)}\"")
            }
            is SemanticTarget.Text -> {
                builder.appendLine("      kind: \"text\"")
                builder.appendLine("      value: \"${sanitizeLine(target.text)}\"")
                builder.appendLine("      exact: ${target.exact}")
            }
            is SemanticTarget.ContentDesc -> {
                builder.appendLine("      kind: \"content_desc\"")
                builder.appendLine("      value: \"${sanitizeLine(target.desc)}\"")
                builder.appendLine("      exact: ${target.exact}")
            }
        }
    }

    private fun sanitizeLine(s: String): String = sanitize(s).replace("\"", "\\\"")
}