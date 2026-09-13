package com.aniob.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aniob.core.diff.AniobScreenDiff
import com.aniob.core.domain.*
import com.aniob.core.optimizer.AniobTokenOptimizer

/**
 * High-performance Accessibility Service for Aniob UI Automation.
 * References ARTEMIS (https://github.com/google/artemis) & PrivateAgent (https://github.com/deepakraj-18/mobileappagent).
 */
class AniobAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AniobA11yService"
        var instance: AniobAccessibilityService? = null
            private set
        var isServiceConnected: Boolean = false
            private set
        var isTaskActive: Boolean = false
    }

    private var contentChangedSinceLastStep: Boolean = true
    private var lastScreenState: AniobScreenState? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceConnected = true
        Log.i(TAG, "AniobAccessibilityService successfully connected and ready.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isTaskActive || event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                contentChangedSinceLastStep = true
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AniobAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isServiceConnected = false
    }

    /**
     * Extracts optimized screen state using TokenOptimizer and ScreenDiff waterfall.
     */
    fun captureCurrentScreenState(): AniobScreenState {
        val root = rootInActiveWindow
        val rawNodes = mutableListOf<AniobNode>()

        if (root != null) {
            traverseNode(root, rawNodes)
        }

        // Apply TokenOptimizer rule: Max 60 actionable nodes with Set-of-Mark tags
        val optimizedNodes = AniobTokenOptimizer.optimize(rawNodes)
        val packageName = root?.packageName?.toString().orEmpty()

        val waterfall = AniobScreenDiff.evaluateWaterfall(
            hasAccessibilityContentChanged = contentChangedSinceLastStep,
            currentNodes = optimizedNodes,
            previousScreen = lastScreenState
        )

        contentChangedSinceLastStep = false

        val screenState = AniobScreenState(
            packageName = packageName,
            activityName = "",
            nodes = optimizedNodes,
            treeHash = waterfall.treeHash,
            screenshotBase64 = null,
            changedRegion = waterfall.changedRegion,
            isEventDrivenSkip = waterfall.waterfallLevelUsed == 1
        )
        lastScreenState = screenState
        return screenState
    }

    private fun traverseNode(node: AccessibilityNodeInfo, list: MutableList<AniobNode>) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        val aniobNode = AniobNode(
            id = list.size + 1,
            className = node.className?.toString().orEmpty(),
            text = node.text?.toString().orEmpty(),
            contentDescription = node.contentDescription?.toString().orEmpty(),
            viewId = node.viewIdResourceName.orEmpty(),
            bounds = AniobRect(rect.left, rect.top, rect.right, rect.bottom),
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isSelected = node.isSelected,
            isEnabled = node.isEnabled,
            isVisibleToUser = node.isVisibleToUser
        )
        list.add(aniobNode)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, list)
            child.recycle()
        }
    }

    /**
     * Executes atomic action via dispatchGesture or direct a11y node actions.
     */
    fun executeAction(action: AniobAction, callback: (Boolean) -> Unit) {
        when (action) {
            is AniobAction.Tap -> {
                val root = rootInActiveWindow
                val screenState = lastScreenState
                val targetNode = action.targetNodeId?.let { screenState?.findNodeById(it) }

                val tapX = if (action.x > 0) action.x.toFloat() else targetNode?.centerX?.toFloat() ?: 500f
                val tapY = if (action.y > 0) action.y.toFloat() else targetNode?.centerY?.toFloat() ?: 500f

                dispatchTap(tapX, tapY, callback)
            }

            is AniobAction.Click -> {
                val root = rootInActiveWindow
                val screenState = lastScreenState
                val targetNode = screenState?.findNodeById(action.targetNodeId)

                val tapX = if (action.x > 0) action.x.toFloat() else targetNode?.centerX?.toFloat() ?: 500f
                val tapY = if (action.y > 0) action.y.toFloat() else targetNode?.centerY?.toFloat() ?: 500f

                dispatchTap(tapX, tapY, callback)
            }

            is AniobAction.LongPress -> {
                val root = rootInActiveWindow
                val screenState = lastScreenState
                val targetNode = action.targetNodeId?.let { screenState?.findNodeById(it) }

                val tapX = if (action.x > 0) action.x.toFloat() else targetNode?.centerX?.toFloat() ?: 500f
                val tapY = if (action.y > 0) action.y.toFloat() else targetNode?.centerY?.toFloat() ?: 500f

                dispatchLongPress(tapX, tapY, action.durationMs, callback)
            }

            is AniobAction.InputText -> {
                val root = rootInActiveWindow
                val screenState = lastScreenState
                val targetNode = screenState?.findNodeById(action.targetNodeId)
                // Focus and set text
                if (root != null && targetNode != null) {
                    val found = findAccessibilityNodeByBounds(root, targetNode.bounds)
                    if (found != null) {
                        val arguments = Bundle()
                        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                        val success = found.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                        callback(success)
                        return
                    }
                }
                callback(true)
            }

            is AniobAction.OpenApp -> {
                val intent = packageManager.getLaunchIntentForPackage(action.packageName)
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    callback(true)
                } else {
                    callback(false)
                }
            }

            is AniobAction.Swipe -> {
                dispatchSwipe(action.direction, action.distancePx, callback)
            }

            is AniobAction.SystemKey -> {
                val globalAction = when (action.key) {
                    KeyType.BACK -> GLOBAL_ACTION_BACK
                    KeyType.HOME -> GLOBAL_ACTION_HOME
                    KeyType.RECENTS -> GLOBAL_ACTION_RECENTS
                    else -> GLOBAL_ACTION_BACK
                }
                val success = performGlobalAction(globalAction)
                callback(success)
            }

            is AniobAction.PressKey -> {
                val globalAction = when (action.key) {
                    KeyType.BACK -> GLOBAL_ACTION_BACK
                    KeyType.HOME -> GLOBAL_ACTION_HOME
                    KeyType.RECENTS -> GLOBAL_ACTION_RECENTS
                    else -> GLOBAL_ACTION_BACK
                }
                val success = performGlobalAction(globalAction)
                callback(success)
            }

            is AniobAction.Wait -> {
                callback(true)
            }

            is AniobAction.ConfirmWithUser -> {
                callback(true)
            }

            is AniobAction.GetScreenInfo,
            is AniobAction.TakeScreenshot,
            is AniobAction.GetDeviceInfo,
            is AniobAction.GetNotifications,
            is AniobAction.GetInstalledApps,
            is AniobAction.Clipboard -> {
                callback(true)
            }

            is AniobAction.Finish -> {
                callback(true)
            }

            is AniobAction.Fail -> {
                callback(false)
            }
        }
    }

    private fun dispatchLongPress(x: Float, y: Float, durationMs: Long, callback: (Boolean) -> Unit) {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(400L))
        val builder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback(false)
            }
        }, null)
    }

    private fun dispatchTap(x: Float, y: Float, callback: (Boolean) -> Unit) {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val builder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback(false)
            }
        }, null)
    }

    private fun dispatchSwipe(direction: SwipeDirection, distance: Int, callback: (Boolean) -> Unit) {
        val displayMetrics = resources.displayMetrics
        val startX = displayMetrics.widthPixels / 2f
        val startY = displayMetrics.heightPixels / 2f

        val endX = when (direction) {
            SwipeDirection.LEFT -> startX - distance
            SwipeDirection.RIGHT -> startX + distance
            else -> startX
        }
        val endY = when (direction) {
            SwipeDirection.UP -> startY - distance
            SwipeDirection.DOWN -> startY + distance
            else -> startY
        }

        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val builder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback(false)
            }
        }, null)
    }

    private fun findAccessibilityNodeByBounds(root: AccessibilityNodeInfo, targetRect: AniobRect): AccessibilityNodeInfo? {
        val rect = Rect()
        root.getBoundsInScreen(rect)
        if (rect.left == targetRect.left && rect.top == targetRect.top && rect.right == targetRect.right && rect.bottom == targetRect.bottom) {
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findAccessibilityNodeByBounds(child, targetRect)
            if (found != null) return found
        }
        return null
    }
}
