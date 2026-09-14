package brain.desktop

import brain.model.*
import brain.runtime.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class DesktopAudioLifecycleTest {
    @Test fun missingCaptureDoesNotLeavePlayerLoading() = runBlocking {
        val root=Files.createTempDirectory("kasha-playback-missing")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val audio=DesktopAudio(FileBrainStore(root){RuntimeStatus()},"not-used",root,scope)
        try{
            assertFailsWith<IllegalStateException>{audio.playCapture("missing",false,0.0,1.0)}
            assertEquals("idle",audio.telemetry().phase)
        }finally{audio.stop();scope.cancel();root.toFile().deleteRecursively()}
    }

    @Test fun closedScopeDoesNotAcquirePlaybackResources() = runBlocking {
        val root=Files.createTempDirectory("kasha-playback-closed")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val audio=DesktopAudio(FileBrainStore(root){RuntimeStatus()},"not-used",root,scope)
        scope.cancel()
        try{
            assertFailsWith<CancellationException>{audio.playCapture("unused",false,0.0,1.0)}
            assertEquals("idle",audio.telemetry().phase)
            assertFalse(Files.list(root).use{files->files.anyMatch{it.fileName.toString().startsWith(".playback-")}})
        }finally{audio.stop();root.toFile().deleteRecursively()}
    }
}
