package ru.vrmn.kasha.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import brain.studio.StudioApp
import brain.studio.StudioState
import kotlinx.coroutines.*
import java.util.Locale

/** Composition root Android: только создание platform adapters вокруг общего Core/UI. */
class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var rendered = false
    private var recorder: AndroidRecorder? = null
    private var audio: AndroidAudio? = null

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        renderKasha()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 201)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            renderKasha()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun renderKasha() {
        if (rendered || isFinishing || isDestroyed) return
        rendered = true
        val intelligence = AndroidIntelligence(applicationContext)
        val repository = AndroidRepository(applicationContext, intelligence)
        val platformRecorder = AndroidRecorder(applicationContext, repository, scope)
        val platformAudio = AndroidAudio(repository, scope)
        val reminder = AndroidReminder(applicationContext)
        recorder = platformRecorder
        audio = platformAudio

        setContent {
            val state = remember(repository, platformRecorder, platformAudio) {
                StudioState(
                    repository = repository,
                    recorder = platformRecorder,
                    audio = platformAudio,
                    systemLanguage = Locale.getDefault().toLanguageTag(),
                    reminders = reminder,
                )
            }
            StudioApp(state)
        }
    }

    override fun onDestroy() {
        recorder?.close()
        audio?.stop()
        scope.cancel()
        super.onDestroy()
    }
}
