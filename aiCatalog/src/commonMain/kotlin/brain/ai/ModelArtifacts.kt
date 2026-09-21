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
            AiSelection.DEFAULT_ROUTING,
            "F2LLM-v2-80M.Q8_0.gguf",
            "https://huggingface.co/mradermacher/F2LLM-v2-80M-GGUF/resolve/main/F2LLM-v2-80M.Q8_0.gguf?download=true",
            "fb2a92e51dba7120d5369704502520814775b27c116d27ed039690106bf224ee",
        ),
    ).associateBy { it.engineId }

    val speech: Map<String, ModelArtifact> = packages.filterKeys { it == AiSelection.DEFAULT_STT }
    val routing: Map<String, ModelArtifact> = packages.filterKeys { it == AiSelection.DEFAULT_ROUTING }

    /**
     * Transcrib Cleanup 0.6B сейчас опубликован как MLX-пакет из нескольких файлов.
     * Его нельзя выдавать за одиночный GGUF и исполнять текущим llama.cpp adapter.
     * Кроссплатформенный TEXT package добавляется только после проверенного runtime/artifact.
     */
    val text: Map<String, ModelArtifact> = emptyMap()
}
