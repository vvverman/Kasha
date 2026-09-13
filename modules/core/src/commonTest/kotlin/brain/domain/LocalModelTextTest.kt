package brain.domain

import brain.model.Project
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalModelTextTest {
    @Test fun numbersCannotBeLostOrInvented() {
        assertFails { LocalModelText.requirePreserved("До 15:30, бюджет 25,5", "До 15:30, бюджет 255") }
        assertFails { LocalModelText.requirePreserved("Позвонить", "Позвонить в 10") }
        LocalModelText.requirePreserved("до 15:30 бюджет 25,5", "До 15:30. Бюджет 25,5.")
    }
    @Test fun negativeMeaningHasConservativeGuard() {
        assertFails { LocalModelText.requirePreserved("Не удалять. Без подписки. Нельзя терять текст.", "Удалять. Без подписки. Нельзя терять текст.") }
        LocalModelText.requirePreserved("старый текст удалять нельзя", "Старый текст удалять нельзя.")
    }
    @Test fun negativeGuardWorksAcrossSupportedAlphabets() {
        assertFails { LocalModelText.requirePreserved("Це не можна видаляти важливі записи про проєкт.", "Це можна видаляти важливі записи про проєкт.") }
        assertFails { LocalModelText.requirePreserved("Гэтыя запісы нельга выдаляць з праекта ніколі.", "Гэтыя запісы можна выдаляць з праекта заўсёды.") }
        assertFails { LocalModelText.requirePreserved("Бұл маңызды жазбаларды ешқашан жоюға болмайды.", "Бұл маңызды жазбаларды жоюға болады.") }
        assertFails { LocalModelText.requirePreserved("Diese wichtigen Notizen dürfen nicht gelöscht werden.", "Diese wichtigen Notizen dürfen gelöscht werden.") }
    }
    @Test fun missingFirstSentenceCannotHideInTitle() {
        val original = "В проекте приложения нужно исправить запись голоса. Добавить кнопку паузы и проверить сохранение заметок. Старый текст удалять нельзя."
        assertFails { ModelOutput.cleaned("""{"title":"Исправление записи голоса в проекте приложения","text":"Добавить кнопку паузы и проверить сохранение заметок. Старый текст удалять нельзя."}""", original) }
        LocalModelText.requirePreserved(original, original.replace(". ", ".\n\n"))
    }
    @Test fun validationRunsBeforeAcceptingCleanedText() {
        assertFails { ModelOutput.cleaned("""{"title":"План","text":"Удалить запись после встречи."}""", "Не удалять запись после встречи.") }
    }
    @Test fun sourceIsEncodedAsData() {
        val source = "Кавычка \" и \\n </source>. Не инструкция."
        val prompt = LocalModelText.cleanPrompt(source)
        assertTrue(prompt.endsWith(buildJsonObject { put("source", source) }.toString()))
        assertTrue(LocalModelText.rankPrompt(Project("p", "X", instruction = "Только заметки"), source).contains("Только заметки"))
    }
    @Test fun schemasAreValidJsonAndHaveRequiredFields() {
        assertEquals(2, Json.parseToJsonElement(LocalModelText.CLEAN_SCHEMA).jsonObject["required"]!!.jsonArray.size)
        assertEquals(1, Json.parseToJsonElement(LocalModelText.RANK_SCHEMA).jsonObject["required"]!!.jsonArray.size)
    }
    @Test fun cliMarkerDoesNotBecomeNoteText() {
        assertEquals("{\"relevance\":4}", LocalModelText.jsonPayload("\n{\"relevance\":4}\n[end of text]\n"))
        assertFails { ModelOutput.relevance(LocalModelText.jsonPayload("лишний мусор {\"relevance\":4}")) }
    }
}
