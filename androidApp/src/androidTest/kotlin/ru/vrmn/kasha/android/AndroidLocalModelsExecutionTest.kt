package ru.vrmn.kasha.android

import androidx.test.platform.app.InstrumentationRegistry
import brain.ai.ModelArtifacts
import brain.domain.LocalModelText
import brain.model.*
import brain.studio.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/** Настоящие JNI-движки и системный декодер Android, не mock и не demo. */
class AndroidLocalModelsExecutionTest {
    @Test fun voiceBecomesNoteUsingSelectedNativeModelsWithoutNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "ai-fixtures")
        val evidence = File(input, "android-local-models.json")
        evidence.delete()
        var networkReachable = false
        try { Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 1500); networkReachable = true } }
        catch (_: java.io.IOException) { }
        assertFalse("Внешняя сеть должна быть выключена до запуска inference", networkReachable)
        assertTrue("Whisper JNI missing", AndroidWhisperNative.available)
        assertTrue("Qwen JNI missing", AndroidLlamaNative.available)
        val selection = AiSelection()
        val preferences = Preferences(ai = selection, language = "ru")
        val models = File(context.filesDir, "Kasha/models").also { it.mkdirs() }
        for (id in setOf(selection.speechToText, selection.text)) {
            val artifact = ModelArtifacts.packages.getValue(id)
            val file = File(input, artifact.fileName)
            check(file.isFile && file.canRead()) { "Missing pinned model fixture: ${file.absolutePath}; visible=${input.list()?.joinToString()}" }
            file.copyTo(File(models, artifact.fileName), overwrite = true)
        }
        val audio = File(input, "russian-with-pauses.wav")
        val originalHash = hash(audio)
        val intelligence = AndroidIntelligence(context) { preferences }
        assertTrue(intelligence.roles(selection).all { it.executable })
        val transcript = intelligence.transcribe(audio.absolutePath, "ru", "")
        assertTrue(transcript, transcript.any { it in 'А'..'я' })
        assertFalse(intelligence.simulated)
        val source = "Ирина не меняла 1200 пунктов."
        assertTrue(intelligence.title(source, "ru").isNotBlank())
        val tidied = intelligence.tidy(source, "ru")
        LocalModelText.requirePreserved(source, tidied)
        val projects = listOf(Project("work", "Работа", instruction = "Рабочие встречи"))
        val rank = intelligence.rank(source, projects, "ru")
        assertEquals(setOf("work"), rank.keys)
        assertTrue(rank.getValue("work") in 0..4)
        val root = File(context.cacheDir, "ai-voice-integration").also { it.deleteRecursively(); it.mkdirs() }
        try {
            val repository = AndroidStudioRepository(root, intelligence, "ru", defaultPreferences = preferences)
            val pending = repository.newPendingFile()
            audio.copyTo(pending, overwrite = true)
            val capture = repository.acceptPending(pending, 20.0, listOf(0.1f))
            val ready = repository.reprocess(capture.id)
            assertEquals(CaptureStatus.READY, ready.status)
            assertTrue(ready.transcript.any { it in 'А'..'я' }); assertFalse(ready.simulated)
            val project = repository.createProject(ProjectDraft(title = "Работа"))
            val note = repository.distribute(ready.id, DistributionRequest(projectId = project.id))
            assertEquals(ready.textToSave.trimEnd(), note.body)
            val reopened = AndroidStudioRepository(root, intelligence, "ru", defaultPreferences = preferences)
            assertEquals(note, reopened.snapshot().notes.single())
            assertEquals(originalHash, hash(audio))
            assertEquals(originalHash, hash(repository.audioFile(capture.id)))
            assertEquals(selection, repository.preferences().ai)
            evidence.writeText("""{"passed":true,"nativeWhisper":true,"nativeQwen":true,"routerRoles":3,"voiceSavedAsNote":true,"restartPreserved":true,"sourceUnchanged":true,"externalNetworkReachable":false,"fixture":"synthetic-russian-speech"}""")
        } finally { root.deleteRecursively() }
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
