package brain.ai

import brain.studio.*

/** Реальные встроенные обработчики. Это не загруженные Whisper/Qwen-модели. */
object BuiltInAi {
    const val APPLE_SPEECH = "native.apple.speech"
    const val ANDROID_SPEECH = "native.android.speech"
    const val LOCAL_RULES = "native.kasha.rules"

    val engines: List<AiEngineDescriptor> = listOf(
        AiEngineDescriptor(
            id = APPLE_SPEECH, name = "Apple Speech", provider = "Apple",
            roles = setOf(AiRole.SPEECH_TO_TEXT), locality = AiLocality.NATIVE,
            version = "system", languages = emptyList(), installable = false,
            description = "Системное распознавание на устройстве; доступность зависит от языка и ОС",
        ),
        AiEngineDescriptor(
            id = ANDROID_SPEECH, name = "Android On-device Speech", provider = "Android",
            roles = setOf(AiRole.SPEECH_TO_TEXT), locality = AiLocality.NATIVE,
            version = "system", languages = emptyList(), installable = false,
            description = "Распознавание сохранённого файла на устройстве: Android 13+ и установленный языковой пакет",
        ),
        AiEngineDescriptor(
            id = LOCAL_RULES, name = "Локальные правила", provider = "Kasha",
            roles = setOf(AiRole.TEXT, AiRole.ROUTING), locality = AiLocality.NATIVE,
            version = "1", languages = Languages.codes, installable = false,
            description = "Базовая обработка текста и сопоставление слов, без языковой модели",
        ),
    )

    fun appleSelection() = AiSelection(speechToText = APPLE_SPEECH, text = LOCAL_RULES, routing = LOCAL_RULES)

    fun androidSelection() = AiSelection(speechToText = ANDROID_SPEECH, text = LOCAL_RULES, routing = LOCAL_RULES)

    fun supportsApple(role: AiRole, engineId: String) = supports(role, engineId, APPLE_SPEECH)
    fun supportsAndroid(role: AiRole, engineId: String) = supports(role, engineId, ANDROID_SPEECH)

    private fun supports(role: AiRole, engineId: String, speechId: String): Boolean = when (role) {
        AiRole.SPEECH_TO_TEXT -> engineId == speechId
        AiRole.TEXT, AiRole.ROUTING -> engineId == LOCAL_RULES
    }

    fun requireApple(role: AiRole, engineId: String) {
        check(supportsApple(role, engineId)) { "aiUnavailable" }
    }
}
