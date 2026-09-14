package ru.vrmn.kasha.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Android Keystore — единственный источник ключа. Backup и plaintext fallback отсутствуют. */
internal class AndroidSecretStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "kasha-secrets")
    private val gate = Mutex()
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    suspend fun read(id: String): String? = withContext(Dispatchers.IO) {
        gate.withLock {
            val file = AtomicFile(File(directory, AndroidSecretEnvelope.fileName(id)))
            val encrypted = try { file.readFully() } catch (_: FileNotFoundException) { return@withLock null }
            val bytes = AndroidSecretEnvelope.decrypt(key(create = false), id, encrypted)
            try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
        }
    }

    suspend fun write(id: String, value: String) = withContext(Dispatchers.IO) {
        require(value.isNotBlank()) { "emptySecret" }
        gate.withLock {
            check(directory.isDirectory || directory.mkdirs()) { "secureStorageUnavailable" }
            val bytes = value.toByteArray(Charsets.UTF_8)
            val encrypted = try { AndroidSecretEnvelope.encrypt(key(create = true), id, bytes) } finally { bytes.fill(0) }
            val file = AtomicFile(File(directory, AndroidSecretEnvelope.fileName(id)))
            val stream = file.startWrite()
            try {
                stream.write(encrypted)
                file.finishWrite(stream)
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        }
    }

    /** Возвращает true только если существовавшая запись действительно удалена. */
    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        gate.withLock {
            val base = File(directory, AndroidSecretEnvelope.fileName(id))
            val backup = File(base.path + ".bak")
            val temporary = File(base.path + ".new")
            val existed = base.exists() || backup.exists() || temporary.exists()
            AtomicFile(base).delete()
            check(!base.exists() && !backup.exists() && !temporary.exists()) { "secretDeletionFailed" }
            existed
        }
    }

    private fun key(create: Boolean): SecretKey {
        if (store.containsAlias(ALIAS)) return store.getKey(ALIAS, null) as? SecretKey ?: error("secureKeyUnavailable")
        // Потерянный системный ключ не заменяем молча поверх существующего ciphertext.
        check(create && directory.listFiles().orEmpty().none { it.name.endsWith(".bin") || it.name.endsWith(".bin.bak") || it.name.endsWith(".bin.new") }) { "secureKeyUnavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    companion object { private const val ALIAS = "ru.vrmn.kasha.api-secrets.v1" }
}
