package com.aniob.app.workflow

import androidx.test.core.app.ApplicationProvider
import com.aniob.app.AniobApplication
import com.aniob.core.workflow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AniobApplication::class, sdk = [33])
class WorkflowCoordinatorTest {

    private lateinit var app: AniobApplication
    private lateinit var coordinator: WorkflowCoordinator

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        coordinator = app.workflowCoordinator
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        app.workflowActionHost.attach(activity)

        val resolveInfo = android.content.pm.ResolveInfo().apply {
            serviceInfo = android.content.pm.ServiceInfo().apply {
                packageName = "com.android.chrome"
            }
        }
        val customTabsIntent = android.content.Intent(androidx.browser.customtabs.CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION)
        org.robolectric.Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(customTabsIntent, resolveInfo)
    }

    @Test
    fun testWebsiteActionTransitionsToWaitingForUser() = runTest {
        val destination = Destination(
            handler = "lovable.dev",
            url = "https://lovable.dev/",
            category = DestinationCategory.BUILDER_SERVICE,
            isApproved = true
        )
        val action = WorkflowAction.OpenWebsite(
            actionId = "test_action_1",
            destination = destination,
            briefText = "Create a portfolio website"
        )

        val result = coordinator.executeAction(
            action = action,
            taskId = "test_task_1",
            generation = 1L
        )

        // Web handoff must produce WaitingForUser result
        assertTrue("Expected WaitingForUser but got $result", result is WorkflowResult.WaitingForUser)

        val persisted = app.workflowRepository.getWorkflow("test_task_1")
        assertNotNull(persisted)
        assertEquals("WaitingForUser", persisted?.state)
        assertEquals("https://lovable.dev/", persisted?.destinationUrl)
    }

    @Test
    fun testFailedCapabilityCheckStopsPreDispatch() = runTest {
        val destination = Destination(
            handler = "non.existent.package.app",
            category = DestinationCategory.APP
        )
        val action = WorkflowAction.OpenApp(
            actionId = "test_action_2",
            destination = destination
        )

        val result = coordinator.executeAction(
            action = action,
            taskId = "test_task_2",
            generation = 1L
        )

        assertTrue("Unavailable handler must fail pre-dispatch", result is WorkflowResult.Failed)
        val persisted = app.workflowRepository.getWorkflow("test_task_2")
        assertEquals("Failed", persisted?.state)
    }
}
