package brain.studio

import brain.domain.BrainRepository
import brain.model.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
data class Preferences(
    val autoRecord: Boolean = true,
    val autoRoute: Boolean = false,
    val quality: Int = 0,
    val savedSpeed: Double = 1.5,
    val language: String = "system",
    val theme: String = "system",
    val demoExample: String = "idea",
    val projectSort: SortMode = SortMode.ALPHABETICAL,
    val noteSort: SortMode = SortMode.UPDATED,
    val taskSort: SortMode = SortMode.UPDATED,
    val ai: AiSelection = AiSelection(),
) {
    fun validated(): Preferences {
        require(quality in 0..3 && savedSpeed.isFinite() && savedSpeed in 1.0..2.0)
        require(language == "system" || language in Languages.codes)
        require(theme in listOf("system", "light", "dark"))
        require(demoExample in listOf("idea", "password", "cooking"))
        ai.validated()
        return this
    }
    val bitrate: Int get() = listOf(32, 48, 64, 96)[quality]
}

object Languages {
    val codes = listOf("ru", "en", "es", "fr", "de", "uk", "be", "kk")
    val names = listOf("Русский", "English", "Español", "Français", "Deutsch", "Українська", "Беларуская", "Қазақша")
    fun resolve(selected: String, system: String): String =
        (if (selected == "system") system.substringBefore('-').substringBefore('_').lowercase() else selected)
            .takeIf { it in codes } ?: "en"
}

/** Расширение портов продуктового приложения. Реализация всегда приходит от платформы. */
interface StudioRepository : BrainRepository {
    val simulated: Boolean
    suspend fun preferences(): Preferences
    suspend fun savePreferences(value: Preferences)
    suspend fun tidy(id: String): Capture
    suspend fun rank(id: String): Capture
    suspend fun discard(id: String)
    suspend fun createDemo(): Capture
}

/**
 * Legacy facade поверх независимых ролей AI. Новые реализации должны собираться через
 * CompositeIntelligence/SpeechToTextEngine/TextProcessingEngine/RoutingEngine.
 */
interface Intelligence {
    val simulated: Boolean
    suspend fun transcribe(file: String, language: String, example: String): String
    suspend fun title(text: String, language: String): String
    suspend fun tidy(text: String, language: String): String
    suspend fun rank(text: String, projects: List<Project>, language: String): Map<String, Int>
}

