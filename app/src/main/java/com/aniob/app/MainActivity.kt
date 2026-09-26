package com.aniob.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.aniob.app.background.AniobBackgroundController
import com.aniob.app.ui.AniobMainScreen
import com.aniob.app.ui.AniobViewModel
import com.aniob.app.ui.theme.AniobTheme
import com.aniob.core.providers.AniobLocalLlmClient

class MainActivity : ComponentActivity() {

    private val viewModel: AniobViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !viewModel.isReadyToRender.value }

        val app = application as AniobApplication
        AniobBackgroundController.registerActivity(this)
        app.workflowActionHost.attach(this)

        // UX-3: wire Stop/Pause notification actions.
        com.aniob.app.background.AniobForegroundService.onStopRequestedFromNotification = {
            viewModel.stopCurrentTask()
        }
        com.aniob.app.background.AniobForegroundService.onPauseToggleFromNotification = {
            viewModel.togglePause()
        }
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            AniobTheme {
                AniobMainScreen(viewModel = viewModel)
            }
        }
    }

    /**
     * Catches result/pill deep links while the activity is alive.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val app = application as AniobApplication
        val sharedContent = app.incomingContentReader.parse(intent)
        if (sharedContent != null) {
            viewModel.handleIncomingShare(sharedContent)
        } else {
            viewModel.handleDeepLink(intent.dataString)
        }
    }

    /** Every return from Settings re-reads permission state so banners never go stale (U2). */
    override fun onResume() {
        super.onResume()
        (application as AniobApplication).systemState.refresh()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Memory safety: release local SLM native memory when device is under memory pressure
        AniobLocalLlmClient.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as AniobApplication).workflowActionHost.detach()
        AniobBackgroundController.unregisterActivity(this)
    }
}
