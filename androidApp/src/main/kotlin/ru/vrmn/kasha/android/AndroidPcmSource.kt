package ru.vrmn.kasha.android

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Временный PCM для системного STT. Исходная пользовательская запись не меняется. */
internal data class AndroidPcmSource(val file: File, val sampleRate: Int, val channels: Int)

internal suspend fun decodeSpeechSource(source: File, cache: File): AndroidPcmSource = withContext(Dispatchers.IO) {
    require(source.isFile && source.length() > 0) { "audioFailed" }
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    val output = File.createTempFile("kasha-stt-", ".pcm", cache)
    try {
        extractor.setDataSource(source.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("audioFailed")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var inputEnded = false
        var outputEnded = false
        val info = MediaCodec.BufferInfo()
        output.outputStream().buffered().use { sink ->
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: error("audioFailed")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val actual = decoder.outputFormat
                    rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    check(!actual.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                        actual.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) { "audioFailed" }
                } else if (index >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = decoder.getOutputBuffer(index) ?: error("audioFailed")
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            sink.write(bytes)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(index, false) }
                }
            }
        }
        check(output.length() > 0 && rate > 0 && channels > 0) { "audioFailed" }
        AndroidPcmSource(output, rate, channels)
    } catch (failure: Throwable) {
        output.delete()
        throw failure
    } finally {
        runCatching { codec?.stop() }
        codec?.release()
        extractor.release()
    }
}
