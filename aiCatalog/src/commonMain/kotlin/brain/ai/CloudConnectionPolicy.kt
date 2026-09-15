package brain.ai

import brain.studio.*

/** Проверки конфигурации до чтения ключа и до любого сетевого вызова. */
object CloudConnectionPolicy {
    fun validate(connection: CloudAiConnection) {
        val provider = AiCatalog.provider(connection.providerId) ?: error("unknownAiProvider")
        require(connection.enabled) { "cloudConnectionDisabled" }
        require(AiPrivacy.hasCurrentConsent(connection)) { "cloudConsentRequired" }
        require(connection.roles.isNotEmpty()) { "cloudModelRequired" }
        require(connection.roles.all { it in provider.roles }) { "cloudRoleUnsupported" }
        if (provider.endpointRequired) endpoint(connection.endpoint.orEmpty())
    }

    fun endpoint(value: String): String {
        val normalized = value.trim().trimEnd('/')
        require(normalized.none { it.isWhitespace() || it.code < 32 || it == '\\' || it == '?' || it == '#' }) { "cloudEndpointMustBeHttps" }
        val match = Regex("^(https?)://([^/]+)(/.*)?$").matchEntire(normalized) ?: error("cloudEndpointMustBeHttps")
        val authority = match.groupValues[2]
        require('@' !in authority && '%' !in authority) { "cloudEndpointMustBeHttps" }
        val hostPort = Regex("^([A-Za-z0-9.-]+)(?::([0-9]{1,5}))?$").matchEntire(authority)
            ?: error("cloudEndpointMustBeHttps")
        val host = hostPort.groupValues[1].lowercase()
        require(host.isNotBlank() && !host.startsWith('.') && !host.endsWith('.') && ".." !in host) { "cloudEndpointMustBeHttps" }
        val port = hostPort.groupValues[2]
        require(port.isEmpty() || (port.toIntOrNull() ?: 0) in 1..65535) { "cloudEndpointMustBeHttps" }
        require(match.groupValues[1] == "https" || host in setOf("localhost", "127.0.0.1")) { "cloudEndpointMustBeHttps" }
        return normalized
    }

    fun requireReplacementKey(previous: CloudAiConnection?, next: CloudAiConnection, apiKey: String?) {
        if (previous != null && AiCatalog.provider(next.providerId)?.endpointRequired == true) {
            val changed = AiPrivacy.snapshot(previous).normalizedEndpoint != AiPrivacy.snapshot(next).normalizedEndpoint
            require(!changed || !apiKey.isNullOrBlank()) { "apiKeyRequired" }
        }
    }
}
