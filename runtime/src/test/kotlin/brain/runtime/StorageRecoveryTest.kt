package brain.runtime

import brain.model.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class StorageRecoveryTest {
    private val original = byteArrayOf(1, 2, 3, 4)
    private val finalized = byteArrayOf(5, 6, 7, 8)

    private suspend fun <T> withRoot(block: suspend (Path) -> T): T {
        val root = Files.createTempDirectory("kasha-storage-recovery")
        return try { block(root) } finally { root.toFile().deleteRecursively() }
    }

    private fun open(root: Path) = FileBrainStore(root) { RuntimeStatus() }

    private suspend fun finalizedFixture(root: Path, output: ByteArray?): Capture {
        val store = open(root)
        val capture = store.createCapture("source.wav", original)
        val saved = root.resolve("audio/${capture.id}/saved.m4a")
        if (output != null) Files.write(saved, output)
        // Persisted metadata can outlive the output file after an interrupted publication.
        return store.updateCapture(capture.id) {
            it.copy(status = CaptureStatus.READY, audioFinalized = true,
                audioFileName = "audio/${capture.id}/saved.m4a")
        }
    }

    private suspend fun assertOriginalSurvivesReopen(output: ByteArray?) = withRoot { root ->
        val expected = finalizedFixture(root, output)
        val source = root.resolve("audio/${expected.id}/original.wav")
        val stateBefore = Files.readAllBytes(root.resolve("brain.json"))
        repeat(3) {
            val reopened = open(root)
            assertEquals(expected, reopened.capture(expected.id))
            assertContentEquals(original, Files.readAllBytes(source))
            assertContentEquals(stateBefore, Files.readAllBytes(root.resolve("brain.json")))
            val saved = root.resolve(expected.audioFileName!!)
            if (output == null) assertFalse(Files.exists(saved))
            else assertContentEquals(output, Files.readAllBytes(saved))
        }
    }

    @Test fun finalizedMetadataWithMissingOutputDoesNotDeleteOriginal() = runBlocking {
        assertOriginalSurvivesReopen(null)
    }

    @Test fun finalizedMetadataWithEmptyOutputDoesNotDeleteOriginal() = runBlocking {
        assertOriginalSurvivesReopen(byteArrayOf())
    }

    @Test fun reopeningIsNotAnImplicitRequestToDeleteSurvivingOriginal() = runBlocking {
        assertOriginalSurvivesReopen(finalized)
    }

    @Test fun successfulFinalizationStillPublishesOutputBeforeRemovingOriginal() = runBlocking {
        withRoot { root ->
            val store = open(root)
            val capture = store.createCapture("source.wav", original)
            val source = store.resolveAudio(capture)
            val saved = source.parent.resolve("saved.m4a")
            Files.write(saved, finalized)
            val result = store.finalizeAudio(capture.id, saved, 1.0, listOf(0.2f), 1.5)
            assertTrue(result.audioFinalized)
            assertFalse(Files.exists(source))
            assertContentEquals(finalized, Files.readAllBytes(store.resolveAudio(result)))
            val reopened = open(root)
            assertContentEquals(finalized, Files.readAllBytes(reopened.resolveAudio(reopened.capture(capture.id)!!)))
        }
    }

    @Test fun retainedSourceCanStillBeDeletedByExplicitDiscard() = runBlocking {
        withRoot { root ->
            val capture = finalizedFixture(root, null)
            val reopened = open(root)
            assertTrue(Files.exists(root.resolve("audio/${capture.id}/original.wav")))
            reopened.discard(capture.id)
            assertNull(reopened.capture(capture.id))
            assertFalse(Files.exists(root.resolve("audio/${capture.id}")))
            assertTrue(open(root).snapshot().captures.isEmpty())
        }
    }

    @Test fun corruptStateIsPreservedAndCanBeRetriedAfterRepair() = runBlocking {
        withRoot { root ->
            val capture = finalizedFixture(root, null)
            val state = root.resolve("brain.json")
            val valid = Files.readAllBytes(state)
            val corrupt = "{broken state".toByteArray()
            Files.write(state, corrupt)
            repeat(2) {
                assertFails { open(root) }
                assertContentEquals(corrupt, Files.readAllBytes(state))
                assertContentEquals(original, Files.readAllBytes(root.resolve("audio/${capture.id}/original.wav")))
            }
            Files.write(state, valid)
            repeat(2) { assertEquals(capture, open(root).capture(capture.id)) }
            assertContentEquals(original, Files.readAllBytes(root.resolve("audio/${capture.id}/original.wav")))
        }
    }

    @Test fun corruptPreferencesBlockJournalReconcileAndCannotBeOverwritten() = runBlocking {
        withRoot { root ->
            val capture = finalizedFixture(root, null)
            val directory = root.resolve("audio/${capture.id}")
            val journal = root.resolve("audio/.deleted-${capture.id}")
            Files.move(directory, journal)
            val preferences = PreferenceStore(root)
            val expected = Preferences(autoRecord = false, savedSpeed = 1.5, language = "ru")
            preferences.save(expected)
            val file = root.resolve("preferences.json")
            val valid = Files.readAllBytes(file)
            val corrupt = "{broken preferences".toByteArray()
            Files.write(file, corrupt)
            val state = Files.readAllBytes(root.resolve("brain.json"))
            repeat(2) {
                assertFails { open(root) }
                assertFails { preferences.read() }
                assertFails { preferences.save(Preferences()) }
                assertContentEquals(corrupt, Files.readAllBytes(file))
                assertContentEquals(state, Files.readAllBytes(root.resolve("brain.json")))
                assertContentEquals(original, Files.readAllBytes(journal.resolve("original.wav")))
                assertFalse(Files.exists(directory))
            }
            Files.write(file, valid)
            assertEquals(expected, preferences.read())
            repeat(2) {
                assertEquals(capture, open(root).capture(capture.id))
                assertContentEquals(original, Files.readAllBytes(directory.resolve("original.wav")))
            }
            assertFalse(Files.exists(journal))
        }
    }

    @Test fun missingStateBesideAudioIsNotAnEmptyDatabase() = runBlocking {
        withRoot { root ->
            val capture = finalizedFixture(root, null)
            val state = root.resolve("brain.json")
            val valid = Files.readAllBytes(state)
            Files.delete(state)
            repeat(2) {
                assertFails { open(root) }
                assertFalse(Files.exists(state))
                assertContentEquals(original, Files.readAllBytes(root.resolve("audio/${capture.id}/original.wav")))
            }
            Files.write(state, valid)
            assertEquals(capture, open(root).capture(capture.id))
        }
    }

    @Test fun missingIndexWithInterruptedWriteIsNotAFirstLaunch() = runBlocking {
        withRoot { root ->
            val pending = root.resolve(".save-interrupted.tmp")
            val bytes = "{interrupted state".toByteArray()
            Files.write(pending, bytes)
            repeat(2) {
                assertFails { open(root) }
                assertContentEquals(bytes, Files.readAllBytes(pending))
                assertFalse(Files.exists(root.resolve("brain.json")))
            }
        }
    }

    @Test fun confirmedMissingFilesInFreshDirectoryAreAllowed() = runBlocking {
        withRoot { root ->
            repeat(2) {
                assertTrue(open(root).snapshot().captures.isEmpty())
                assertTrue(open(root).snapshot().projects.isEmpty())
                assertEquals(Preferences(), PreferenceStore(root).read())
            }
        }
    }

    @Test fun temporaryPreferencesReadFailureIsNotCachedOrOverwritten() = runBlocking {
        withRoot { root ->
            val store = PreferenceStore(root)
            val expected = Preferences(autoRecord = false, savedSpeed = 2.0, language = "ru")
            store.save(expected)
            val file = root.resolve("preferences.json")
            val backup = root.resolve("preferences.backup")
            Files.move(file, backup)
            // A real filesystem error, not an absent-file result or a mocked empty preference set.
            Files.createDirectory(file)
            Files.writeString(file.resolve("unavailable"), "original storage is temporarily unavailable")
            repeat(2) {
                assertFails { store.read() }
                assertFails { store.save(Preferences()) }
                assertTrue(Files.isDirectory(file))
            }
            file.toFile().deleteRecursively()
            Files.move(backup, file)
            repeat(2) { assertEquals(expected, store.read()) }
            store.save(expected.copy(savedSpeed = 1.0))
            assertEquals(1.0, PreferenceStore(root).read().savedSpeed)
        }
    }
}
