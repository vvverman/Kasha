package brain.ai

import brain.studio.*

/** Реальные встроенные обработчики. Это не загруженные Whisper/Qwen-модели. */
object BuiltInAi {
    const val APPLE_SPEECH = "native.apple.speech"
    const val LOCAL_RULES = "native.kasha.rules"

    val engines: List<AiEngineDescriptor> = listOf(
        AiEngineDescriptor(
            id = APPLE_SPEECH, name = "Apple Speech", provider = "Apple",
            roles = setOf(AiRole.SPEECH_TO_TEXT), locality = AiLocality.NATIVE,
            version = "system", languages = emptyList(), installable = false,
            description = "Системное распознавание на устройстве; доступность зависит от языка и ОС",
        ),
        AiEngineDescriptor(
            id = LOCAL_RULES, name = "Локальные правила", provider = "Kasha",
            roles = setOf(AiRole.TEXT, AiRole.ROUTING), locality = AiLocality.NATIVE,
            version = "1", languages = Languages.codes, installable = false,
            description = "Базовая обработка текста и сопоставление слов, без языковой модели",
        ),
    )

    fun appleSelection() = AiSelection(speechToText = APPLE_SPEECH, text = LOCAL_RULES, routing = LOCAL_RULES)

    fun supportsApple(role: AiRole, engineId: String): Boolean = when (role) {
        AiRole.SPEECH_TO_TEXT -> engineId == APPLE_SPEECH
        AiRole.TEXT, AiRole.ROUTING -> engineId == LOCAL_RULES
    }

    fun requireApple(role: AiRole, engineId: String) {
        check(supportsApple(role, engineId)) { "aiUnavailable" }
    }
}
