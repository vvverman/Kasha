package ru.vrmn.kasha.android

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import brain.studio.DevicePermission
import brain.studio.DevicePermissionGateway
import brain.studio.DevicePermissionStatus
import kotlinx.coroutines.CompletableDeferred

/**
 * Тонкий Android host системного permission dialog.
 *
 * Product flow решает, КОГДА вызывать request(); этот adapter только исполняет
 * уже явное shared-намерение. Он не стартует recorder и не открывает dialog сам.
 */
internal class AndroidDevicePermissionGateway(
    private val application: Application,
) : DevicePermissionGateway {
    override val supportedPermissions: Set<DevicePermission> = setOf(DevicePermission.MICROPHONE)
    override val canOpenSystemSettings: Boolean = true

    private val history = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var activity: ComponentActivity? = null
    @Volatile private var microphoneLauncher: ActivityResultLauncher<String>? = null
    @Volatile private var inFlight: CompletableDeferred<DevicePermissionStatus>? = null
    @Volatile private var requestLaunched = false

    /** Вызывать из Activity.onCreate до STARTED, чтобы ActivityResultRegistry восстановил pending result. */
    fun bind(host: ComponentActivity) {
        val launcher = host.activityResultRegistry.register(
            MICROPHONE_REQUEST_KEY,
            host,
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            finishMicrophoneRequest(granted)
        }
        synchronized(lock) {
            activity = host
            microphoneLauncher = launcher
        }
        launchPendingRequest()
    }

    fun unbind(host: ComponentActivity) {
        synchronized(lock) {
            if (activity === host) {
                activity = null
                microphoneLauncher = null
            }
        }
    }

    override suspend fun status(permission: DevicePermission): DevicePermissionStatus = when (permission) {
        DevicePermission.MICROPHONE -> microphoneStatus()
        else -> DevicePermissionStatus.UNAVAILABLE
    }

    override suspend fun request(permission: DevicePermission): DevicePermissionStatus {
        if (permission != DevicePermission.MICROPHONE) return DevicePermissionStatus.UNAVAILABLE

        val current = microphoneStatus()
        if (
            current == DevicePermissionStatus.GRANTED ||
            current == DevicePermissionStatus.UNAVAILABLE ||
            current == DevicePermissionStatus.RESTRICTED
        ) {
            return current
        }

        val deferred = synchronized(lock) {
            inFlight ?: CompletableDeferred<DevicePermissionStatus>().also {
                inFlight = it
                requestLaunched = false
            }
        }
        launchPendingRequest()
        return deferred.await()
    }

    override fun openSystemSettings(): Boolean = runCatching {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", application.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        application.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun launchPendingRequest() {
        mainHandler.post {
            val launcher = synchronized(lock) {
                if (inFlight == null || requestLaunched) return@synchronized null
                microphoneLauncher?.also { requestLaunched = true }
            } ?: return@post

            val requestedBefore = history.getBoolean(MICROPHONE_REQUESTED_KEY, false)
            if (!requestedBefore) {
                val persisted = history.edit().putBoolean(MICROPHONE_REQUESTED_KEY, true).commit()
                if (!persisted) {
                    failPendingLaunch(DevicePermissionStatus.NOT_DETERMINED)
                    return@post
                }
            }

            try {
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            } catch (_: Throwable) {
                if (!requestedBefore) {
                    history.edit().putBoolean(MICROPHONE_REQUESTED_KEY, false).commit()
                }
                failPendingLaunch(microphoneStatus(requestInFlight = false))
            }
        }
    }

    private fun finishMicrophoneRequest(granted: Boolean) {
        val host = activity
        val blocked = !granted && host?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
        history.edit()
            .putBoolean(MICROPHONE_REQUESTED_KEY, true)
            .putBoolean(MICROPHONE_BLOCKED_KEY, blocked)
            .commit()

        val result = when {
            granted -> DevicePermissionStatus.GRANTED
            blocked -> DevicePermissionStatus.RESTRICTED
            else -> DevicePermissionStatus.DENIED
        }
        val deferred = synchronized(lock) {
            requestLaunched = false
            inFlight.also { inFlight = null }
        }
        deferred?.complete(result)
    }

    private fun failPendingLaunch(status: DevicePermissionStatus) {
        val deferred = synchronized(lock) {
            requestLaunched = false
            inFlight.also { inFlight = null }
        }
        deferred?.complete(status)
    }

    private fun microphoneStatus(
        requestInFlight: Boolean = synchronized(lock) { inFlight != null },
    ): DevicePermissionStatus {
        val packageManager = application.packageManager
        val hasMicrophone = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        val granted = application.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted && history.getBoolean(MICROPHONE_BLOCKED_KEY, false)) {
            // Settings/OS мог снова выдать доступ. Это только platform metadata, не Core Preferences.
            history.edit().putBoolean(MICROPHONE_BLOCKED_KEY, false).apply()
        }
        return classifyMicrophonePermission(
            hasMicrophone = hasMicrophone,
            granted = granted,
            requestedBefore = history.getBoolean(MICROPHONE_REQUESTED_KEY, false),
            blockedByObservedResult = history.getBoolean(MICROPHONE_BLOCKED_KEY, false),
            requestInFlight = requestInFlight,
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "kasha-device-permissions"
        const val MICROPHONE_REQUESTED_KEY = "microphone-requested"
        const val MICROPHONE_BLOCKED_KEY = "microphone-blocked"
        const val MICROPHONE_REQUEST_KEY = "kasha:permission:microphone"
    }
}

/**
 * Public-SDK-only mapping. RESTRICTED ставится только после реально наблюдённого
 * deny-result, где Android больше не предлагает rationale/system prompt.
 */
internal fun classifyMicrophonePermission(
    hasMicrophone: Boolean,
    granted: Boolean,
    requestedBefore: Boolean,
    blockedByObservedResult: Boolean,
    requestInFlight: Boolean,
): DevicePermissionStatus = when {
    !hasMicrophone -> DevicePermissionStatus.UNAVAILABLE
    granted -> DevicePermissionStatus.GRANTED
    requestInFlight -> DevicePermissionStatus.NOT_DETERMINED
    !requestedBefore -> DevicePermissionStatus.NOT_DETERMINED
    blockedByObservedResult -> DevicePermissionStatus.RESTRICTED
    else -> DevicePermissionStatus.DENIED
}
