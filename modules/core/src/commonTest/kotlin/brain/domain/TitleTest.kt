package brain.domain

import kotlin.test.*

class TitleTest {
    @Test fun unrelatedBoilerplateTitleFallsBackToSource() {
        val original = "В проекте приложения нужно исправить запись голоса."
        val result = ModelOutput.cleaned("""{"title":"дополнительные метаданные","text":"В проекте приложения нужно исправить запись голоса."}""", original)
        assertEquals(original, result.title)
        assertEquals("Исправление записи голоса", LocalModelText.safeTitle("Исправление записи голоса", original))
    }
}
