package brain.runtime

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CommandEnvironmentTest {
    @Test fun nativeCpuOverrideIsConfinedToChildAndKeepsOfflinePolicy() = runBlocking {
        val root = Files.createTempDirectory("kasha-child-env-")
        val before = System.getenv("GGML_METAL_DEVICES")
        try {
            val source = root.resolve("KashaChildEnvironment.java")
            Files.writeString(source, """
                class KashaChildEnvironment {
                    public static void main(String[] args) {
                        System.out.print(System.getenv("GGML_METAL_DEVICES") + ":" +
                            System.getenv("HF_HUB_OFFLINE") + ":" + System.getenv("LLAMA_ARG_RPC"));
                    }
                }
            """.trimIndent())
            val name = if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java"
            val java = Path.of(System.getProperty("java.home"), "bin", name).toString()
            val runner = JvmCommandRunner(mapOf("GGML_METAL_DEVICES" to "0", "HF_HUB_OFFLINE" to "0", "LLAMA_ARG_RPC" to "forbidden"))
            assertEquals("0:1:null", runner.run(listOf(java, source.toString()), 30))
            assertEquals(before, System.getenv("GGML_METAL_DEVICES"))
        } finally { root.toFile().deleteRecursively() }
    }
}
