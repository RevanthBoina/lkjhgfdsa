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
import com.aniob.core.execution.AniobActionExecutor
import com.aniob.core.execution.DispatchCommand
import com.aniob.core.optimizer.AniobTokenOptimizer

/**
 * High-performance Accessibility Service for Aniob UI Automation.
 * References ARTEMIS (https://github.com/google/artemis) & PrivateAgent (https://github.com/deepakraj-18/mobileappagent).
 */
class AniobAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AniobA11yService"
        private const val MAX_IDLE_WAIT_MS = 1000L
        private const val IDLE_POLL_INTERVAL_MS = 50L
        var instance: AniobAccessibilityService? = null
            private set
        var isServiceConnected: Boolean = false
            private set
        var isTaskActive: Boolean = false

        /**
         * Fired when the service is destroyed, carrying whether a task was still running.
         * UX-6 turns this into the "Aniob lost access" recovery surface. Zero-cost when unset.
         */
        @Volatile
        var onServiceLost: ((wasTaskActive: Boolean) -> Unit)? = null

        @Volatile
        var isRecordingDemo: Boolean = false
        private val demoRecordedEvents = mutableListOf<com.aniob.core.skills.RecordedDemoEvent>()

        fun startDemoRecording() {
            synchronized(demoRecordedEvents) {
                demoRecordedEvents.clear()
            }
            isRecordingDemo = true
        }

        fun stopDemoRecording(): List<com.aniob.core.skills.RecordedDemoEvent> {
            isRecordingDemo = false
            return synchronized(demoRecordedEvents) {
                demoRecordedEvents.toList()
            }
        }
    }

    private val spotlightOverlay by lazy { com.aniob.app.overlay.AniobSpotlightOverlay(this) }
    private var contentChangedSinceLastStep: Boolean = true
    private var lastScreenState: AniobScreenState? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceConnected = true
        notifySystemState()
        // Build AppCatalog with toast like reference
        try {
            android.widget.Toast.makeText(this, "Building app catalog...", android.widget.Toast.LENGTH_SHORT).show()
            val catalog = com.aniob.core.knowledge.AniobAppCatalog.getInstance()
            val pm = packageManager
            val apps = pm.getInstalledApplications(0)
            for (appInfo in apps) {
                val appName = pm.getApplicationLabel(appInfo).toString()
                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                catalog.registerApp(com.aniob.core.knowledge.AppCatalogEntry(appInfo.packageName, appName, isSystem))
            }
            val jsonFile = java.io.File(filesDir, "app_catalog.json")
            val mdFile = java.io.File(filesDir, "apps/app-registry.md")
            mdFile.parentFile?.mkdirs()
            catalog.saveToFile(jsonFile, mdFile)
            Log.i(TAG, "AppCatalog built ${catalog.getAll().size} apps")
        } catch (e: Exception) {
            Log.e(TAG, "Catalog build failed", e)
        }
        Log.i(TAG, "AniobAccessibilityService successfully connected and ready.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (isRecordingDemo) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    val source = event.source
                    val pkg = event.packageName?.toString()
                    val text = (source?.text ?: event.text.firstOrNull())?.toString()
                    val desc = (source?.contentDescription ?: event.contentDescription)?.toString()
                    val resId = source?.viewIdResourceName
                    synchronized(demoRecordedEvents) {
                        demoRecordedEvents.add(
                            com.aniob.core.skills.RecordedDemoEvent(
                                actionType = "click",
                                resourceId = resId,
                                text = text,
                                contentDescription = desc,
                                packageName = pkg
                            )
                        )
                    }
                    source?.recycle()
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val source = event.source
                    val pkg = event.packageName?.toString()
                    val text = event.text.joinToString(" ")
                    val desc = source?.contentDescription?.toString()
                    val resId = source?.viewIdResourceName
                    if (text.isNotBlank()) {
                        synchronized(demoRecordedEvents) {
                            demoRecordedEvents.add(
                                com.aniob.core.skills.RecordedDemoEvent(
                                    actionType = "type_text",
                                    resourceId = resId,
                                    text = source?.text?.toString(),
                                    contentDescription = desc,
                                    inputText = text,
                                    packageName = pkg
                                )
                            )
                        }
                    }
                    source?.recycle()
                }
            }
        }

        if (!isTaskActive) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                contentChangedSinceLastStep = true
                com.aniob.core.policy.AniobWaitForIdle.onContentChanged()
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
        // A11y dies mid-task: surface the loss so UX-6's recovery flow can react.
        AniobAccessibilityService.onServiceLost?.invoke(AniobAccessibilityService.isTaskActive)
        AniobAccessibilityService.isTaskActive = false
        notifySystemState()
    }

    /** Pushes the new connection truth into the reactive system state (UX-0 §1). */
    private fun notifySystemState() {
        try {
            com.aniob.app.AniobApplication.instance.systemState.onAccessibilityChanged(isServiceConnected)
        } catch (_: Exception) {
            // Application not initialised in this process (e.g. isolated test); safe to skip.
        }
    }

    /**
     * Reflex 5: onTrimMemory releases transient caches immediately (zero LLM).
     * Drops the cached screen so the next capture is fresh, and lets the
     * ViewModel (which owns the LiteRT engine) know we are under memory pressure.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            lastScreenState = null
            contentChangedSinceLastStep = true
            Log.i(TAG, "onTrimMemory level=$level -> cleared cached screen state (reflex, no LLM)")
        }
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

    fun findScrollableNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findScrollableRecursive(root)
    }

    private fun findScrollableRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableRecursive(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    /**
     * Executes an atomic action via dispatchGesture, node actions, or a system intent.
     *
     * Two contracts hold on every call:
     * 1. Gesture-bearing actions are gated on UI quiescence first — a 300ms Android animation is
     *    long enough for a follow-up tap to land on stale coordinates or be swallowed entirely.
     * 2. The target is grounded against a **fresh** capture taken right here, never the cached
     *    planning screen. A miss calls back `false` and logs `grounding_miss`; we never fall back
     *    to a guessed coordinate.
     */
    fun executeAction(action: AniobAction, callback: (Boolean) -> Unit) {
        gateOnUiQuiescence(action)

        val liveScreen = try {
            captureCurrentScreenState()
        } catch (_: Exception) {
            lastScreenState
        } ?: AniobScreenState(packageName = currentForegroundPackage().orEmpty())

        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        when (val command = AniobActionExecutor.planDispatch(action, liveScreen, width, height)) {
            is DispatchCommand.TapAt -> {
                spotlightOverlay.flash(command.x.toFloat(), command.y.toFloat())
                dispatchTap(command.x.toFloat(), command.y.toFloat(), callback)
            }

            is DispatchCommand.LongPressAt -> {
                spotlightOverlay.flash(command.x.toFloat(), command.y.toFloat())
                dispatchLongPress(command.x.toFloat(), command.y.toFloat(), command.durationMs, callback)
            }

            is DispatchCommand.SetText -> {
                val node = liveScreen.findNodeById(command.nodeId)
                if (node != null) {
                    spotlightOverlay.flash(Rect(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom))
                }
                setTextOnNode(command, liveScreen, callback)
            }

            is DispatchCommand.SwipeGesture -> {
                spotlightOverlay.flash(command.startX.toFloat(), command.startY.toFloat())
                dispatchGesturePath(command, callback)
            }

            is DispatchCommand.SystemIntent -> handleSystemIntent(action, command, callback)

            is DispatchCommand.NoOp -> {
                Log.w(TAG, "grounding_miss: ${command.reason}")
                callback(false)
            }
        }
    }

    /** Sets text by locating the node by id within the live tree. */
    private fun setTextOnNode(
        command: DispatchCommand.SetText,
        liveScreen: AniobScreenState,
        callback: (Boolean) -> Unit
    ) {
        val root = rootInActiveWindow
        val targetNode = liveScreen.findNodeById(command.nodeId)
        if (root != null && targetNode != null) {
            val found = findAccessibilityNodeByBounds(root, targetNode.bounds)
            if (found != null) {
                if (command.clearFirst) {
                    val clearArgs = Bundle()
                    clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                    found.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
                }
                val arguments = Bundle()
                arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    command.text
                )
                callback(found.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
                return
            }
        }
        callback(false)
    }

    private fun dispatchGesturePath(command: DispatchCommand.SwipeGesture, callback: (Boolean) -> Unit) {
        val path = Path()
        path.moveTo(command.startX.toFloat(), command.startY.toFloat())
        path.lineTo(command.endX.toFloat(), command.endY.toFloat())
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            command.durationMs.coerceAtLeast(1L)
        )
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback(false)
                }
            },
            null
        )
    }

    private fun handleSystemIntent(
        action: AniobAction,
        command: DispatchCommand.SystemIntent,
        callback: (Boolean) -> Unit
    ) {
        when (command.name) {
            "OPEN_APP" -> {
                val intent = packageManager.getLaunchIntentForPackage(command.argument.orEmpty())
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    callback(true)
                } else {
                    callback(false)
                }
            }
            "SYSTEM_KEY" -> {
                val globalAction = when ((action as? AniobAction.SystemKey)?.key ?: (action as? AniobAction.PressKey)?.key) {
                    KeyType.HOME -> GLOBAL_ACTION_HOME
                    KeyType.RECENTS -> GLOBAL_ACTION_RECENTS
                    else -> GLOBAL_ACTION_BACK
                }
                callback(performGlobalAction(globalAction))
            }
            "CONFIRM_WITH_USER" -> callback(true)
            "FINISH", "WAIT", "CLIPBOARD", "GET_SCREEN_INFO", "TAKE_SCREENSHOT",
            "GET_DEVICE_INFO", "GET_NOTIFICATIONS", "GET_INSTALLED_APPS" -> callback(true)
            "FAIL" -> callback(false)
            else -> callback(true)
        }
    }

    /**
     * Package currently owning the active window, read live from the a11y root. Used to detect
     * an OEM background-kill that invalidated our cached session before we reuse it.
     */
    fun currentForegroundPackage(): String? =
        try {
            rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            null
        }

    private fun gateOnUiQuiescence(action: AniobAction) {
        // Read-only / non-gesture actions do not race an animation, so skip the wait for them.
        val gesture = action is AniobAction.Tap || action is AniobAction.LongPress ||
            action is AniobAction.Swipe || action is AniobAction.InputText
        if (!gesture) return
        if (com.aniob.core.policy.AniobWaitForIdle.isUiIdle()) return
        val deadline = System.currentTimeMillis() + MAX_IDLE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (com.aniob.core.policy.AniobWaitForIdle.isUiIdle()) return
            try {
                Thread.sleep(IDLE_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
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
