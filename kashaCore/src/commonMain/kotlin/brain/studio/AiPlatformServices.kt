package brain.studio

import kotlinx.serialization.Serializable

@Serializable
enum class DevicePermissionKind { MICROPHONE, SPEECH_RECOGNITION, NOTIFICATIONS }

@Serializable
enum class DevicePermissionState { GRANTED, DENIED, NOT_DETERMINED, UNAVAILABLE }

@Serializable
data class DeviceCapabilitySnapshot(
    val permissions: Map<DevicePermissionKind, DevicePermissionState> = emptyMap(),
    val canOpenSettings: Boolean = false,
)

interface DeviceCapabilityGateway {
    suspend fun snapshot(): DeviceCapabilitySnapshot
    suspend fun openSettings(): Boolean
    /** Только по явному действию пользователя, не при проверке готовности. */
    suspend fun request(kind: DevicePermissionKind): DevicePermissionState =
        snapshot().permissions[kind] ?: DevicePermissionState.UNAVAILABLE
}

object NoopDeviceCapabilityGateway : DeviceCapabilityGateway {
    override suspend fun snapshot() = DeviceCapabilitySnapshot()
    override suspend fun openSettings(): Boolean = false
}

@Serializable
data class AiRoleCapability(
    val role: AiRole,
    val selectedEngineId: String,
    val executable: Boolean,
    val reason: String? = null,
    val permission: DevicePermissionKind? = null,
)

interface AiExecutionCapabilityGateway {
    suspend fun roles(selection: AiSelection): List<AiRoleCapability>
}

object NoopAiExecutionCapabilityGateway : AiExecutionCapabilityGateway {
    override suspend fun roles(selection: AiSelection): List<AiRoleCapability> = AiRole.entries.map { role ->
        AiRoleCapability(role, selection.engineId(role), executable = false, reason = "platformUnavailable")
    }
}

/** Опциональные системные сервисы; основной контракт данных остаётся независимым. */
interface AiPlatformServices {
    val aiPackages: AiPackageGateway
    val cloudAi: CloudAiGateway
    val aiExecution: AiExecutionCapabilityGateway get() = NoopAiExecutionCapabilityGateway
    val deviceCapabilities: DeviceCapabilityGateway get() = NoopDeviceCapabilityGateway
}
