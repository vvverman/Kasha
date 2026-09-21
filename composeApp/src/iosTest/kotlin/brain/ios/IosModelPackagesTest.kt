@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package brain.ios

import brain.ai.ModelArtifact
import brain.studio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import platform.Foundation.*
import kotlin.test.*

class IosModelPackagesTest {
    private val id = "test.model"
    private val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private fun root() = IosPaths.directory(IosPaths.child(NSTemporaryDirectory(), "model-test-${NSUUID().UUIDString}"))
    private fun packages(root: String, body: suspend (String, String) -> Unit = { _, path -> IosPaths.write(path, "abc") },
        selected: () -> AiSelection = { AiSelection() }, url: String = "https://example.test/model") =
        IosModelPackages(root, mapOf(id to ModelArtifact(id, "model.bin", url, hash)), selected, body)

    @Test fun verifiedDownloadIsPublishedAndReusedAfterRestart() = runTest {
        val root = root(); var downloads = 0
        try {
            val first = packages(root, { _, file -> downloads++; IosPaths.write(file, "abc") })
            first.install(id); first.install(id)
            assertEquals(1, downloads)
            assertTrue(first.states().single().installed)
            assertTrue(packages(root).states().single().installed)
            assertEquals("abc", first.withModel(id) { IosPaths.read(it) })
        } finally { IosPaths.remove(root) }
    }
    @Test fun corruptDownloadNeverBecomesInstalledAndRetryWorks() = runTest {
        val root = root(); var corrupt = true
        try {
            val manager = packages(root, { _, file -> IosPaths.write(file, if (corrupt) "bad" else "abc") })
            assertFails { manager.install(id) }
            assertFalse(manager.states().single().installed)
            assertFalse(IosPaths.exists(IosPaths.child(root, "model.bin")))
            assertTrue(IosPaths.list(root).isEmpty())
            corrupt = false; manager.install(id)
            assertTrue(manager.states().single().installed)
        } finally { IosPaths.remove(root) }
    }
    @Test fun cancellationRemovesPartAndDoesNotPublishSuccess() = runTest {
        val root = root()
        try {
            val manager = packages(root, { _, file -> IosPaths.write(file, "ab"); throw CancellationException("test") })
            assertFailsWith<CancellationException> { manager.install(id) }
            assertFalse(manager.states().single().installed)
            assertTrue(IosPaths.list(root).isEmpty())
        } finally { IosPaths.remove(root) }
    }
    @Test fun failedWriteCanBeRetriedWithoutTouchingOtherFiles() = runTest {
        val root = root(); var fail = true
        try {
            val note = IosPaths.child(root, "unrelated.txt"); IosPaths.write(note, "original")
            val manager = packages(root, { _, file -> if (fail) error("diskFull") else IosPaths.write(file, "abc") })
            assertFails { manager.install(id) }; fail = false
            manager.install(id)
            assertEquals("original", IosPaths.read(note))
            assertTrue(manager.states().single().installed)
        } finally { IosPaths.remove(root) }
    }
    @Test fun httpNeverCallsDownloader() = runTest {
        val root = root(); var called = false
        try {
            assertFails { packages(root, { _, _ -> called = true }, url = "http://example.test/model").install(id) }
            assertFalse(called)
        } finally { IosPaths.remove(root) }
    }
    @Test fun changedFileIsNotTrustedByNewManager() = runTest {
        val root = root()
        try {
            packages(root).install(id)
            IosPaths.write(IosPaths.child(root, "model.bin"), "changed")
            assertFalse(packages(root).states().single().installed)
            assertFails { packages(root).withModel(id) { error("must not run") } }
        } finally { IosPaths.remove(root) }
    }
    @Test fun selectedModelCannotBeRemoved() = runTest {
        val root = root()
        try {
            val manager = packages(root, selected = { AiSelection(text = id) }); manager.install(id)
            assertFails { manager.remove(id) }
            assertTrue(manager.states().single().installed)
        } finally { IosPaths.remove(root) }
    }
    @Test fun removalWaitsForActiveInference() = runTest {
        val root = root()
        try {
            val manager = packages(root); manager.install(id)
            val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val inference = launch { manager.withModel(id) { started.complete(Unit); finish.await(); assertTrue(IosPaths.exists(it)) } }
            started.await()
            val removal = launch { manager.remove(id) }
            yield(); assertFalse(removal.isCompleted)
            finish.complete(Unit); inference.join(); removal.join()
            assertFalse(manager.states().single().installed)
        } finally { IosPaths.remove(root) }
    }
}
