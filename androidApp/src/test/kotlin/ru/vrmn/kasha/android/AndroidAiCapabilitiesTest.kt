package ru.vrmn.kasha.android

import brain.ai.BuiltInAi
import brain.studio.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AndroidAiCapabilitiesTest {
    @Test fun installedFileCannotHideMissingNativeLibrary() = runBlocking {
        val result = check(AiSelection(), runnable = false, installed = true)
        assertTrue(result.all { !it.executable && it.reason == "runtimeUnavailable" })
    }
    @Test fun nativeAndRulesNeedNoModelPackage() = runBlocking {
        var reads = 0
        val selection = BuiltInAi.androidSelection()
        val result = androidRoleCapabilities(selection, "ru", { reads++; error("unexpected file access") }, { false }) {
            AiRoleCapability(AiRole.SPEECH_TO_TEXT, BuiltInAi.ANDROID_SPEECH, true)
        }
        assertTrue(result.all { it.executable })
        assertEquals(0, reads)
    }
    @Test fun missingModelHasInstallAction() = runBlocking {
        val result = check(AiSelection(), installed = false)
        assertTrue(result.all { it.reason == "modelNotInstalled" && it.action == AiCapabilityAction.INSTALL_MODEL })
    }
    @Test fun brokenPackageCheckDoesNotDisableRules() = runBlocking {
        val selection = BuiltInAi.androidSelection().copy(speechToText = AiSelection.DEFAULT_STT)
        val result = androidRoleCapabilities(selection, "ru", { error("unreadable storage") }, { true }) { error("unexpected native call") }
        assertEquals("capabilityCheckFailed", result.first().reason)
        assertTrue(result.drop(1).all { it.executable })
    }
    @Test fun nativePermissionReasonIsPreserved() = runBlocking {
        val result = androidRoleCapabilities(BuiltInAi.androidSelection(), "ru", { emptyList() }, { false }) {
            AiReadiness.native(AiRole.SPEECH_TO_TEXT, BuiltInAi.ANDROID_SPEECH, true, true,
                DevicePermissionState.NOT_DETERMINED, DevicePermissionKind.MICROPHONE, true)
        }
        assertEquals(AiCapabilityAction.REQUEST_PERMISSION, result.first().action)
        assertEquals(DevicePermissionKind.MICROPHONE, result.first().permission)
    }
    @Test fun unsupportedSelectionIsNotRewritten() = runBlocking {
        val selection = AiSelection("local.unknown", "cloud:openai:TEXT", BuiltInAi.APPLE_SPEECH)
        val result = check(selection)
        assertTrue(result.all { !it.executable && it.reason == "platformUnavailable" })
        assertEquals(selection.speechToText, result.first().selectedEngineId)
    }
    @Test fun cancelledChecksRemainCancelled() = runBlocking {
        try {
            androidRoleCapabilities(AiSelection(), "ru", { throw CancellationException("cancel") }, { true }) { error("unexpected") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
    private suspend fun check(selection: AiSelection, runnable: Boolean = true, installed: Boolean = true) =
        androidRoleCapabilities(selection, "ru", {
            listOf(AiPackageState(AiSelection.DEFAULT_STT, installed), AiPackageState(AiSelection.DEFAULT_TEXT, installed))
        }, { runnable }) { error("unexpected native call") }
}
