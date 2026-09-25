package com.aniob.core.execution

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.ClipboardOp
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobActionExecutorTest {

    private val sampleScreen = AniobScreenState(
        packageName = "com.example.app",
        activityName = "MainActivity",
        nodes = listOf(
            AniobNode(
                id = 1,
                className = "android.widget.Button",
                text = "Submit",
                viewId = "com.example.app:id/submit_btn",
                bounds = AniobRect(100, 200, 300, 260),
                isClickable = true
            ),
            AniobNode(
                id = 2,
                className = "android.widget.EditText",
                text = "Search here",
                viewId = "com.example.app:id/search_box",
                bounds = AniobRect(50, 50, 450, 100),
                isEditable = true
            )
        )
    )

    @Test
    fun `planDispatch grounds Tap to target coordinates`() {
        val action = AniobAction.Tap(SemanticTarget.Text("Submit"))
        val command = AniobActionExecutor.planDispatch(action, sampleScreen)

        assertTrue(command is DispatchCommand.TapAt)
        val tap = command as DispatchCommand.TapAt
        assertEquals(200, tap.x) // (100 + 300) / 2
        assertEquals(230, tap.y) // (200 + 260) / 2
    }

    @Test
    fun `planDispatch on missing target yields NoOp with grounding miss reason`() {
        val action = AniobAction.Tap(SemanticTarget.Text("NonExistentButton"))
        val command = AniobActionExecutor.planDispatch(action, sampleScreen)

        assertTrue("Missing target must result in NoOp", command is DispatchCommand.NoOp)
        val noop = command as DispatchCommand.NoOp
        assertTrue(noop.reason.startsWith("grounding_miss:"))
    }

    @Test
    fun `planDispatch translates utility and system actions to typed SystemIntent`() {
        val openApp = AniobActionExecutor.planDispatch(
            AniobAction.OpenApp("com.android.settings"),
            sampleScreen
        )
        assertTrue(openApp is DispatchCommand.SystemIntent)
        assertEquals("OPEN_APP", (openApp as DispatchCommand.SystemIntent).name)
        assertEquals("com.android.settings", openApp.argument)

        val clipboard = AniobActionExecutor.planDispatch(
            AniobAction.Clipboard(ClipboardOp.SET),
            sampleScreen
        )
        assertTrue(clipboard is DispatchCommand.SystemIntent)
        assertEquals("CLIPBOARD", (clipboard as DispatchCommand.SystemIntent).name)

        val screenInfo = AniobActionExecutor.planDispatch(
            AniobAction.GetScreenInfo(),
            sampleScreen
        )
        assertTrue(screenInfo is DispatchCommand.SystemIntent)
        assertEquals("GET_SCREEN_INFO", (screenInfo as DispatchCommand.SystemIntent).name)

        val notifs = AniobActionExecutor.planDispatch(
            AniobAction.GetNotifications(filterPackage = "com.chat"),
            sampleScreen
        )
        assertTrue(notifs is DispatchCommand.SystemIntent)
        assertEquals("GET_NOTIFICATIONS", (notifs as DispatchCommand.SystemIntent).name)

        val finish = AniobActionExecutor.planDispatch(
            AniobAction.Finish("Done"),
            sampleScreen
        )
        assertTrue(finish is DispatchCommand.SystemIntent)
        assertEquals("FINISH", (finish as DispatchCommand.SystemIntent).name)

        val fail = AniobActionExecutor.planDispatch(
            AniobAction.Fail("Failed to locate"),
            sampleScreen
        )
        assertTrue(fail is DispatchCommand.SystemIntent)
        assertEquals("FAIL", (fail as DispatchCommand.SystemIntent).name)
    }

    @Test
    fun `planDispatch calculates swipe gesture within container or screen bounds`() {
        val swipe = AniobActionExecutor.planDispatch(
            AniobAction.Swipe(direction = SwipeDirection.UP, distancePx = 300),
            sampleScreen,
            screenWidth = 1080,
            screenHeight = 2400
        )
        assertTrue(swipe is DispatchCommand.SwipeGesture)
        val sg = swipe as DispatchCommand.SwipeGesture
        assertEquals(540, sg.startX)
        assertEquals(1200, sg.startY)
        assertEquals(540, sg.endX)
        assertEquals(900, sg.endY)
    }
}
