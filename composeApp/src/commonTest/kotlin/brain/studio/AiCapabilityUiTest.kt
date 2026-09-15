package brain.studio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AiCapabilityUiTest {
    @Test fun missingAndMismatchedAnswersCannotBecomeReady() = runTest {
        val gateway = gateway { listOf(AiRoleCapability(AiRole.TEXT, "wrong-id", true)) }
        val result = readAiCapabilities(gateway, AiSelection())
        assertTrue(result.all { !it.executable && it.reason == "capabilityCheckFailed" })
    }
    @Test fun exactAnswersPreserveReasonAndRequiredAction() = runTest {
        val gateway = gateway { selection -> AiRole.entries.map { role ->
            AiReadiness.blocked(role, selection.engineId(role), "modelNotInstalled")
        } }
        val result = readAiCapabilities(gateway, AiSelection())
        assertTrue(result.all { it.action == AiCapabilityAction.INSTALL_MODEL })
    }
    @Test fun contradictoryAndDuplicateAnswersAreRejected() = runTest {
        val gateway = gateway { selection -> listOf(
            AiRoleCapability(AiRole.TEXT, selection.text, true, "runtimeUnavailable"),
            AiRoleCapability(AiRole.ROUTING, selection.routing, true),
            AiRoleCapability(AiRole.ROUTING, selection.routing, false, "modelNotInstalled"),
        ) }
        assertTrue(readAiCapabilities(gateway, AiSelection()).all { !it.executable })
    }
    @Test fun failureIsNotReportedAsMissingModel() = runTest {
        val result = readAiCapabilities(gateway { error("sensitive implementation detail") }, AiSelection())
        assertTrue(result.all { it.reason == "capabilityCheckFailed" })
    }
    @Test fun cancellationIsNotAnErrorState() = runTest {
        assertFailsWith<CancellationException> {
            readAiCapabilities(gateway { throw CancellationException("cancel") }, AiSelection())
        }
    }
    @Test fun stalledCheckOffersRetryInsteadOfCheckingForever() = runTest {
        val result = readAiCapabilities(gateway { delay(31_000); emptyList() }, AiSelection())
        assertTrue(result.all { it.reason == "capabilityCheckFailed" && it.action == AiCapabilityAction.RETRY })
    }
    @Test fun allEightLanguagesHaveEveryStatusAndAction() {
        for (language in listOf("ru", "en", "es", "fr", "de", "uk", "be", "kk")) {
            assertTrue(AiReadinessCopy.complete(language), language)
            assertTrue(Copy.text(language, "about").isNotBlank(), language)
            for (action in AiCapabilityAction.entries.filterNot { it == AiCapabilityAction.NONE })
                assertTrue(AiReadinessCopy.text(language, action.name).isNotBlank())
        }
    }
    private fun gateway(action: suspend (AiSelection) -> List<AiRoleCapability>) = object : AiExecutionCapabilityGateway {
        override suspend fun roles(selection: AiSelection) = action(selection)
    }
}
