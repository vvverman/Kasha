package brain.studio

import kotlinx.serialization.Serializable

/** API key передаётся только в platform gateway и никогда не сохраняется в Preferences/Core state. */
@Serializable
data class CloudAiConnectionRequest(
    val connection: CloudAiConnection,
    val apiKey: String? = null,
)

@Serializable
data class CloudAiConnectionTestResult(val ok: Boolean)
