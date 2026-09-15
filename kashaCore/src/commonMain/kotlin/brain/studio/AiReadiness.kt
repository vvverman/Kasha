package brain.studio

import kotlinx.coroutines.CancellationException

/** Действие устраняет блокер, но никогда не подменяет выбранный движок автоматически. */
enum class AiCapabilityAction { NONE, INSTALL_MODEL, CHOOSE_ENGINE, REQUEST_PERMISSION, OPEN_SETTINGS, EDIT_CONNECTION, RETRY }

val AiRoleCapability.action: AiCapabilityAction get() = if (executable) AiCapabilityAction.NONE else when (reason) {
    "modelNotInstalled", "modelInstallFailed" -> AiCapabilityAction.INSTALL_MODEL
    "platformUnavailable", "unknownEngine", "unsupportedRole", "languageUnsupported" -> AiCapabilityAction.CHOOSE_ENGINE
    "permissionRequired" -> AiCapabilityAction.REQUEST_PERMISSION
    "permissionDenied" -> AiCapabilityAction.OPEN_SETTINGS
    "cloudConnectionDisabled", "cloudConsentRequired", "cloudModelRequired", "apiKeyMissing" -> AiCapabilityAction.EDIT_CONNECTION
    "modelDownloading", "capabilityChecking" -> AiCapabilityAction.NONE
    else -> AiCapabilityAction.RETRY
}

/** Общие правила готовности. Здесь нет моделей, ОС, сетевых запросов или чтения секретов. */
object AiReadiness {
    fun blocked(role: AiRole, engineId: String, reason: String) =
        AiRoleCapability(role, engineId, false, reason)

    fun local(
        role: AiRole,
        engineId: String,
        supported: Boolean,
        languageSupported: Boolean,
        state: AiPackageState?,
        runtimeReady: Boolean,
    ): AiRoleCapability {
        val reason = when {
            !supported -> "platformUnavailable"
            !languageSupported -> "languageUnsupported"
            !runtimeReady -> "runtimeUnavailable"
            state?.downloading == true -> "modelDownloading"
            state?.installed == true -> null
            state?.error != null -> "modelInstallFailed"
            else -> "modelNotInstalled"
        }
        return AiRoleCapability(role, engineId, reason == null, reason)
    }

    fun native(
        role: AiRole,
        engineId: String,
        supported: Boolean,
        languageSupported: Boolean,
        permission: DevicePermissionState,
        permissionKind: DevicePermissionKind,
        serviceReady: Boolean,
    ): AiRoleCapability {
        val reason = when {
            !supported -> "platformUnavailable"
            !languageSupported -> "languageUnsupported"
            permission == DevicePermissionState.NOT_DETERMINED -> "permissionRequired"
            permission == DevicePermissionState.DENIED -> "permissionDenied"
            permission != DevicePermissionState.GRANTED -> "platformUnavailable"
            !serviceReady -> "runtimeUnavailable"
            else -> null
        }
        return AiRoleCapability(role, engineId, reason == null, reason,
            permissionKind.takeIf { reason == "permissionRequired" || reason == "permissionDenied" })
    }

    fun cloud(
        role: AiRole,
        engineId: String,
        connection: CloudAiConnection?,
        secureStoreAvailable: Boolean,
        keyPresent: Boolean,
    ): AiRoleCapability {
        val reason = when {
            !secureStoreAvailable -> "secureStoreUnavailable"
            connection == null || !connection.enabled -> "cloudConnectionDisabled"
            connection.modelFor(role) == null -> "cloudModelRequired"
            !AiPrivacy.hasCurrentConsent(connection) -> "cloudConsentRequired"
            !keyPresent -> "apiKeyMissing"
            else -> null
        }
        return AiRoleCapability(role, engineId, reason == null, reason)
    }

    /** Читает только метаданные и факт наличия ключа; test/generate/transcribe не вызываются. */
    suspend fun cloud(
        role: AiRole,
        engineId: String,
        providerId: String,
        gateway: CloudAiGateway,
        hasKey: suspend () -> Boolean,
    ): AiRoleCapability = try {
        val connection = gateway.connections().firstOrNull { it.providerId == providerId }
        val prerequisites = cloud(role, engineId, connection, gateway.available, keyPresent = true)
        if (!prerequisites.executable) prerequisites
        else cloud(role, engineId, connection, gateway.available, hasKey())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        blocked(role, engineId, "capabilityCheckFailed")
    }
}
