package brain.studio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** UI принимает только ответ о том же движке и роли, которые были запрошены. */
internal suspend fun readAiCapabilities(
    gateway: AiExecutionCapabilityGateway,
    selection: AiSelection,
): List<AiRoleCapability> {
    val values = try { withTimeoutOrNull(30_000) { gateway.roles(selection) }.orEmpty() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { emptyList() }
    return AiRole.entries.map { role ->
        val id = selection.engineId(role)
        values.filter { it.role == role && it.selectedEngineId == id }.singleOrNull()
            ?.takeIf { !it.executable || it.reason == null }
            ?: AiReadiness.blocked(role, id, "capabilityCheckFailed")
    }
}
