package ru.vrmn.kasha.android

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import brain.domain.CaptureRecoveryCoordinator
import brain.domain.PlaybackSessionGateway
import brain.domain.RecorderPermission
import brain.domain.RecorderSessionGateway
import brain.domain.TransportSnapshot
import brain.studio.DeviceCapabilityGateway
import brain.studio.DeviceCapabilitySnapshot
import brain.studio.DevicePermissionKind
import brain.studio.DevicePermissionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.lang.ref.WeakReference

/** Только Android permission-dialog/result/lifecycle. Не хранит продуктовый capture intent. */
internal class AndroidPermissions(context: Context) : DeviceCapabilityGateway {
    private val app = context.applicationContext
    private val history = app.getSharedPreferences("kasha-permissions", Context.MODE_PRIVATE)
    private var host = WeakReference<ComponentActivity>(null)
    private var launcher: ActivityResultLauncher<String>? = null
    private var waiter: CompletableDeferred<DevicePermissionState>? = null
    private var requested: DevicePermissionKind? = null
    private var systemRequest: DevicePermissionKind? = null
    private var dialog: AlertDialog? = null

    val foreground: Boolean get() = host.get()?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
    val pendingSystemRequest: String? get() = systemRequest?.name

    fun attach(activity: ComponentActivity, value: ActivityResultLauncher<String>, restoredRequest: String?) {
        host = WeakReference(activity)
        launcher = value
        if (systemRequest == null) {
            systemRequest = restoredRequest?.let { name -> DevicePermissionKind.entries.firstOrNull { it.name == name } }
        }
    }

    fun onResume(activity: ComponentActivity) {
        if (host.get() === activity && requested != null && systemRequest == null) showExplanation()
        // Только ранее запрошенное объяснение; ни start(), ни resume() отсюда не вызываются.
    }

    fun detach(activity: ComponentActivity) {
        if (host.get() !== activity) return
        dialog?.dismiss()
        dialog = null
        launcher = null
        host.clear()
        if (!activity.isChangingConfigurations) finish(DevicePermissionState.DENIED)
    }

    fun onResult(granted: Boolean) {
        val kind = systemRequest ?: return
        systemRequest = null
        history.edit().putBoolean(kind.name, true).apply()
        finish(if (granted) status(kind) else DevicePermissionState.DENIED)
    }

