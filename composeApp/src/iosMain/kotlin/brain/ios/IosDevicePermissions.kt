@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import brain.domain.RecorderPermission
import brain.studio.DevicePermission
import brain.studio.DevicePermissionGateway
import brain.studio.DevicePermissionStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.*
import platform.Foundation.NSNotificationCenter
import platform.Speech.SFSpeechRecognizer
import platform.UserNotifications.*
import kotlin.coroutines.resume

internal class IosDevicePermissions : DevicePermissionGateway {
    override val supportedPermissions: Set<DevicePermission> = DevicePermission.entries.toSet()
    override val canOpenSystemSettings: Boolean = true

    override suspend fun status(permission: DevicePermission): DevicePermissionStatus = when (permission) {
        DevicePermission.MICROPHONE -> microphoneStatus()
        DevicePermission.SPEECH_RECOGNITION -> speechStatus()
        DevicePermission.NOTIFICATIONS -> notificationStatus()
    }

    override suspend fun request(permission: DevicePermission): DevicePermissionStatus = when (permission) {
        DevicePermission.MICROPHONE -> requestMicrophone()
        DevicePermission.SPEECH_RECOGNITION -> requestSpeech()
        DevicePermission.NOTIFICATIONS -> requestNotifications()
    }

    override fun openSystemSettings(): Boolean {
        NSNotificationCenter.defaultCenter.postNotificationName(OPEN_SYSTEM_SETTINGS, null)
        return true
    }

    suspend fun recorderPermission(): RecorderPermission = when (microphoneStatus()) {
        DevicePermissionStatus.NOT_DETERMINED -> RecorderPermission.NOT_DETERMINED
        DevicePermissionStatus.GRANTED -> RecorderPermission.GRANTED
        DevicePermissionStatus.DENIED -> RecorderPermission.DENIED
        DevicePermissionStatus.RESTRICTED -> RecorderPermission.RESTRICTED
        DevicePermissionStatus.UNAVAILABLE -> RecorderPermission.UNAVAILABLE
    }

    private fun microphoneStatus(): DevicePermissionStatus = when (AVAudioSession.sharedInstance().recordPermission) {
        AVAudioSessionRecordPermissionGranted -> DevicePermissionStatus.GRANTED
        AVAudioSessionRecordPermissionDenied -> DevicePermissionStatus.DENIED
        AVAudioSessionRecordPermissionUndetermined -> DevicePermissionStatus.NOT_DETERMINED
        else -> DevicePermissionStatus.UNAVAILABLE
    }

    private fun speechStatus(): DevicePermissionStatus = when (SFSpeechRecognizer.authorizationStatus().value) {
        SPEECH_NOT_DETERMINED -> DevicePermissionStatus.NOT_DETERMINED
        SPEECH_DENIED -> DevicePermissionStatus.DENIED
        SPEECH_RESTRICTED -> DevicePermissionStatus.RESTRICTED
        SPEECH_AUTHORIZED -> DevicePermissionStatus.GRANTED
        else -> DevicePermissionStatus.UNAVAILABLE
    }

    private suspend fun notificationStatus(): DevicePermissionStatus = suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            if (continuation.isActive) {
                continuation.resume(mapNotificationStatus(settings.authorizationStatus.value))
            }
        }
    }

    private suspend fun requestMicrophone(): DevicePermissionStatus {
        val current = microphoneStatus()
        if (current != DevicePermissionStatus.NOT_DETERMINED) return current
        return suspendCancellableCoroutine { continuation ->
            AVAudioSession.sharedInstance().requestRecordPermission {
                if (continuation.isActive) continuation.resume(microphoneStatus())
            }
        }
    }

    private suspend fun requestSpeech(): DevicePermissionStatus {
        val current = speechStatus()
        if (current != DevicePermissionStatus.NOT_DETERMINED) return current
        return suspendCancellableCoroutine { continuation ->
            SFSpeechRecognizer.requestAuthorization {
                if (continuation.isActive) continuation.resume(speechStatus())
            }
        }
    }

    private suspend fun requestNotifications(): DevicePermissionStatus {
        val current = notificationStatus()
        if (current != DevicePermissionStatus.NOT_DETERMINED) return current
        suspendCancellableCoroutine<Unit> { continuation ->
            UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
                UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
            ) { _, _ ->
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        return notificationStatus()
    }

    internal fun mapNotificationStatus(value: Long): DevicePermissionStatus = when (value) {
        NOTIFICATION_NOT_DETERMINED -> DevicePermissionStatus.NOT_DETERMINED
        NOTIFICATION_DENIED -> DevicePermissionStatus.DENIED
        NOTIFICATION_AUTHORIZED,
        NOTIFICATION_PROVISIONAL,
        NOTIFICATION_EPHEMERAL,
        -> DevicePermissionStatus.GRANTED
        else -> DevicePermissionStatus.UNAVAILABLE
    }

    private companion object {
        const val OPEN_SYSTEM_SETTINGS = "KashaOpenSystemSettings"

        const val SPEECH_NOT_DETERMINED = 0L
        const val SPEECH_DENIED = 1L
        const val SPEECH_RESTRICTED = 2L
        const val SPEECH_AUTHORIZED = 3L

        const val NOTIFICATION_NOT_DETERMINED = 0L
        const val NOTIFICATION_DENIED = 1L
        const val NOTIFICATION_AUTHORIZED = 2L
        const val NOTIFICATION_PROVISIONAL = 3L
        const val NOTIFICATION_EPHEMERAL = 4L
    }
}
