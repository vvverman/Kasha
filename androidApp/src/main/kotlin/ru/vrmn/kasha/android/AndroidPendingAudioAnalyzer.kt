package ru.vrmn.kasha.android

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

internal data class AndroidPendingAudioAnalysis(
    val durationSeconds: Double,
    val waveform: List<Float>,
)

/**
 * Локально извлекает фактическую duration и waveform из сохранённого Android M4A.
 * Если decoder не даёт поддерживаемый PCM, caller может оставить waveform неизвестной,
 * но не должен подставлять синтетические пики.
 */
internal object AndroidPendingAudioAnalyzer {
    fun analyze(file: File): AndroidPendingAudioAnalysis? {
        if (!file.isFile || file.length() <= 0L) return null

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val durationSeconds = inputFormat
                .takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)
                ?.div(1_000_000.0)
                ?: 0.0
            val sampleRate = inputFormat
                .takeIf { it.containsKey(MediaFormat.KEY_SAMPLE_RATE) }
                ?.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ?: return AndroidPendingAudioAnalysis(durationSeconds, emptyList())
            val channels = inputFormat
                .takeIf { it.containsKey(MediaFormat.KEY_CHANNEL_COUNT) }
                ?.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                ?: 1

            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            codecStarted = true

            val accumulator = PcmPeakAccumulator(
                samplesPerPoint = ((sampleRate.toLong() * channels * POINT_INTERVAL_MS) / 1_000L)
                    .coerceAtLeast(1L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt(),
            )
            val info = MediaCodec.BufferInfo()
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var inputDone = false
            var outputDone = false
            var idleLoops = 0

            while (!outputDone && idleLoops < MAX_IDLE_LOOPS) {
                var progressed = false

                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        progressed = true
                        val buffer = codec.getInputBuffer(inputIndex) ?: error("Decoder input buffer unavailable")
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime.coerceAtLeast(0L),
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        progressed = true
                        pcmEncoding = pcmEncoding(codec.outputFormat)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        progressed = true
                        val outputFormat = runCatching { codec.getOutputFormat(outputIndex) }.getOrNull()
                        if (outputFormat != null) pcmEncoding = pcmEncoding(outputFormat)
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            codec.getOutputBuffer(outputIndex)?.let { output ->
                                appendPcm(output, info.offset, info.size, pcmEncoding, accumulator)
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }

                idleLoops = if (progressed) 0 else idleLoops + 1
            }

            if (!outputDone) return AndroidPendingAudioAnalysis(durationSeconds, emptyList())
            val waveform = reduceWaveform(accumulator.finish(), MAX_WAVEFORM_POINTS)
            return AndroidPendingAudioAnalysis(durationSeconds, waveform)
        } catch (_: Throwable) {
            return null
        } finally {
            if (codec != null) {
                if (codecStarted) runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            extractor.release()
        }
    }

    private fun pcmEncoding(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }

    private fun appendPcm(
        source: ByteBuffer,
        offset: Int,
        size: Int,
        encoding: Int,
        accumulator: PcmPeakAccumulator,
    ) {
        val end = (offset + size).coerceAtMost(source.capacity())
        if (offset < 0 || end <= offset) return
        val buffer = source.duplicate().order(ByteOrder.nativeOrder())
        buffer.position(offset)
        buffer.limit(end)

        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                while (buffer.remaining() >= Short.SIZE_BYTES) {
                    accumulator.add(abs(buffer.short.toInt()) / 32768f)
                }
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                while (buffer.remaining() >= Float.SIZE_BYTES) {
                    val value = buffer.float
                    accumulator.add(if (value.isFinite()) abs(value).coerceIn(0f, 1f) else 0f)
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                while (buffer.hasRemaining()) {
                    val unsigned = buffer.get().toInt() and 0xFF
                    accumulator.add(abs(unsigned - 128) / 128f)
                }
            }
            else -> throw UnsupportedOperationException("Unsupported PCM encoding: $encoding")
        }
    }

    private const val POINT_INTERVAL_MS = 65L
    private const val CODEC_TIMEOUT_US = 10_000L
    private const val MAX_IDLE_LOOPS = 300
    private const val MAX_WAVEFORM_POINTS = 512
}

internal class PcmPeakAccumulator(private val samplesPerPoint: Int) {
    init { require(samplesPerPoint > 0) }

    private val peaks = mutableListOf<Float>()
    private var count = 0
    private var peak = 0f

    fun add(value: Float) {
        peak = max(peak, value.coerceIn(0f, 1f))
        count++
        if (count >= samplesPerPoint) flush()
    }

    fun finish(): List<Float> {
        if (count > 0) flush()
        return peaks.toList()
    }

    private fun flush() {
        peaks += peak
        count = 0
        peak = 0f
    }
}