    fun status(kind: DevicePermissionKind): DevicePermissionState {
        if (kind == DevicePermissionKind.SPEECH_RECOGNITION) return DevicePermissionState.UNAVAILABLE
        if (kind == DevicePermissionKind.MICROPHONE && !app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            return DevicePermissionState.UNAVAILABLE
        }
        val permission = androidPermission(kind)
        val granted = permission == null || app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (kind == DevicePermissionKind.NOTIFICATIONS && !app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
                return DevicePermissionState.DENIED
            }
            return DevicePermissionState.GRANTED
        }
        return if (history.getBoolean(kind.name, false)) DevicePermissionState.DENIED else DevicePermissionState.NOT_DETERMINED
    }

    fun recorderPermission(): RecorderPermission = when (status(DevicePermissionKind.MICROPHONE)) {
        DevicePermissionState.GRANTED -> RecorderPermission.GRANTED
        DevicePermissionState.DENIED -> RecorderPermission.DENIED
        DevicePermissionState.NOT_DETERMINED -> RecorderPermission.NOT_DETERMINED
        DevicePermissionState.UNAVAILABLE -> RecorderPermission.UNAVAILABLE
    }

    override suspend fun snapshot() = DeviceCapabilitySnapshot(
        permissions = DevicePermissionKind.entries.associateWith(::status),
        canOpenSettings = host.get() != null,
    )

    override suspend fun openSettings(): Boolean = withContext(Dispatchers.Main.immediate) {
        val activity = host.get() ?: return@withContext false
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
            true
        }.getOrDefault(false)
    }

    /** Вызывается из системного действия, не из snapshot/foreground. */
    suspend fun request(kind: DevicePermissionKind): DevicePermissionState = withContext(Dispatchers.Main.immediate) {
        val current = status(kind)
        if (current == DevicePermissionState.GRANTED || current == DevicePermissionState.UNAVAILABLE) return@withContext current
        waiter?.let { return@withContext if (requested == kind) it.await() else current }
        if (host.get() == null || systemRequest != null) return@withContext current
        val result = CompletableDeferred<DevicePermissionState>()
        waiter = result
        requested = kind
        showExplanation()
        try {
            result.await()
        } finally {
            if (waiter === result) {
                waiter = null
                requested = null
                dialog?.dismiss()
                dialog = null
                // Callback OS prompt всё ещё обновит history, но отменённый start не возобновится.
            }
        }
    }

    /** Только продолжение конкретного grant-result; уход в background отменяет ожидание. */
    suspend fun awaitPermissionReturn(): Boolean = withContext(Dispatchers.Main.immediate) {
        val activity = host.get() ?: return@withContext false
        val lifecycle = activity.lifecycle
        if (foreground) return@withContext true
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@withContext false
        withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                lateinit var observer: LifecycleEventObserver
                observer = LifecycleEventObserver { _, event ->
                    val answer = when (event) {
                        Lifecycle.Event.ON_RESUME -> host.get() === activity
                        Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> false
                        else -> null
                    }
                    if (answer != null && continuation.isActive) {
                        lifecycle.removeObserver(observer)
                        continuation.resume(answer)
                    }
                }
                lifecycle.addObserver(observer)
                continuation.invokeOnCancellation {
                    activity.runOnUiThread { lifecycle.removeObserver(observer) }
                }
            }
        } ?: false
    }

    private fun showExplanation() {
        val activity = host.get() ?: return
        val kind = requested ?: return
        if (!foreground || dialog != null || systemRequest != null) return
        val permission = androidPermission(kind) ?: run { finish(status(kind)); return }
        val needsSettings = status(kind) == DevicePermissionState.DENIED && !activity.shouldShowRequestPermissionRationale(permission)
        val copy = AndroidPlatformCopy.forLanguage(activity.resources.configuration.locales[0].language)
        dialog = AlertDialog.Builder(activity)
            .setTitle("Kasha")
            .setMessage(if (kind == DevicePermissionKind.MICROPHONE) copy.microphone else copy.notifications)
            .setPositiveButton(if (needsSettings) copy.settings else copy.allow) { _, _ ->
                dialog = null
                if (needsSettings) {
                    runCatching { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))) }
                    finish(status(kind))
                } else {
                    systemRequest = kind
                    try { checkNotNull(launcher).launch(permission) }
                    catch (_: Exception) { systemRequest = null; finish(status(kind)) }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> dialog = null; finish(status(kind)) }
            .setOnCancelListener { dialog = null; finish(status(kind)) }
            .create()
        dialog?.show()
    }

    private fun finish(value: DevicePermissionState) {
        val result = waiter
        waiter = null
        requested = null
        dialog?.dismiss()
        dialog = null
        result?.complete(value)
    }

    private fun androidPermission(kind: DevicePermissionKind): String? = when (kind) {
        DevicePermissionKind.MICROPHONE -> Manifest.permission.RECORD_AUDIO
        DevicePermissionKind.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        DevicePermissionKind.SPEECH_RECOGNITION -> null
    }
}

/** Системный permission preflight; правила допустимости делегируются существующему Core. */
internal class AndroidPermissionRecorder(
    private val delegate: RecorderSessionGateway,
    private val permissions: AndroidPermissions,
    private val repository: AndroidStudioRepository,
    private val audio: PlaybackSessionGateway,
) : RecorderSessionGateway by delegate {
    override suspend fun permission() = permissions.recorderPermission()
    override suspend fun hasConsent() = permission() == RecorderPermission.GRANTED

    override suspend fun start() {
        check(permissions.request(DevicePermissionKind.MICROPHONE) == DevicePermissionState.GRANTED) { "microphonePermissionDenied" }
        check(permissions.awaitPermissionReturn()) { "microphoneRequiresForeground" }
        val currentId = repository.snapshot().captures.firstOrNull { it.isInbox }?.id
        check(CaptureRecoveryCoordinator(this).snapshot(currentId).canAttemptNewRecording) { "currentExists" }
        check(TransportSnapshot(sessionState().phase, audio.playbackState()).canStartRecording) { "stopPlayback" }
        check(permissions.foreground) { "microphoneRequiresForeground" }
        delegate.start()
    }
}
