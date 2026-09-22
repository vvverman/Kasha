package brain.ai

import brain.studio.AiRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocalCleanupPartsTest {
    @Test fun everyPartIsGeneratedInOrderAndParagraphsStayInPlace() = runTest {
        val source = "Проверить 12 файлов.\n\nСтарый текст удалять нельзя."
        val outputs = listOf("Проверить 12 файлов.", "Старый текст удалять нельзя.")
        val prompts = mutableListOf<String>()
        val roles = LocalTextRoles { role, prompt, schema, _ ->
            assertEquals(AiRole.TEXT, role)
            assertTrue(schema.isBlank())
            prompts += prompt
            outputs[prompts.size - 1]
        }
        assertEquals(source, roles.tidy(source))
        assertEquals(2, prompts.size)
        assertTrue(outputs[0] in prompts[0] && outputs[1] !in prompts[0])
        assertTrue(outputs[1] in prompts[1] && outputs[0] !in prompts[1])
    }

    @Test fun rejectedSecondPartDoesNotPublishPartialTextOrRunTheThird() = runTest {
        var calls = 0
        val roles = LocalTextRoles { _, _, _, _ ->
            calls++
            if (calls == 1) "Проверить файлы." else "Старый текст удалять."
        }
        assertFailsWith<IllegalArgumentException> {
            roles.tidy("Проверить файлы. Старый текст удалять нельзя. Позвонить Антону.")
        }
        assertEquals(2, calls)
    }

    @Test fun lostListItemCannotPassAsRemovalOfRepetition() = runTest {
        val source = "Нужно купить молоко, молоко и хлеб."
        val roles = LocalTextRoles { _, prompt, _, _ ->
            assertTrue("молоко и хлеб" in prompt)
            assertFalse("молоко, молоко" in prompt)
            "Нужно купить хлеб."
        }
        assertFailsWith<IllegalArgumentException> { roles.tidy(source) }
    }

    @Test fun onlyTheModelInputCopyReceivesExplicitCorrection() = runTest {
        val source = "Встреча завтра, нет, в среду в 3 часа."
        val roles = LocalTextRoles { _, prompt, _, _ ->
            assertTrue("Встреча в среду в 3 часа." in prompt)
            assertFalse("завтра" in prompt)
            "Встреча в среду в 3 часа."
        }
        assertEquals("Встреча в среду в 3 часа.", roles.tidy(source))
        assertEquals("Встреча завтра, нет, в среду в 3 часа.", source)
    }

    @Test fun cancellationAfterOnePartRemainsCancellation() = runTest {
        var calls = 0
        val roles = LocalTextRoles { _, _, _, _ ->
            if (++calls == 2) throw CancellationException("cancel")
            "Проверить файлы."
        }
        assertFailsWith<CancellationException> { roles.tidy("Проверить файлы. Сохранить заметки.") }
        assertEquals(2, calls)
    }

    @Test fun timeoutCoversAllPartsTogetherRatherThanRestartingForEveryPart() = runTest {
        var calls = 0
        val roles = LocalTextRoles { _, _, _, _ ->
            calls++
            delay(500_000)
            "Проверить файлы."
        }
        assertFailsWith<TimeoutCancellationException> { roles.tidy("Проверить файлы. Проверить файлы.") }
        assertEquals(2, calls)
    }
}
