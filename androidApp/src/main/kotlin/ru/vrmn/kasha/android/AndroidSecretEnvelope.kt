package ru.vrmn.kasha.android

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Формат защищённого системным ключом ciphertext. Не содержит открытый секрет. */
internal object AndroidSecretEnvelope {
    private val magic = byteArrayOf(0x4b, 0x53, 0x48, 1)
    fun fileName(id: String): String {
        require(id.isNotBlank()) { "invalidSecretId" }
        return MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) } + ".bin"
    }
    private fun aad(id: String): ByteArray = ("ru.vrmn.kasha/secrets/v1/" + id).toByteArray(Charsets.UTF_8)

    fun encrypt(key: SecretKey, id: String, value: ByteArray): ByteArray {
        require(id.isNotBlank()) { "invalidSecretId" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad(id))
        val iv = cipher.iv
        check(iv.size == 12) { "unsupportedSecretNonce" }
        return magic + iv + cipher.doFinal(value)
    }

    fun decrypt(key: SecretKey, id: String, value: ByteArray): ByteArray {
        require(value.size >= 32 && value.take(4).toByteArray().contentEquals(magic)) { "invalidSecretEnvelope" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(4, 16)))
        cipher.updateAAD(aad(id))
        return cipher.doFinal(value, 16, value.size - 16)
    }
}
