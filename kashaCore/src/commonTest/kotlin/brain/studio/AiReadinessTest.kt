package brain.studio

import kotlin.test.*

class AiReadinessTest {
    private val role = AiRole.SPEECH_TO_TEXT
    private val id = "test.engine"

    @Test fun downloadedPackageAloneDoesNotMeanRunnable() {
        val result = AiReadiness.local(role, id, true, true, AiPackageState(id, true), false)
        assertFalse(result.executable)
        assertEquals("runtimeUnavailable", result.reason)
        assertEquals(AiCapabilityAction.RETRY, result.action)
    }
    @Test fun missingPackageIsDistinctFromUnsupportedRuntime() {
        assertEquals("modelNotInstalled", AiReadiness.local(role, id, true, true, null, true).reason)
        assertEquals("platformUnavailable", AiReadiness.local(role, id, false, true, null, true).reason)
        assertEquals(AiCapabilityAction.INSTALL_MODEL, AiReadiness.local(role, id, true, true, null, true).action)
    }
    @Test fun downloadingAndInstallationErrorAreNotReady() {
        assertEquals("modelDownloading", AiReadiness.local(role, id, true, true, AiPackageState(id, false, true), true).reason)
        assertEquals("modelInstallFailed", AiReadiness.local(role, id, true, true, AiPackageState(id, false, error = "failure"), true).reason)
    }
    @Test fun supportedRuntimeAndInstalledPackageAreReady() {
        val result = AiReadiness.local(role, id, true, true, AiPackageState(id, true), true)
        assertTrue(result.executable)
        assertNull(result.reason)
        assertEquals(id, result.selectedEngineId)
    }
    @Test fun languageSupportIsIndependentOfPackage() {
        assertEquals("languageUnsupported", AiReadiness.local(role, id, true, false, AiPackageState(id, true), true).reason)
    }
    @Test fun nativeDoesNotRequireDownloadablePackage() {
        val result = native(DevicePermissionState.GRANTED)
        assertTrue(result.executable)
        assertNull(result.permission)
        assertEquals(AiCapabilityAction.NONE, result.action)
    }
    @Test fun firstPermissionAndDeniedPermissionHaveDifferentActions() {
        assertEquals(AiCapabilityAction.REQUEST_PERMISSION, native(DevicePermissionState.NOT_DETERMINED).action)
        assertEquals(AiCapabilityAction.OPEN_SETTINGS, native(DevicePermissionState.DENIED).action)
        assertEquals(DevicePermissionKind.SPEECH_RECOGNITION, native(DevicePermissionState.DENIED).permission)
    }
    @Test fun grantedPermissionDoesNotHideUnavailableService() {
        assertEquals("runtimeUnavailable", native(DevicePermissionState.GRANTED, ready = false).reason)
        assertEquals("languageUnsupported", native(DevicePermissionState.GRANTED, language = false).reason)
    }
    @Test fun metadataWithoutKeyDoesNotMeanCloudReady() {
        val result = cloud(connection(), key = false)
        assertFalse(result.executable)
        assertEquals("credentialMissing", result.reason)
        assertEquals(AiCapabilityAction.EDIT_CONNECTION, result.action)
    }
    @Test fun cloudDisabledConsentAndSecureStoreAreDistinct() {
        assertEquals("cloudConnectionDisabled", cloud(null).reason)
        assertEquals("cloudConnectionDisabled", cloud(connection().copy(enabled = false)).reason)
        assertEquals("cloudConsentRequired", cloud(connection().copy(privacyConsentVersion = 0)).reason)
        assertEquals("secureStoreUnavailable", cloud(connection(), store = false).reason)
    }
    @Test fun cloudRoleMustHaveItsOwnModel() {
        assertEquals("cloudModelRequired", cloud(connection().copy(modelIds = mapOf(AiRole.TEXT to "other"))).reason)
    }
    @Test fun completeCloudConfigurationIsReadyWithoutChangingSelection() {
        val connection = connection()
        assertTrue(cloud(connection).executable)
        assertEquals("test.engine", cloud(connection).selectedEngineId)
        assertTrue(AiPrivacy.hasCurrentConsent(connection))
    }

    private fun native(permission: DevicePermissionState, ready: Boolean = true, language: Boolean = true) =
        AiReadiness.native(role, id, true, language, permission, DevicePermissionKind.SPEECH_RECOGNITION, ready)
    private fun cloud(connection: CloudAiConnection?, store: Boolean = true, key: Boolean = true) =
        AiReadiness.cloud(role, id, connection, store, key)
    private fun connection(): CloudAiConnection {
        val value = CloudAiConnection("test", mapOf(role to "model"), enabled = true,
            privacyConsentVersion = AiPrivacy.CONSENT_VERSION)
        return value.copy(consentSnapshot = AiPrivacy.snapshot(value))
    }
}
