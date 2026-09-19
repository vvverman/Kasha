package ru.vrmn.kasha.android

import brain.ai.ModelArtifact
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest

class AndroidTextPackagesTest {
    private val payload = "Test GGUF bytes, not a real model".toByteArray()
    private fun spec(id: String, name: String) = ModelArtifact(id, name, "https://models.test/$name",
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) })
    private val text = spec("local.qwen.4b", "qwen.gguf")
    private val speech = spec("local.whisper.small", "whisper.bin")
    private fun connection(url: URL) = object : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getInputStream() = ByteArrayInputStream(payload)
        override fun getContentLengthLong() = payload.size.toLong()
    }
    private suspend fun withDirectory(block: suspend (File) -> Unit) {
        val root = Files.createTempDirectory("kasha-text-packages-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    @Test fun textDownloadUsesExistingHashVerificationAndExactFile() = runBlocking<Unit> {
        withDirectory { root ->
            val packages = AndroidWhisperPackages(root, mapOf(text.engineId to text), { true }, ::connection)
            packages.install(text.engineId)
            assertTrue(packages.states().single().installed)
            packages.withModel(text.engineId) { assertArrayEquals(payload, it.readBytes()) }
            assertFalse(File(root, ".${text.fileName}.part").exists())
        }
    }
    @Test fun unlinkedTextEngineDoesNotBecomeAvailableBecauseWhisperWorks() = runBlocking<Unit> {
        withDirectory { root ->
            val packages = AndroidWhisperPackages(root, mapOf(text.engineId to text, speech.engineId to speech),
                { true }, ::connection, engineAvailable = { it == speech.engineId })
            assertEquals(listOf(speech.engineId), packages.states().map { it.engineId })
            try { packages.install(text.engineId); fail("Unsupported engine was installed") }
            catch (_: IllegalStateException) { }
            assertFalse(File(root, text.fileName).exists())
        }
    }
    @Test fun textPresenceDoesNotReportSpeechModelReady() = runBlocking<Unit> {
        withDirectory { root ->
            val packages = AndroidWhisperPackages(root, mapOf(text.engineId to text, speech.engineId to speech),
                { true }, ::connection)
            packages.install(text.engineId)
            assertTrue(packages.hasVerifiedModel(setOf(text.engineId)))
            assertFalse(packages.hasVerifiedModel(setOf(speech.engineId)))
        }
    }
    @Test fun removalWaitsForActiveInferenceAndDoesNotDeleteOtherModel() = runBlocking<Unit> {
        withDirectory { root ->
            val packages = AndroidWhisperPackages(root, mapOf(text.engineId to text, speech.engineId to speech),
                { true }, ::connection)
            packages.install(text.engineId)
            packages.install(speech.engineId)
            val acquired = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val inference = launch {
                packages.withModel(text.engineId) { acquired.complete(Unit); release.await(); assertTrue(it.isFile) }
            }
            acquired.await()
            val deletion = launch(start = CoroutineStart.UNDISPATCHED) { packages.remove(text.engineId) }
            assertFalse(deletion.isCompleted)
            release.complete(Unit)
            inference.join(); deletion.join()
            assertFalse(File(root, text.fileName).exists())
            assertArrayEquals(payload, File(root, speech.fileName).readBytes())
        }
    }
}
