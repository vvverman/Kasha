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

class AndroidWhisperPackagesTest {
    private val payload = "Проверяемый пакет".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(payload)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val artifact = ModelArtifact("test-model", "model.bin", "https://model.invalid/model.bin", hash)
    private fun connection(bytes: ByteArray) = object : HttpURLConnection(URL(artifact.url)) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getContentLengthLong() = bytes.size.toLong()
        override fun getInputStream() = ByteArrayInputStream(bytes)
    }
    private fun gateway(root: File, data: ByteArray = payload) = AndroidWhisperPackages(
        root, mapOf(artifact.engineId to artifact), { true }, { connection(data) })

    @Test fun verifiesDownloadBeforePublishingAndDoesNotDownloadTwice() = runBlocking {
        val root = Files.createTempDirectory("kasha-model-test").toFile()
        try {
            var calls = 0
            val packages = AndroidWhisperPackages(root, mapOf(artifact.engineId to artifact), { true }, { calls++; connection(payload) })
            packages.install(artifact.engineId)
            packages.install(artifact.engineId)
            assertEquals(1, calls)
            assertTrue(packages.states().single().installed)
            packages.withModel(artifact.engineId) { assertArrayEquals(payload, it.readBytes()) }
            assertFalse(File(root, ".model.bin.part").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun checksumMismatchDoesNotPublishOrTouchOtherFiles() = runBlocking {
        val root = Files.createTempDirectory("kasha-model-test").toFile()
        try {
            val audio = File(root, "recording.m4a").apply { writeText("keep") }
            val packages = gateway(root, "invalid".toByteArray())
            try { packages.install(artifact.engineId); fail("Expected checksum failure") } catch (_: IllegalStateException) {}
            assertFalse(packages.states().single().installed)
            assertFalse(File(root, "model.bin").exists())
            assertFalse(File(root, ".model.bin.part").exists())
            assertEquals("keep", audio.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun reopensAndChecksStoredModelWithoutTrustingMarker() = runBlocking {
        val root = Files.createTempDirectory("kasha-model-test").toFile()
        try {
            gateway(root).install(artifact.engineId)
            assertTrue(gateway(root).states().single().installed)
            File(root, "model.bin").writeText("corrupted")
            File(root, "model.bin.sha256").writeText(hash)
            assertFalse(gateway(root).states().single().installed)
        } finally { root.deleteRecursively() }
    }

    @Test fun cannotUseOrInstallUnregisteredEngine() = runBlocking {
        val root = Files.createTempDirectory("kasha-model-test").toFile()
        try {
            val packages = gateway(root)
            try { packages.install("other"); fail("Expected unsupported model") } catch (_: IllegalStateException) {}
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun removeWaitsForActiveInferenceAndKeepsOtherFiles() = runBlocking {
        val root = Files.createTempDirectory("kasha-model-test").toFile()
        try {
            val packages = gateway(root)
            packages.install(artifact.engineId)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val using = async { packages.withModel(artifact.engineId) { entered.complete(Unit); release.await(); assertTrue(it.exists()) } }
            entered.await()
            val removing = async { packages.remove(artifact.engineId) }
            yield()
            assertFalse(removing.isCompleted)
            release.complete(Unit); using.await(); removing.await()
            assertFalse(packages.states().single().installed)
        } finally { root.deleteRecursively() }
    }
}
