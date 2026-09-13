package brain.studio

import brain.domain.RecorderGateway
import brain.domain.RecorderPermission
import brain.domain.RecorderSessionGateway

internal class StudioDeviceCapabilityGateway(
    private val recorder: RecorderGateway,
    private val reminders: ReminderGateway,
) : DeviceCapabilityGateway {
    override suspend fun snapshot(): DeviceCapabilitySnapshot {
        val recorderPermission = (recorder as? RecorderSessionGateway)?.permission()
        val microphone = when (recorderPermission) {
            RecorderPermission.GRANTED -> DevicePermissionState.GRANTED
            RecorderPermission.DENIED, RecorderPermission.RESTRICTED -> DevicePermissionState.DENIED
            RecorderPermission.NOT_DETERMINED -> DevicePermissionState.NOT_DETERMINED
            RecorderPermission.UNAVAILABLE, null -> DevicePermissionState.UNAVAILABLE
        }
        return DeviceCapabilitySnapshot(
            permissions = mapOf(
                DevicePermissionKind.MICROPHONE to microphone,
                DevicePermissionKind.SPEECH_RECOGNITION to DevicePermissionState.UNAVAILABLE,
                DevicePermissionKind.NOTIFICATIONS to if (reminders.available) {
                    DevicePermissionState.NOT_DETERMINED
                } else {
                    DevicePermissionState.UNAVAILABLE
                },
            ),
            canOpenSettings = false,
        )
    }

    override suspend fun openSettings(): Boolean = false
}
