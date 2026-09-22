package brain.runtime

import brain.ai.EmbeddingProjectRouting
import brain.model.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LocalEmbeddingRoutingTest {
    @Test fun multilineTextIsOnePrivateRequestAndMachineOutputIsNotDisabled(): Unit = runBlocking {
        val root = Files.createTempDirectory("kasha-embedding-test-")
        val source = "Запись голоса\nНе потерять заметку\\nИрина 1200"
        val project = Project("notes", "Голосовые заметки", "Запись\nАудио", "Сохранить весь текст")
        val contents = mutableListOf<String>()
        val prompts = mutableListOf<Path>()
        try {
            val router = LocalEmbeddingRouting("embedding", root.resolve("model.gguf"), root, CommandRunner { args, timeout ->
                assertEquals(300L, timeout)
                assertFalse("--log-disable" in args)
                assertEquals("0", args[args.indexOf("--verbosity") + 1])
                assertTrue("--no-escape" in args)
                assertEquals("array", args[args.indexOf("--embd-output-format") + 1])
                val file = Path.of(args[args.indexOf("--file") + 1])
                prompts.add(file)
                val text = Files.readString(file)
                contents.add(text)
                assertTrue('\n' in text)
                assertFalse(args.any { it.contains("Ирина") || it.contains("Голосовые") })
                val separator = args[args.indexOf("--embd-separator") + 1]
                assertTrue(separator.isNotEmpty() && separator !in text)
                "[[3.0,4.0]]"
            })
            assertEquals(mapOf("notes" to 4), router.rank(source, listOf(project)))
            assertEquals(listOf(EmbeddingProjectRouting.query(source), EmbeddingProjectRouting.document(project)), contents)
            assertTrue(prompts.all { !Files.exists(it) })
            Files.list(root).use { assertEquals(0L, it.count()) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun emptyProjectsDoNotStartTheEngine(): Unit = runBlocking {
        assertEquals(emptyMap(), LocalEmbeddingRouting("unused", Path.of("unused"), Path.of("unused"),
            CommandRunner { _, _ -> error("Не должен запускаться") }).rank("Текст", emptyList()))
    }

    @Test fun invalidOrMultipleVectorsAreRejectedInsteadOfTakingTheFirst(): Unit = runBlocking {
        for (output in listOf("", "[]", "[[]]", "[[1,0],[0,1]]", "[1,2]", "{}", "not-json",
            "[[null,1]]", "[[\"1\",2]]", "[[1e999,1]]", "[[0,0]]")) {
            val root = Files.createTempDirectory("kasha-embedding-invalid-")
            try {
                val router = LocalEmbeddingRouting("embedding", root.resolve("model"), root, CommandRunner { _, _ -> output })
                assertFails { router.rank("Текст", listOf(Project("id", "Проект"))) }
                Files.list(root).use { assertEquals(0L, it.count(), output) }
            } finally { root.toFile().deleteRecursively() }
        }
    }

    @Test fun differentDimensionsFailWithoutPartialScores(): Unit = runBlocking {
        val root = Files.createTempDirectory("kasha-embedding-dimension-")
        var calls = 0
        try {
            val router = LocalEmbeddingRouting("embedding", root.resolve("model"), root,
                CommandRunner { _, _ -> if (++calls == 1) "[[1,0]]" else "[[1,0,0]]" })
            assertFails { router.rank("Текст", listOf(Project("id", "Проект"))) }
            Files.list(root).use { assertEquals(0L, it.count()) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun actualCosineNormalizesVectorsJustLikeNativeAdapters(): Unit = runBlocking {
        val root = Files.createTempDirectory("kasha-embedding-cosine-")
        var calls = 0
        val outputs = listOf("[[5,0]]", "[[50,0]]", "[[0,50]]", "[[-50,0]]")
        try {
            val router = LocalEmbeddingRouting("embedding", root.resolve("model"), root,
                CommandRunner { _, _ -> outputs[calls++] })
            assertEquals(mapOf("same" to 4, "other" to 0, "opposite" to 0), router.rank("Текст",
                listOf(Project("same", "Тема"), Project("other", "Другая тема"), Project("opposite", "Противоположная тема"))))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun processFailureAndCancellationDeleteThePrivateFile(): Unit = runBlocking {
        for (cancel in listOf(false, true)) {
            val root = Files.createTempDirectory("kasha-embedding-failure-")
            try {
                val router = LocalEmbeddingRouting("embedding", root.resolve("model"), root, CommandRunner { _, _ ->
                    if (cancel) throw CancellationException("cancel") else error("failed")
                })
                if (cancel) assertFailsWith<CancellationException> { router.rank("Текст", listOf(Project("id", "Проект"))) }
                else assertFailsWith<IllegalStateException> { router.rank("Текст", listOf(Project("id", "Проект"))) }
                Files.list(root).use { assertEquals(0L, it.count()) }
            } finally { root.toFile().deleteRecursively() }
        }
    }
}
