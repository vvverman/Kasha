@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import brain.studio.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVAudioSessionRecordPermissionUndetermined
import platform.Foundation.NSNotificationCenter
import platform.Speech.SFSpeechRecognizer
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/** Живой снимок системных iOS permissions. Значения не кэшируются. */
internal class IosDeviceCapabilities : DeviceCapabilityGateway {
    private var settingsContinuation: CancellableContinuation<Boolean>? = null
    init {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(APP_DID_BECOME_ACTIVE, null, null) { finishSettingsTransition(true) }
        center.addObserverForName(SETTINGS_OPEN_FAILED, null, null) { finishSettingsTransition(false) }
    }
    override suspend fun snapshot(): DeviceCapabilitySnapshot = DeviceCapabilitySnapshot(
        permissions = mapOf(
            DevicePermissionKind.MICROPHONE to microphoneState(),
            DevicePermissionKind.SPEECH_RECOGNITION to speechState(),
            DevicePermissionKind.NOTIFICATIONS to notificationState(),
        ),
        canOpenSettings = true,
    )
    /** Вызывается только кнопкой пользователя; сама проверка AI разрешений не запрашивает. */
    override suspend fun request(kind: DevicePermissionKind): DevicePermissionState {
        if (kind != DevicePermissionKind.SPEECH_RECOGNITION)
            return snapshot().permissions[kind] ?: DevicePermissionState.UNAVAILABLE
        if (speechState() != DevicePermissionState.NOT_DETERMINED) return speechState()
        return suspendCancellableCoroutine { continuation ->
            SFSpeechRecognizer.requestAuthorization { status ->
                if (continuation.isActive) continuation.resume(mapSpeechStatus(status.value))
            }
        }
    }
    override suspend fun openSettings(): Boolean {
        if (settingsContinuation != null) return false
        return suspendCancellableCoroutine { continuation ->
            settingsContinuation = continuation
            continuation.invokeOnCancellation {
                if (settingsContinuation === continuation) settingsContinuation = null
            }
            NSNotificationCenter.defaultCenter.postNotificationName(OPEN_SYSTEM_SETTINGS, null)
        }
    }
    private fun finishSettingsTransition(success: Boolean) {
        val continuation = settingsContinuation ?: return
        settingsContinuation = null
        if (continuation.isActive) continuation.resume(success)
    }
    private fun microphoneState(): DevicePermissionState = when (AVAudioSession.sharedInstance().recordPermission) {
        AVAudioSessionRecordPermissionGranted -> DevicePermissionState.GRANTED
        AVAudioSessionRecordPermissionDenied -> DevicePermissionState.DENIED
        AVAudioSessionRecordPermissionUndetermined -> DevicePermissionState.NOT_DETERMINED
        else -> DevicePermissionState.UNAVAILABLE
    }
    private fun speechState(): DevicePermissionState = mapSpeechStatus(SFSpeechRecognizer.authorizationStatus().value)
    private suspend fun notificationState(): DevicePermissionState = suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            if (continuation.isActive) continuation.resume(mapNotificationStatus(settings?.authorizationStatus ?: -1L))
        }
    }
    internal companion object {
        const val OPEN_SYSTEM_SETTINGS = "KashaOpenSystemSettings"
        const val SETTINGS_OPEN_FAILED = "KashaSystemSettingsOpenFailed"
        const val APP_DID_BECOME_ACTIVE = "KashaApplicationDidBecomeActive"
        fun mapSpeechStatus(value: Long): DevicePermissionState = when (value) {
            0L -> DevicePermissionState.NOT_DETERMINED
            1L, 2L -> DevicePermissionState.DENIED
            3L -> DevicePermissionState.GRANTED
            else -> DevicePermissionState.UNAVAILABLE
        }
        fun mapNotificationStatus(value: Long): DevicePermissionState = when (value) {
            0L -> DevicePermissionState.NOT_DETERMINED
            1L -> DevicePermissionState.DENIED
            2L, 3L, 4L -> DevicePermissionState.GRANTED
            else -> DevicePermissionState.UNAVAILABLE
        }
    }
}
