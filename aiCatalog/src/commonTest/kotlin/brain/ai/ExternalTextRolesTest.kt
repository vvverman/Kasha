package brain.ai

import brain.domain.CaptureWorkflow
import brain.model.*
import brain.studio.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ExternalTextRolesTest {
    private val source = "Ирина не меняла 1200 пунктов."
    private val projects = listOf(Project("b", "Проект Б", "Описание", "Инструкция", manualOrder = 0), Project("a", "Проект А", manualOrder = 1))
    @Test fun routingUsesOneBatchWithOnlyConsentedFieldsAndKeepsOrder() = runTest {
        var calls = 0
        val roles = ExternalTextRoles { role, prompt ->
            calls++; assertEquals(AiRole.ROUTING, role)
            val json = Json.parseToJsonElement(prompt.substringAfter('\n')).jsonObject
            assertEquals(setOf("source", "projects"), json.keys)
            assertEquals(source, json.getValue("source").jsonPrimitive.content)
            val values = json.getValue("projects").jsonArray
            assertEquals(listOf("b", "a"), values.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            assertTrue(values.all { it.jsonObject.keys == setOf("id", "title", "description", "instruction") })
            "{\"a\": 1, \"b\": 4}"
        }
        assertEquals(mapOf("a" to 1, "b" to 4), roles.rank(source, projects))
        assertEquals(1, calls)
        assertEquals(listOf("b", "a"), projects.map { it.id })
    }
    @Test fun emptyProjectsNeverMakeNetworkRequest() = runTest {
        assertEquals(emptyMap(), ExternalTextRoles { _, _ -> error("unexpected request") }.rank(source, emptyList()))
    }
    @Test fun malformedIncompleteForeignAndOutOfRangeRankingsAreRejected() = runTest {
        for (answer in listOf("{}", "{\"a\":4}", "{\"a\":1,\"b\":4,\"c\":1}", "{\"a\":1,\"b\":5}",
            "{\"a\":1,\"b\":-1}", "{\"a\":1,\"b\":\"4\"}", "{\"a\":1,\"b\":2.5}", "invalid")) {
            assertFails { ExternalTextRoles { _, _ -> answer }.rank(source, projects) }
        }
    }
    @Test fun editedNumbersNamesAndNegationsAreRejected() = runTest {
        for (answer in listOf("Ирина не меняла 2000 пунктов.", "Ирина меняла 1200 пунктов.", "Марина не меняла 1200 пунктов.", "")) {
            assertFails { ExternalTextRoles { _, _ -> answer }.tidy(source) }
        }
    }
    @Test fun harmlessFormattingPasses() = runTest {
        assertEquals("Ирина не меняла 1200 пунктов!", ExternalTextRoles { _, _ -> "Ирина не меняла 1200 пунктов!" }.tidy(source))
    }
    @Test fun rejectedTidyAndRankPreserveOriginalAndAllowManualSaving() = runTest {
        val external = ExternalTextRoles { role, _ -> if (role == AiRole.TEXT) "Ирина меняла 1200 пунктов." else "{}" }
        val workflow = CaptureWorkflow(object : Intelligence {
            override val simulated = false
            override suspend fun transcribe(file: String, language: String, example: String) = source
            override suspend fun title(text: String, language: String) = text
            override suspend fun tidy(text: String, language: String) = external.tidy(text)
            override suspend fun rank(text: String, projects: List<Project>, language: String) = external.rank(text, projects)
        })
        val capture = Capture("voice", 1, transcript = source, preparedText = source, status = CaptureStatus.READY)
        assertFails { workflow.tidy(capture, "ru") }
        val tidied = capture
        assertEquals(source, tidied.preparedText); assertFalse(tidied.llmApplied)
        val ranked = workflow.rank(tidied, projects, "ru")
        assertFalse(ranked.rankingApplied); assertTrue(ranked.relevance.isEmpty())
        val data = brain.domain.BrainData(projects = projects, captures = listOf(ranked))
        val (_, note) = data.distribute(capture.id, DistributionRequest(projectId = "a"), "note", 2)
        assertEquals(source, note.body)
    }
}
