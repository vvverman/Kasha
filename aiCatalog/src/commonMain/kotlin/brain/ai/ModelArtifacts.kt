package brain.ai

import brain.studio.AiSelection

/** Закреплённые пакеты уже выбранных моделей; общий источник для Desktop и Android. */
data class ModelArtifact(val engineId: String, val fileName: String, val url: String, val sha256: String)

object ModelArtifacts {
    val packages: Map<String, ModelArtifact> = listOf(
        ModelArtifact(AiSelection.DEFAULT_STT, "ggml-small.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin?download=true",
            "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b"),
        ModelArtifact("local.whisper.medium", "ggml-medium.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin?download=true",
            "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208"),
        ModelArtifact("local.whisper.large-v3", "ggml-large-v3.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3.bin?download=true",
            "64d182b440b98d5203c4f9bd541544d84c605196c4f7b845dfa11fb23594d1e2"),
        ModelArtifact(AiSelection.DEFAULT_TEXT, "Qwen3-4B-Q4_K_M.gguf",
            "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf?download=true",
            "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5"),
        ModelArtifact("local.qwen.8b", "Qwen3-8B-Q4_K_M.gguf",
            "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf?download=true",
            "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785"),
    ).associateBy { it.engineId }
    val speech: Map<String, ModelArtifact> = packages.filterKeys {
        it == AiSelection.DEFAULT_STT || it == "local.whisper.medium" || it == "local.whisper.large-v3"
    }
    val text: Map<String, ModelArtifact> = packages.filterKeys {
        it == AiSelection.DEFAULT_TEXT || it == "local.qwen.8b"
    }
}
