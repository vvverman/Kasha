package brain.runtime

import brain.domain.*
import brain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.*
import kotlin.io.path.*

class LocalProcessing(
    private val store: FileBrainStore,
    private val env: Map<String, String> = System.getenv(),
    private val runner: CommandRunner = JvmCommandRunner(),
) {
    private val whisperCli = env["KASHA_WHISPER_CLI"]
    private val whisperModel = env["KASHA_WHISPER_MODEL"]
    private val llamaCli = env["KASHA_LLAMA_CLI"]
    private val llamaModel = env["KASHA_LLAMA_MODEL"]
    private val ffmpeg = env["KASHA_FFMPEG"] ?: "ffmpeg"
    private val workLock = Mutex()
    private val queueLock = Mutex()
    private val queued = mutableSetOf<String>()

    private fun configured(cli: String?, model: String?): Boolean = runCatching {
        if (cli.isNullOrBlank() || model.isNullOrBlank()) return false
        val executable = if (cli.contains('/') || cli.contains('\\')) Path.of(cli)
            else (env["PATH"] ?: System.getenv("PATH").orEmpty()).split(java.io.File.pathSeparator)
                .map { Path.of(it, cli) }.firstOrNull { Files.isExecutable(it) } ?: return false
        Files.isRegularFile(executable) && Files.isExecutable(executable) &&
            Files.isRegularFile(Path.of(model)) && Files.size(Path.of(model)) > 0
    }.getOrDefault(false)
    fun status(): RuntimeStatus {
        val speech = configured(whisperCli, whisperModel); val llm = configured(llamaCli, llamaModel)
        return RuntimeStatus(speech, llm, true, when {
            !speech -> "Для транскрипции укажите локальные whisper-cli и многоязычную модель. Аудио сохраняется без них; текст можно ввести вручную."
            !llm -> "Whisper настроен. Для оформления и сортировки укажите llama-completion и русскоязычную GGUF-модель."
            else -> "Пути моделей настроены. Качество и доступность вычислений проверяются при обработке."
        })
    }
    suspend fun enqueue(id: String, scope: CoroutineScope): Capture = queueLock.withLock {
        val capture = store.capture(id) ?: error("Запись не найдена")
        require(capture.noteId == null) { "Источник уже добавлен в заметку" }
        if (id in queued) return@withLock capture
        val result = store.updateCapture(id) { it.copy(status = CaptureStatus.QUEUED) }
        queued += id
        scope.launch(Dispatchers.IO) {
            try { process(id) } finally { withContext(NonCancellable) { queueLock.withLock { queued -= id } } }
        }
        result
    }
    suspend fun process(id: String): Capture = workLock.withLock {
        var capture = store.capture(id) ?: error("Запись не найдена")
        require(capture.noteId == null) { "Источник уже добавлен в заметку" }
        val warnings = mutableListOf<String>()
        val work = Files.createTempDirectory(store.root, ".work-")
        try {
            if (capture.transcript.isBlank() && !status().whisperConfigured) {
                return@withLock store.updateCapture(id) { it.copy(status = CaptureStatus.NEEDS_MODEL, message = status().message) }
            }
            var wav: Path? = null
            if (capture.transcript.isBlank() || capture.compactAudioFileName == null) {
                val source = store.resolveAudio(capture); wav = work.resolve("input.wav")
                val format = when (source.extension.lowercase()) { "webm" -> "matroska"; "m4a", "mp4" -> "mov"; "caf" -> "caf"; "ogg" -> "ogg"; else -> "wav" }
                runner.run(listOf(ffmpeg, "-nostdin", "-v", "error", "-y", "-protocol_whitelist", "file,pipe", "-f", format,
                    "-i", source.toString(), "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", wav.toString()), 300)
                val duration = PcmAudio.info(wav).duration
                capture = store.updateCapture(id) { it.copy(durationSeconds = duration) }
            }
            if (capture.transcript.isBlank()) {
                store.updateCapture(id) { it.copy(status = CaptureStatus.TRANSCRIBING, message = "") }
                val out = work.resolve("transcript")
                runner.run(listOf(whisperCli!!, "-m", whisperModel!!, "-f", wav.toString(), "-l", "ru", "-otxt", "-oj", "-of", out.toString()), 3600)
                currentCoroutineContext().ensureActive()
                val transcript = Files.readString(Path.of("$out.txt")).trim()
                require(transcript.isNotBlank()) { "Речь не распознана. Аудио сохранено" }
                val pieces = runCatching { ModelOutput.whisperPieces(Files.readString(Path.of("$out.json")), capture.durationSeconds) }.getOrElse {
                    warnings += "Таймкоды не получены; полный транскрипт сохранён"; emptyList()
                }
                capture = store.updateCapture(id) { it.copy(transcript = transcript, pieces = pieces,
                    preparedText = if (it.draftEdited) it.preparedText else transcript,
                    title = if (it.draftEdited) it.title else NoteText.title(transcript)) }
            }
            if (capture.compactAudioFileName == null && wav != null) {
                store.updateCapture(id) { it.copy(status = CaptureStatus.COMPACTING) }
                try {
                    val compactWav = work.resolve("compact.wav")
                    val (_, spans) = PcmAudio.compact(wav, compactWav)
                    val exported = work.resolve("compact.m4a")
                    runner.run(listOf(ffmpeg, "-nostdin", "-v", "error", "-y", "-i", compactWav.toString(), "-c:a", "aac", "-b:a", "48k", exported.toString()), 300)
                    currentCoroutineContext().ensureActive()
                    val target = store.resolveAudio(capture).parent.resolve("compact.m4a")
                    Files.move(exported, target, StandardCopyOption.REPLACE_EXISTING)
                    capture = store.updateCapture(id) { it.copy(compactAudioFileName = store.root.toAbsolutePath().relativize(target).toString().replace('\\', '/'),
                        compactDurationSeconds = spans.sumOf { span -> span.duration }, spans = spans) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { warnings += "Не удалось сократить паузы: ${e.message}" }
            }
            if (status().llmConfigured) {
                store.updateCapture(id) { it.copy(status = CaptureStatus.POLISHING) }
                val llm = LocalLlm(llamaCli!!, llamaModel!!, work, runner)
                // Ранжирование независимо от оформления: сбой одного не отменяет другое.
                if (!capture.draftEdited && !capture.llmApplied) {
                    try {
                        val edited = TextChunks.split(capture.transcript).map { chunk ->
                            ModelOutput.cleaned(llm.generate(LocalModelText.cleanPrompt(chunk), LocalModelText.CLEAN_SCHEMA, 2200), chunk)
                        }
                        capture = store.updateCapture(id) { it.copy(preparedText = edited.joinToString("\n\n") { part -> part.text },
                            title = edited.first().title, llmApplied = true) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { warnings += "Оформление не применено: ${e.message}. Полный транскрипт сохранён" }
                }
                try {
                    val projects = store.snapshot().projects.filterNot { it.pinned }
                    val grades = projects.associate { project ->
                        require(project.title.length + project.description.length + project.instruction.length <= 4000) {
                            "Описание проекта слишком длинное для локального ранжирования"
                        }
                        project.id to (TextChunks.split(capture.textToSave).maxOfOrNull { chunk ->
                            ModelOutput.relevance(llm.generate(LocalModelText.rankPrompt(project, chunk), LocalModelText.RANK_SCHEMA, 80))
                        } ?: 0)
                    }
                    capture = store.updateCapture(id) { it.copy(relevance = grades, rankingApplied = true) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    store.updateCapture(id) { it.copy(relevance = emptyMap(), rankingApplied = false) }
                    warnings += "ИИ-сортировка не выполнена: ${e.message}"
                }
            } else warnings += status().message
            store.updateCapture(id) { it.copy(status = CaptureStatus.READY, message = warnings.joinToString("\n\n")) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { store.updateCapture(id) { it.copy(status = CaptureStatus.FAILED, message = "Обработка отменена. Источник и готовые этапы сохранены") } }; throw e
        } catch (e: Exception) {
            store.updateCapture(id) { it.copy(status = CaptureStatus.FAILED, message = e.message ?: "Ошибка локальной обработки") }
        } finally { work.toFile().deleteRecursively() }
    }
}
