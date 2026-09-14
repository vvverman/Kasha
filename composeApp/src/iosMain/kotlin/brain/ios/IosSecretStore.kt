package brain.ios

import brain.studio.SecureSecretDeletion
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.Settings

internal interface IosSecretStore {
    val available: Boolean
    fun get(providerId: String): String?
    fun put(providerId: String, value: String)
    fun remove(providerId: String): SecureSecretDeletion
}

/** API keys live only in Apple Keychain. There is deliberately no file/defaults fallback. */
@OptIn(ExperimentalSettingsImplementation::class)
internal class IosKeychainSecretStore(
    private val settings: Settings = KeychainSettings(SERVICE),
) : IosSecretStore {
    override val available: Boolean = true

    override fun get(providerId: String): String? = settings
        .getStringOrNull(key(providerId))
        ?.takeIf(String::isNotBlank)

    override fun put(providerId: String, value: String) {
        require(value.isNotBlank()) { "emptyApiKey" }
        settings.putString(key(providerId), value)
    }

    override fun remove(providerId: String): SecureSecretDeletion {
        val secretKey = key(providerId)
        val existed = settings.hasKey(secretKey)
        settings.remove(secretKey)
        return if (existed) SecureSecretDeletion.DELETED else SecureSecretDeletion.NOT_FOUND
    }

    private fun key(providerId: String): String = "provider:${providerId.trim()}"

    private companion object {
        const val SERVICE = "ru.vrmn.kasha.external-ai"
    }
}
