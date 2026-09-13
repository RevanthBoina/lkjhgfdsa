package com.aniob.app

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
        enableEdgeToEdge()
        setContent {
            AniobTheme {
                AniobMainScreen(viewModel = viewModel)
            }
        }
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
