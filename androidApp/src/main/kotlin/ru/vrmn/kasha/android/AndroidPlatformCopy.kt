package ru.vrmn.kasha.android

/** Только тексты Android-окон и уведомлений; продуктовый UI остаётся общим. */
internal data class AndroidPlatformCopy(
    val microphone: String, val notifications: String, val allow: String,
    val settings: String, val playback: String, val reminders: String,
) {
    companion object {
        fun forLanguage(language: String): AndroidPlatformCopy = when (language) {
            "ru" -> AndroidPlatformCopy("Микрофон нужен для голосовых заметок. Аудио сохраняется на этом устройстве.", "Разрешите уведомления для напоминаний о задачах.", "Разрешить", "Открыть настройки", "Воспроизведение аудио", "Напоминания о задачах")
            "uk" -> AndroidPlatformCopy("Мікрофон потрібен для голосових нотаток. Аудіо зберігається на цьому пристрої.", "Дозвольте сповіщення для нагадувань про завдання.", "Дозволити", "Відкрити налаштування", "Відтворення аудіо", "Нагадування про завдання")
            "be" -> AndroidPlatformCopy("Мікрафон патрэбны для галасавых нататак. Аўдыя захоўваецца на гэтай прыладзе.", "Дазвольце апавяшчэнні для напамінаў пра задачы.", "Дазволіць", "Адкрыць налады", "Прайграванне аўдыя", "Напаміны пра задачы")
            "kk" -> AndroidPlatformCopy("Микрофон дауыстық жазбалар үшін қажет. Аудио осы құрылғыда сақталады.", "Тапсырма еске салғыштары үшін хабарландыруларға рұқсат беріңіз.", "Рұқсат беру", "Параметрлерді ашу", "Аудионы ойнату", "Тапсырма еске салғыштары")
            "de" -> AndroidPlatformCopy("Das Mikrofon wird für Sprachnotizen benötigt. Audio wird auf diesem Gerät gespeichert.", "Erlauben Sie Benachrichtigungen für Aufgabenerinnerungen.", "Erlauben", "Einstellungen öffnen", "Audiowiedergabe", "Aufgabenerinnerungen")
            "fr" -> AndroidPlatformCopy("Le microphone est nécessaire pour les notes vocales. Le son est enregistré sur cet appareil.", "Autorisez les notifications pour les rappels de tâches.", "Autoriser", "Ouvrir les réglages", "Lecture audio", "Rappels de tâches")
            "es" -> AndroidPlatformCopy("El micrófono es necesario para notas de voz. El audio se guarda en este dispositivo.", "Permita las notificaciones para los recordatorios de tareas.", "Permitir", "Abrir ajustes", "Reproducción de audio", "Recordatorios de tareas")
            else -> AndroidPlatformCopy("The microphone is needed for voice notes. Audio is saved on this device.", "Allow notifications for task reminders.", "Allow", "Open settings", "Audio playback", "Task reminders")
        }
    }
}
