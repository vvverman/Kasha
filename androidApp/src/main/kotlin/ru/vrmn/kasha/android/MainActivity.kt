package ru.vrmn.kasha.android

import android.content.Intent
import android.graphics.Color
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import brain.studio.StudioApp
import brain.studio.Tab

/** Продуктовые экраны, scale, semantics и insets остаются в shared Kasha UI. */
class MainActivity : ComponentActivity() {
    private val runtime get() = (application as KashaApplication).platform
    private var notificationTaskId by mutableStateOf<String?>(null)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        runtime.permissions.onResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runtime.permissions.attach(this, permissionLauncher, savedInstanceState?.getString(PENDING_PERMISSION))
        notificationTaskId = savedInstanceState?.getString(PENDING_TASK) ?: intent.getStringExtra(AndroidReminders.EXTRA_TASK_ID)
        val state = runtime.state
        setContent {
            val dark = when (state.preferences.theme) {
                "dark" -> true
                "light" -> false
                else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                onDispose { }
            }
            StudioApp(state)
            LaunchedEffect(notificationTaskId, state.initialized) {
                val id = notificationTaskId ?: return@LaunchedEffect
                if (!state.initialized) return@LaunchedEffect
                val task = state.snapshot.tasks.firstOrNull { it.id == id }
                if (task != null) {
                    state.navigate(Tab.TASKS)
                    state.taskArchive = task.completed
                    state.openTask(task.id)
                }
                notificationTaskId = null
                intent.removeExtra(AndroidReminders.EXTRA_TASK_ID)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        runtime.permissions.onResume(this)
        runtime.onForeground()
    }

    override fun onStop() {
        runtime.onBackground()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationTaskId = intent.getStringExtra(AndroidReminders.EXTRA_TASK_ID)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PENDING_PERMISSION, runtime.permissions.pendingSystemRequest)
        outState.putString(PENDING_TASK, notificationTaskId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        runtime.permissions.detach(this)
        super.onDestroy()
    }

    companion object {
        private const val PENDING_PERMISSION = "kasha.pendingPermission"
        private const val PENDING_TASK = "kasha.pendingTask"
    }
}
