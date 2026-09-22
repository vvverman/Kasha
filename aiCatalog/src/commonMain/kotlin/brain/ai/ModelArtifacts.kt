package brain.ai

import brain.studio.AiSelection

/** Закреплённые кроссплатформенные пакеты локальных моделей. */
data class ModelArtifact(val engineId: String, val fileName: String, val url: String, val sha256: String)

object ModelArtifacts {
    val packages: Map<String, ModelArtifact> = listOf(
        ModelArtifact(
            AiSelection.DEFAULT_STT,
            "ggml-large-v3-turbo-q5_0.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin?download=true",
            "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        ),
        ModelArtifact(
            AiSelection.DEFAULT_TEXT,
            "Kasha-Cleanup-0.6B-Q4_K_M.gguf",
            "https://github.com/vvverman/Kasha/releases/download/kasha-cleanup-0.6b-v1/Kasha-Cleanup-0.6B-Q4_K_M.gguf",
            "8c405ca3d29af67b21d2147e195b62ea63c84b84be1794b8aa453f31115b4f69",
        ),
        ModelArtifact(
            AiSelection.DEFAULT_ROUTING,
            "F2LLM-v2-80M.Q8_0.gguf",
            "https://huggingface.co/mradermacher/F2LLM-v2-80M-GGUF/resolve/main/F2LLM-v2-80M.Q8_0.gguf?download=true",
            "fb2a92e51dba7120d5369704502520814775b27c116d27ed039690106bf224ee",
        ),
    ).associateBy { it.engineId }

    val speech: Map<String, ModelArtifact> = packages.filterKeys { it == AiSelection.DEFAULT_STT }
    val routing: Map<String, ModelArtifact> = packages.filterKeys { it == AiSelection.DEFAULT_ROUTING }

    val text: Map<String, ModelArtifact> = packages.filterKeys { it == AiSelection.DEFAULT_TEXT }
}
