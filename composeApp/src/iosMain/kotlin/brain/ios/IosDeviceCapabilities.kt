@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import brain.studio.DeviceCapabilityGateway
import brain.studio.DeviceCapabilitySnapshot
import brain.studio.DevicePermissionKind
import brain.studio.DevicePermissionState
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
    override suspend fun snapshot(): DeviceCapabilitySnapshot = DeviceCapabilitySnapshot(
        permissions = mapOf(
            DevicePermissionKind.MICROPHONE to microphoneState(),
            DevicePermissionKind.SPEECH_RECOGNITION to speechState(),
            DevicePermissionKind.NOTIFICATIONS to notificationState(),
        ),
        canOpenSettings = true,
    )

    override suspend fun openSettings(): Boolean {
        NSNotificationCenter.defaultCenter.postNotificationName(OPEN_SYSTEM_SETTINGS, null)
        return true
    }

    private fun microphoneState(): DevicePermissionState = when (AVAudioSession.sharedInstance().recordPermission) {
        AVAudioSessionRecordPermissionGranted -> DevicePermissionState.GRANTED
        AVAudioSessionRecordPermissionDenied -> DevicePermissionState.DENIED
        AVAudioSessionRecordPermissionUndetermined -> DevicePermissionState.NOT_DETERMINED
        else -> DevicePermissionState.UNAVAILABLE
    }

    private fun speechState(): DevicePermissionState = mapSpeechStatus(
        SFSpeechRecognizer.authorizationStatus().value,
    )

    private suspend fun notificationState(): DevicePermissionState = suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            if (continuation.isActive) {
                continuation.resume(mapNotificationStatus(settings.authorizationStatus.value))
            }
        }
    }

    internal companion object {
        const val OPEN_SYSTEM_SETTINGS = "KashaOpenSystemSettings"

        fun mapSpeechStatus(value: Long): DevicePermissionState = when (value) {
            0L -> DevicePermissionState.NOT_DETERMINED
            1L, 2L -> DevicePermissionState.DENIED // denied / restricted
            3L -> DevicePermissionState.GRANTED
            else -> DevicePermissionState.UNAVAILABLE
        }

        fun mapNotificationStatus(value: Long): DevicePermissionState = when (value) {
            0L -> DevicePermissionState.NOT_DETERMINED
            1L -> DevicePermissionState.DENIED
            2L, 3L, 4L -> DevicePermissionState.GRANTED // authorized / provisional / ephemeral
            else -> DevicePermissionState.UNAVAILABLE
        }
    }
}
