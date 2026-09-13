package brain.desktop

import brain.runtime.CommandRunner
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class InferenceRunnerTest {
    private fun capture(result: MutableList<String>) = CommandRunner { command, _ -> result.addAll(command); "result" }
    @Test fun regularLaunchPreservesGpuChoice() = runBlocking {
        val output = mutableListOf<String>(); val command = listOf("/bundle/bin/llama-completion", "-m", "/bundle/models/original.gguf")
        assertEquals("result", DesktopInferenceRunner(false, capture(output)).run(command, 2)); assertEquals(command, output)
    }
    @Test fun cpuWhisperKeepsOriginalModel() = runBlocking {
        val output = mutableListOf<String>(); val command = listOf("/bundle/bin/whisper-cli", "-m", "/bundle/models/original.bin")
        DesktopInferenceRunner(true, capture(output)).run(command, 2)
        assertEquals(command + "--no-gpu", output)
    }
    @Test fun cpuLlamaKeepsOriginalModel() = runBlocking {
        val output = mutableListOf<String>(); val command = listOf("/bundle/bin/llama-completion", "-m", "/bundle/models/original.gguf")
        DesktopInferenceRunner(true, capture(output)).run(command, 2)
        assertEquals(command + listOf("--n-gpu-layers", "0", "--device", "none"), output)
    }
    @Test fun audioConversionIsUnchanged() = runBlocking {
        val output = mutableListOf<String>(); val command = listOf("/bundle/bin/ffmpeg", "-version")
        DesktopInferenceRunner(true, capture(output)).run(command, 2); assertEquals(command, output)
    }
}
