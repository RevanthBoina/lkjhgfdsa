package com.aniob.core.skills

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SwipeDirection

data class SkillStep(
    val stepIndex: Int,
    val actionType: String,
    val selector: String? = null,
    /** Legacy pre-v2.1 node id. Superseded by [target]; kept for replaying old skills. */
    val targetNodeId: Int? = null,
    /** v2.1 semantic target block. Preferred over [targetNodeId] when present. */
    val target: SemanticTarget? = null,
    val text: String? = null,
    val packageName: String? = null,
    val key: String? = null,
    val direction: String? = null,
    val description: String = ""
) {
    fun toAniobAction(): AniobAction {
        return when (actionType.lowercase()) {
            "open_app" -> AniobAction.OpenApp(packageName = packageName ?: "com.android.settings")
            "tap", "click" -> AniobAction.Tap(target = resolveTarget(), thought = description)
            "input_text" -> AniobAction.InputText(target = resolveTarget(), text = text ?: "")
            "long_press" -> AniobAction.LongPress(target = resolveTarget(), thought = description)
            "swipe" -> {
                val dir = when (direction?.uppercase()) {
                    "UP" -> SwipeDirection.UP
                    "DOWN" -> SwipeDirection.DOWN
                    "LEFT" -> SwipeDirection.LEFT
                    else -> SwipeDirection.RIGHT
                }
                AniobAction.Swipe(direction = dir)
            }
            "system_key" -> {
                val k = when (key?.uppercase()) {
                    "BACK" -> KeyType.BACK
                    "HOME" -> KeyType.HOME
                    else -> KeyType.ENTER
                }
                AniobAction.SystemKey(key = k)
            }
            "wait" -> AniobAction.Wait()
            "confirm_with_user" -> AniobAction.ConfirmWithUser(message = description)
            else -> AniobAction.Finish(summary = description)
        }
    }

    /**
     * Resolves the semantic target, falling back to the legacy node id. Legacy skills keep
     * working; the deprecation is logged by the loader so authors migrate to `target:`.
     */
    private fun resolveTarget(): SemanticTarget = target
        ?: targetNodeId?.let {
            System.err.println(
                "[AniobSkill] DEPRECATED: step $stepIndex uses legacy 'target_id'. " +
                    "Migrate to a semantic 'target:' block (resource_id/text/content_desc)."
            )
            SemanticTarget.SomIndex(it)
        }
        ?: SemanticTarget.SomIndex(1)
}

data class AniobSkill(
    val name: String,
    val version: String = "2.1",
    val description: String,
    val triggerKeywords: List<String> = emptyList(),
    val intentPattern: String = "",
    val riskTier: String = "LOW", // LOW, MEDIUM, HIGH
    val confirmationRequired: Boolean = false,
    val targetPackage: String? = null,
    val slots: Map<String, String> = emptyMap(),
    val steps: List<SkillStep> = emptyList(),
    val isDraft: Boolean = false
)
