package brain.ai

import brain.model.Project
import brain.studio.AiRole
import kotlin.coroutines.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class LocalTextRolesTest {
    // Ответы здесь синхронные; проверяется общий контракт, а не реальный вывод модели.
    private fun immediate(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { outcome = result }
        })
        checkNotNull(outcome) { "Unexpected asynchronous test" }.getOrThrow()
    }

    @Test fun titleAndTidyUseTextRoleButRoutingDoesNot() = immediate {
        val roles = mutableListOf<AiRole>()
        val model = LocalTextRoles { role, _, schema, _ ->
            roles += role
            if (role == AiRole.ROUTING) {
                assertTrue("relevance" in schema)
                """{"relevance":3}"""
            } else {
                assertTrue(schema.isBlank())
                "Проверить 12 файлов"
            }
        }
        assertEquals("Проверить 12 файлов", model.title("Проверить 12 файлов"))
        assertEquals("Проверить 12 файлов", model.tidy("Проверить 12 файлов"))
        assertEquals(mapOf("p" to 3), model.rank("Проверить 12 файлов", listOf(Project("p", "Работа"))))
        assertEquals(listOf(AiRole.TEXT, AiRole.ROUTING), roles)
    }
    @Test fun rejectedCleanupIsNotRetriedAsAChatConversation() = immediate {
        var calls = 0
        val model = LocalTextRoles { role, prompt, schema, _ ->
            assertEquals(AiRole.TEXT, role)
            assertTrue(schema.isBlank())
            assertTrue("Ирина не меняла 12 файлов" in prompt)
            calls++
            "Ирина меняла 12 файлов"
        }
        assertFailsWith<IllegalArgumentException> { model.tidy("Ирина не меняла 12 файлов") }
        assertEquals(1, calls)
    }

    @Test fun changedNumbersAreRejectedByExistingCoreValidator() = immediate {
        val model = LocalTextRoles { _, _, _, _ -> "Ирина не меняла 21 файл" }
        assertFailsWith<IllegalArgumentException> { model.tidy("Ирина не меняла 12 файлов") }
    }
    @Test fun lostNegationIsRejectedByExistingCoreValidator() = immediate {
        val model = LocalTextRoles { _, _, _, _ -> "Ирина меняла 12 файлов" }
        assertFailsWith<IllegalArgumentException> { model.tidy("Ирина не меняла 12 файлов") }
    }
    @Test fun emptyCleanupResponseIsNotPublishedAsText() = immediate {
        val model = LocalTextRoles { _, _, _, _ -> "   " }
        assertFails { model.tidy("Исходная заметка") }
    }
    @Test fun noProjectsDoesNotLoadModel() = immediate {
        val model = LocalTextRoles { _, _, _, _ -> error("Must not execute") }
        assertEquals(emptyMap(), model.rank("Заметка", emptyList()))
    }
    @Test fun routingKeepsProjectIdsOrderAndChecksRange() = immediate {
        val model = LocalTextRoles { _, _, _, _ -> """{"relevance":5}""" }
        assertFailsWith<IllegalArgumentException> { model.rank("Заметка", listOf(Project("id", "Работа"))) }
    }
    @Test fun cancellationIsNotConvertedIntoAUsableResult() = immediate {
        val model = LocalTextRoles { _, _, _, _ -> throw CancellationException("cancel") }
        assertFailsWith<CancellationException> { model.tidy("Заметка") }
    }
}
