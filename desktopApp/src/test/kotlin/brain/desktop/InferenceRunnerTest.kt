package brain.desktop

import brain.runtime.CommandRunner
import brain.runtime.ai.JvmAiRuntimeProbe
import brain.studio.AiRole
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
    @Test fun readinessProbeUsesTheSameCpuConfigurationWithoutLoadingModel() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val runner = DesktopInferenceRunner(true, CommandRunner { command, _ -> calls += command; "version" })
        val probe = JvmAiRuntimeProbe(mapOf("KASHA_WHISPER_CLI" to "/bundle/whisper-cli", "KASHA_LLAMA_CLI" to "/bundle/llama-completion", "KASHA_FFMPEG" to "/bundle/ffmpeg"), runner)
        assertTrue(probe.available(AiRole.SPEECH_TO_TEXT))
        assertTrue(probe.available(AiRole.TEXT))
        assertEquals(listOf("/bundle/whisper-cli", "--version", "--no-gpu"), calls[0])
        assertEquals(listOf("/bundle/ffmpeg", "-version"), calls[1])
        assertEquals(listOf("/bundle/llama-completion", "--version", "--n-gpu-layers", "0", "--device", "none"), calls[2])
        assertTrue(calls.none { "-m" in it || "--model" in it })
    }
}
