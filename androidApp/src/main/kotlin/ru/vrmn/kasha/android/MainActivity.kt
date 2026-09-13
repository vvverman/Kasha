package ru.vrmn.kasha.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import brain.studio.StudioApp

/** Тонкая Android entry point: весь продуктовый state/UI берётся из shared Kasha UI. */
class MainActivity : ComponentActivity() {
    private lateinit var platform: AndroidPlatformRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = (application as KashaApplication).platform
        platform.permissions.bind(this)
        enableEdgeToEdge()
        setContent { StudioApp(platform.state) }
    }

    override fun onDestroy() {
        if (::platform.isInitialized) platform.permissions.unbind(this)
        super.onDestroy()
    }
}
