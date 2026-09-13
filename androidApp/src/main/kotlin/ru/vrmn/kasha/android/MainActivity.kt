package ru.vrmn.kasha.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import brain.studio.KashaSplash
import brain.studio.StudioTheme

/**
 * Тонкая Android entry point. Продуктовый UI остаётся в общем composeApp.
 * До подключения Android adapters показывается общий Kasha splash, без отдельного Android UI.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudioTheme("system") {
                KashaSplash()
            }
        }
    }
}
