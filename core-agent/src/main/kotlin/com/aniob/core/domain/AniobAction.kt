package com.aniob.core.domain

/**
 * Target specification for resilient element targeting.
 */
data class TargetSpec(
    val nodeId: Int? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val bounds: AniobRect? = null,
    val x: Int? = null,
    val y: Int? = null
)

/**
 * Canonical 15-Tool Action definitions for Aniob autonomous agent.
 * Strictly pure Kotlin JVM domain model.
 */
sealed class AniobAction {
    abstract val thought: String
    abstract val toolName: String

    // 1. tap (alias Click)
    data class Tap(
        val targetNodeId: Int? = null,
        val targetSpec: TargetSpec? = null,
        val x: Int = 0,
        val y: Int = 0,
        override val thought: String = "Tapping target element"
    ) : AniobAction() {
        override val toolName: String = "tap"
    }

    // Backwards compatibility alias for Click
    data class Click(
        val targetNodeId: Int,
        val x: Int = 0,
        val y: Int = 0,
        override val thought: String = "Clicking element $targetNodeId"
    ) : AniobAction() {
        override val toolName: String = "tap"
    }

    // 2. long_press
    data class LongPress(
        val targetNodeId: Int? = null,
        val targetSpec: TargetSpec? = null,
        val x: Int = 0,
        val y: Int = 0,
        val durationMs: Long = 1000L,
        override val thought: String = "Long pressing target element"
    ) : AniobAction() {
        override val toolName: String = "long_press"
    }

    // 3. swipe
    data class Swipe(
        val direction: SwipeDirection,
        val distancePx: Int = 600,
        val startX: Int = 0,
        val startY: Int = 0,
        override val thought: String = "Swiping $direction"
    ) : AniobAction() {
        override val toolName: String = "swipe"
    }

    // 4. input_text
    data class InputText(
        val targetNodeId: Int,
        val text: String,
        val clearFirst: Boolean = false,
        override val thought: String = "Typing text into element $targetNodeId"
    ) : AniobAction() {
        override val toolName: String = "input_text"
    }

    // 5. open_app
    data class OpenApp(
        val packageName: String,
        val activityName: String? = null,
        override val thought: String = "Opening application: $packageName"
    ) : AniobAction() {
        override val toolName: String = "open_app"
    }

    // 6. get_screen_info
    data class GetScreenInfo(
        override val thought: String = "Capturing screen node hierarchy and metadata"
    ) : AniobAction() {
        override val toolName: String = "get_screen_info"
    }

    // 7. take_screenshot
    data class TakeScreenshot(
        override val thought: String = "Capturing visual screenshot for multi-modal analysis"
    ) : AniobAction() {
        override val toolName: String = "take_screenshot"
    }

    // 8. get_device_info
    data class GetDeviceInfo(
        override val thought: String = "Querying device state, battery, and network status"
    ) : AniobAction() {
        override val toolName: String = "get_device_info"
    }

    // 9. get_notifications
    data class GetNotifications(
        val filterPackage: String? = null,
        override val thought: String = "Reading active notification stream"
    ) : AniobAction() {
        override val toolName: String = "get_notifications"
    }

    // 10. get_installed_apps
    data class GetInstalledApps(
        override val thought: String = "Listing launchable user applications"
    ) : AniobAction() {
        override val toolName: String = "get_installed_apps"
    }

    // 11. clipboard
    data class Clipboard(
        val operation: ClipboardOp = ClipboardOp.GET,
        val text: String? = null,
        override val thought: String = "Operating clipboard: $operation"
    ) : AniobAction() {
        override val toolName: String = "clipboard"
    }

    // 12. system_key (alias PressKey)
    data class SystemKey(
        val key: KeyType,
        override val thought: String = "Triggering system key $key"
    ) : AniobAction() {
        override val toolName: String = "system_key"
    }

    data class PressKey(
        val key: KeyType,
        override val thought: String = "Pressing $key key"
    ) : AniobAction() {
        override val toolName: String = "system_key"
    }

    // 13. wait
    data class Wait(
        val durationMs: Long = 1000L,
        override val thought: String = "Waiting ${durationMs}ms for UI to settle"
    ) : AniobAction() {
        override val toolName: String = "wait"
    }

    // 14. confirm_with_user
    data class ConfirmWithUser(
        val message: String,
        val riskLevel: String = "HIGH",
        override val thought: String = "Requesting explicit user confirmation: $message"
    ) : AniobAction() {
        override val toolName: String = "confirm_with_user"
    }

    // 15. finish
    data class Finish(
        val summary: String = "Task completed successfully",
        override val thought: String = "Goal accomplished"
    ) : AniobAction() {
        override val toolName: String = "finish"
    }

    // Execution failure state
    data class Fail(
        val reason: String,
        override val thought: String = "Execution failed: $reason"
    ) : AniobAction() {
        override val toolName: String = "fail"
    }
}

enum class SwipeDirection {
    UP, DOWN, LEFT, RIGHT
}

enum class KeyType {
    BACK, HOME, ENTER, RECENTS, VOLUME_UP, VOLUME_DOWN
}

enum class ClipboardOp {
    GET, SET
}