class DemoIntelligence(private val latencyMillis: Long = 450) : Intelligence {
    override val simulated = true
    override suspend fun transcribe(file: String, language: String, example: String): String {
        delay(latencyMillis)
        val index = Languages.codes.indexOf(language).coerceAtLeast(0)
        return when (example) {
            "password" -> listOf(
                "Пароль учебного аккаунта: DEMO-123. Это пример, не настоящий пароль.",
                "Training account password: DEMO-123. This is an example, not a real password.",
                "Contraseña de la cuenta de prueba: DEMO-123. Es un ejemplo, no una contraseña real.",
                "Mot de passe du compte de test : DEMO-123. Ceci est un exemple, pas un vrai mot de passe.",
                "Passwort des Testkontos: DEMO-123. Dies ist ein Beispiel, kein echtes Passwort.",
                "Пароль навчального облікового запису: DEMO-123. Це приклад, не справжній пароль.",
                "Пароль навучальнага акаўнта: DEMO-123. Гэта прыклад, не сапраўдны пароль.",
                "Сынақ есептік жазбасының құпиясөзі: DEMO-123. Бұл мысал, нақты құпиясөз емес."
            )[index]
            "cooking" -> listOf(
                "Рецепт ужина. Приготовить овощи с рисом. Добавить соус, но не добавлять острый перец.",
                "Dinner recipe. Cook vegetables with rice. Add sauce, but do not add hot pepper.",
                "Receta para la cena. Cocinar verduras con arroz. Añadir salsa, pero no picante.",
                "Recette du dîner. Préparer des légumes avec du riz. Ajouter de la sauce, sans piment.",
                "Rezept fürs Abendessen. Gemüse mit Reis kochen. Soße hinzufügen, aber keine scharfen Chilis.",
                "Рецепт вечері. Приготувати овочі з рисом. Додати соус, але не додавати гострий перець.",
                "Рэцэпт вячэры. Прыгатаваць гародніну з рысам. Дадаць соус, але не дадаваць востры перац.",
                "Кешкі ас рецепті. Күрішпен көкөніс пісіру. Тұздық қосу, бірақ ащы бұрыш қоспау."
            )[index]
            else -> listOf(
                "В приложении какая-то фигня с записью голоса. Нужно добавить кнопку паузы и проверить сохранение заметок. Старый текст удалять нельзя.",
                "There is some crap going on with voice recording in the app. Add a pause button and check that notes are saved. Do not delete the old text.",
                "La grabación de voz de la aplicación es un desastre. Añadir un botón de pausa y comprobar que se guardan las notas. No borrar el texto anterior.",
                "L’enregistrement vocal de l’application est en bazar. Ajouter un bouton pause et vérifier la sauvegarde des notes. Ne pas supprimer l’ancien texte.",
                "Die Sprachaufnahme in der App ist Mist. Eine Pausentaste hinzufügen und das Speichern der Notizen prüfen. Den alten Text nicht löschen.",
                "У застосунку якась фігня із записом голосу. Треба додати кнопку паузи та перевірити збереження нотаток. Старий текст видаляти не можна.",
                "У праграме нейкая фігня з запісам голасу. Трэба дадаць кнопку паўзы і праверыць захаванне нататак. Стары тэкст выдаляць нельга.",
                "Қолданбада дауысты жазу дұрыс істемейді. Кідірту түймесін қосып, жазбалардың сақталуын тексеру керек. Ескі мәтінді жоюға болмайды."
            )[index]
        }
    }

    override suspend fun title(text: String, language: String): String {
        delay(latencyMillis / 2)
        return text.lineSequence().firstOrNull { it.isNotBlank() }?.substringBefore('.').orEmpty().trim().take(64)
    }

    override suspend fun tidy(text: String, language: String): String {
        delay(latencyMillis)
        val replacements = mapOf(
            "какая-то фигня" to "проблема", "эта хрень" to "эта функция", "фигня" to "проблема",
            "хрень" to "проблема", "блять" to "", "бля" to "", "сука" to "", "пиздец" to "серьёзная проблема",
            "crap" to "a problem", "en bazar" to "défectueux", "Mist" to "fehlerhaft", "фігня" to "праблема",
        )
        var result = text
        replacements.forEach { (from, to) -> result = result.replace(Regex("(?i)(?<![\\p{L}])" + Regex.escape(from) + "(?![\\p{L}])"), to) }
        return result.replace(Regex("[ \\t]{2,}"), " ").replace(Regex("([.!?]) +(?=[\\p{L}])"), "$1\n\n").trim()
    }

    override suspend fun rank(text: String, projects: List<Project>, language: String): Map<String, Int> {
        delay(latencyMillis / 2)
        fun words(value: String) = Regex("[\\p{L}\\p{N}]{3,}").findAll(value.lowercase()).map { it.value.take(5) }.toSet()
        val tokens = words(text)
        return projects.associate { p ->
            val titleHits = words(p.title).count { it in tokens }
            val detailsHits = words(p.instruction + " " + p.description).count { it in tokens }
            p.id to (titleHits * 3 + detailsHits).coerceIn(0, 4)
        }
    }
}

@Serializable data class AudioTelemetry(val phase: String = "idle", val position: Double = 0.0, val duration: Double = 0.0, val level: Float = 0f)

object SignalLevel {
    fun pcm16(bytes: ByteArray, count: Int = bytes.size): Float {
        if (count < 2) return 0f
        var sum = 0.0
        for (i in 0 until (count.coerceAtMost(bytes.size) / 2 * 2) step 2) {
            val v = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toDouble() / 32768.0
            sum += v * v
        }
        return (kotlin.math.sqrt(sum / (count / 2)) * 5).toFloat().coerceIn(0f, 1f)
    }
}
