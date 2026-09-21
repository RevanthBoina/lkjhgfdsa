package com.aniob.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.aniob.app.background.AniobBackgroundController
import com.aniob.app.ui.AniobMainScreen
import com.aniob.app.ui.AniobViewModel
import com.aniob.app.ui.theme.AniobTheme
import com.aniob.core.providers.AniobLocalLlmClient

class MainActivity : ComponentActivity() {

    private val viewModel: AniobViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AniobBackgroundController.registerActivity(this)
        // UX-4: wire notification Approve/Deny actions into the suspending confirm gate.
        com.aniob.app.background.AniobForegroundService.onConfirmDecision = { decision ->
            viewModel.resolveConfirmation(decision)
        }
        // UX-3: wire Stop/Pause notification actions.
        com.aniob.app.background.AniobForegroundService.onStopRequestedFromNotification = {
            viewModel.stopCurrentTask()
        }
        com.aniob.app.background.AniobForegroundService.onPauseToggleFromNotification = {
            viewModel.togglePause()
        }
        enableEdgeToEdge()

        // Deep links (UX-0 §2): aniob://chat|tracker|result|skills|confirm.
        viewModel.handleDeepLink(intent?.dataString)

        setContent {
            AniobTheme {
                AniobMainScreen(viewModel = viewModel)
            }
        }
    }

    /**
     * Catches result/pill deep links while the activity is alive. Before UX-0 the result extra
     * was passed but no `onNewIntent` existed, so the user saw nothing (U3).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleDeepLink(intent.dataString)
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
        AniobBackgroundController.unregisterActivity(this)
    }
}
