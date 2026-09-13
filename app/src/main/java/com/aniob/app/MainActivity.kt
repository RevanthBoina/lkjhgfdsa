package com.aniob.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.aniob.app.ui.AniobMainScreen
import com.aniob.app.ui.AniobViewModel
import com.aniob.app.ui.theme.AniobTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AniobViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AniobTheme {
                AniobMainScreen(viewModel = viewModel)
            }
        }
    }
}
