@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSURL
import kotlin.math.abs

internal data class IosRecoveredAudioMetadata(
    val durationSeconds: Double,
    val waveform: List<Float>,
)

/** Читает фактические metadata готового локального файла без synthetic fallback. */
internal object IosAudioRecovery {
    private const val MAX_POINTS = 256
    private const val BUFFER_FRAMES = 4096u

    fun inspect(path: String): IosRecoveredAudioMetadata {
        val duration = runCatching {
            AVAudioPlayer(NSURL.fileURLWithPath(path), error = null).duration.coerceAtLeast(0.0)
        }.getOrDefault(0.0)
        val waveform = runCatching { decodeWaveform(path) }.getOrDefault(emptyList())
        return IosRecoveredAudioMetadata(duration, waveform)
    }

    private fun decodeWaveform(path: String): List<Float> {
        val file = AVAudioFile(NSURL.fileURLWithPath(path), error = null)
        val totalFrames = file.length.coerceAtLeast(0L)
        if (totalFrames == 0L) return emptyList()

        val pointCount = minOf(MAX_POINTS.toLong(), totalFrames).toInt()
        val peaks = IosWaveformPeaks(totalFrames, pointCount)
        val buffer = AVAudioPCMBuffer(file.processingFormat, BUFFER_FRAMES)
        var absoluteFrame = 0L

        while (absoluteFrame < totalFrames) {
            if (!file.readIntoBuffer(buffer, error = null)) break
            val frames = buffer.frameLength.toInt()
            if (frames <= 0) break
            val channels = buffer.floatChannelData ?: return emptyList()
            val channelCount = buffer.format.channelCount.toInt().coerceAtLeast(1)

            for (frame in 0 until frames) {
                var peak = 0f
                for (channel in 0 until channelCount) {
                    val samples = channels[channel] ?: continue
                    val value = abs(samples[frame]).coerceIn(0f, 1f)
                    if (value > peak) peak = value
                }
                peaks.add(absoluteFrame + frame, peak)
            }
            absoluteFrame += frames
        }
        return peaks.result()
    }
}

/** Pure reducer: вся временная шкала сводится в фиксированное число peak buckets. */
internal class IosWaveformPeaks(
    private val totalFrames: Long,
    pointCount: Int,
) {
    private val values = FloatArray(pointCount.coerceAtLeast(1))

    fun add(frame: Long, value: Float) {
        if (totalFrames <= 0L || frame < 0L) return
        val index = ((frame.toDouble() / totalFrames.toDouble()) * values.size)
            .toInt()
            .coerceIn(0, values.lastIndex)
        val normalized = value.coerceIn(0f, 1f)
        if (normalized > values[index]) values[index] = normalized
    }

    fun result(): List<Float> = values.toList()
}
