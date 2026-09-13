package com.aniob.core.skills

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SwipeDirection

data class SkillStep(
    val stepIndex: Int,
    val actionType: String,
    val selector: String? = null,
    val targetNodeId: Int? = null,
    val text: String? = null,
    val packageName: String? = null,
    val key: String? = null,
    val direction: String? = null,
    val description: String = ""
) {
    fun toAniobAction(): AniobAction {
        return when (actionType.lowercase()) {
            "open_app" -> AniobAction.OpenApp(packageName = packageName ?: "com.android.settings")
            "tap", "click" -> AniobAction.Tap(targetNodeId = targetNodeId ?: 1, thought = description)
            "input_text" -> AniobAction.InputText(targetNodeId = targetNodeId ?: 1, text = text ?: "")
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
            "wait" -> AniobAction.Wait(durationMs = 1000L)
            "confirm_with_user" -> AniobAction.ConfirmWithUser(message = description)
            else -> AniobAction.Finish(summary = description)
        }
    }
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
