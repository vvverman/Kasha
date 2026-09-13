package brain.runtime

import brain.domain.SilencePlanner
import brain.model.AudioSpan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.RandomAccessFile
import java.nio.file.Path
import kotlin.math.log10
import kotlin.math.sqrt

/** FFmpeg нормализует вход в WAV PCM16 mono/16kHz; читаем порциями, не весь многочасовой WAV. */
object PcmAudio {
    data class Info(val offset: Long, val bytes: Long) { val duration: Double get() = bytes / 32000.0 }
    private fun RandomAccessFile.u32() = Integer.reverseBytes(readInt()).toLong() and 0xffffffffL
    fun info(path: Path): Info = RandomAccessFile(path.toFile(), "r").use { file ->
        require(file.length() >= 44 && file.readInt() == 0x52494646) { "Ожидался RIFF WAV" }
        file.skipBytes(4); require(file.readInt() == 0x57415645) { "Ожидался WAV" }
        var pcm = false
        while (file.filePointer + 8 <= file.length()) {
            val kind = file.readInt(); val size = file.u32(); val pos = file.filePointer
            require(size <= file.length() - pos) { "Неполный WAV" }
            when (kind) {
                0x666d7420 -> {
                    require(size >= 16)
                    val format = java.lang.Short.reverseBytes(file.readShort()).toInt()
                    val channels = java.lang.Short.reverseBytes(file.readShort()).toInt()
                    val rate = file.u32(); file.skipBytes(6)
                    val bits = java.lang.Short.reverseBytes(file.readShort()).toInt()
                    require(format == 1 && channels == 1 && rate == 16000L && bits == 16) { "Нужен PCM16 mono 16 kHz" }; pcm = true
                }
                0x64617461 -> { require(pcm && size > 0 && size % 2 == 0L); return@use Info(pos, size) }
            }
            file.seek(pos + size + size % 2)
        }
        error("WAV не содержит PCM-данных")
    }

    suspend fun compact(source: Path, target: Path): Pair<Info, List<AudioSpan>> {
        val meta = info(source)
        val levels = mutableListOf<Double>()
        val buffer = ByteArray(3200)
        RandomAccessFile(source.toFile(), "r").use { file ->
            file.seek(meta.offset); var remaining = meta.bytes
            while (remaining > 0) {
                currentCoroutineContext().ensureActive()
                val count = minOf(buffer.size.toLong(), remaining).toInt(); file.readFully(buffer, 0, count)
                var sum = 0.0
                for (i in 0 until count step 2) {
                    val sample = ((buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)).toShort().toDouble() / 32768
                    sum += sample * sample
                }
                levels += 20 * log10(sqrt(sum / (count / 2)).coerceAtLeast(0.000001)); remaining -= count
            }
        }
        val spans = SilencePlanner.spans(levels, 0.1, meta.duration)
        val exact = mutableListOf<AudioSpan>(); var bytesWritten = 0L
        RandomAccessFile(source.toFile(), "r").use { input ->
            RandomAccessFile(target.toFile(), "rw").use { output ->
                output.setLength(0); output.write(ByteArray(44))
                for (span in spans) {
                    val start = (span.originalStart * 16000).toLong() * 2
                    val end = ((span.originalStart + span.duration) * 16000).toLong().coerceAtMost(meta.bytes / 2) * 2
                    exact += AudioSpan(start / 32000.0, (end - start) / 32000.0, bytesWritten / 32000.0)
                    input.seek(meta.offset + start); var left = end - start
                    while (left > 0) {
                        currentCoroutineContext().ensureActive()
                        val count = minOf(buffer.size.toLong(), left).toInt(); input.readFully(buffer, 0, count); output.write(buffer, 0, count)
                        left -= count; bytesWritten += count
                    }
                }
                require(bytesWritten < 0xffffffffL - 36) { "Компактный WAV слишком велик" }
                output.seek(0); output.writeBytes("RIFF"); output.writeInt(Integer.reverseBytes((bytesWritten + 36).toInt())); output.writeBytes("WAVEfmt ")
                output.writeInt(Integer.reverseBytes(16)); output.writeShort(0x0100); output.writeShort(0x0100)
                output.writeInt(Integer.reverseBytes(16000)); output.writeInt(Integer.reverseBytes(32000)); output.writeShort(0x0200); output.writeShort(0x1000)
                output.writeBytes("data"); output.writeInt(Integer.reverseBytes(bytesWritten.toInt()))
            }
        }
        return meta to exact
    }
}
