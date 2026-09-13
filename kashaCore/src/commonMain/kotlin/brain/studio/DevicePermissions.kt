package brain.studio

import kotlinx.serialization.Serializable

/** Системные разрешения/возможности, которые продукт показывает независимо друг от друга. */
@Serializable
enum class DevicePermission {
    MICROPHONE,
    SPEECH_RECOGNITION,
    NOTIFICATIONS,
}

/** Фактический статус от ОС. Это не пользовательская настройка и не сохраняется в Preferences. */
@Serializable
enum class DevicePermissionStatus {
    NOT_DETERMINED,
    GRANTED,
    DENIED,
    RESTRICTED,
    UNAVAILABLE,
}

@Serializable
data class DevicePermissionSnapshot(
    val microphone: DevicePermissionStatus = DevicePermissionStatus.UNAVAILABLE,
    val speechRecognition: DevicePermissionStatus = DevicePermissionStatus.UNAVAILABLE,
    val notifications: DevicePermissionStatus = DevicePermissionStatus.UNAVAILABLE,
) {
    operator fun get(permission: DevicePermission): DevicePermissionStatus = when (permission) {
        DevicePermission.MICROPHONE -> microphone
        DevicePermission.SPEECH_RECOGNITION -> speechRecognition
        DevicePermission.NOTIFICATIONS -> notifications
    }

    fun with(permission: DevicePermission, status: DevicePermissionStatus): DevicePermissionSnapshot = when (permission) {
        DevicePermission.MICROPHONE -> copy(microphone = status)
        DevicePermission.SPEECH_RECOGNITION -> copy(speechRecognition = status)
        DevicePermission.NOTIFICATIONS -> copy(notifications = status)
    }
}

/**
 * Платформенная граница системных permission dialogs и Settings deep-link.
 *
 * Инварианты:
 * - status() только читает фактическое состояние ОС и не открывает системный dialog;
 * - request() вызывается только после явного продуктового действия/разрешённого shared flow;
 * - openSystemSettings() не меняет permission state сам, после возврата status перечитывается;
 * - supportedPermissions описывает только реально реализованные platform capabilities.
 */
interface DevicePermissionGateway {
    val supportedPermissions: Set<DevicePermission>
    val canOpenSystemSettings: Boolean

    suspend fun status(permission: DevicePermission): DevicePermissionStatus
    suspend fun request(permission: DevicePermission): DevicePermissionStatus
    fun openSystemSettings(): Boolean
}

object NoopDevicePermissionGateway : DevicePermissionGateway {
    override val supportedPermissions: Set<DevicePermission> = emptySet()
    override val canOpenSystemSettings: Boolean = false
    override suspend fun status(permission: DevicePermission): DevicePermissionStatus = DevicePermissionStatus.UNAVAILABLE
    override suspend fun request(permission: DevicePermission): DevicePermissionStatus = DevicePermissionStatus.UNAVAILABLE
    override fun openSystemSettings(): Boolean = false
}
