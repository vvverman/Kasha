package ru.vrmn.kasha.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import brain.studio.StudioApp

/** Тонкая Android entry point: весь продуктовый state/UI берётся из shared Kasha UI. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = (application as KashaApplication).platform.state
        setContent { StudioApp(state) }
    }
}
