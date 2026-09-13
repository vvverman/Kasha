package brain.ios

import kotlin.test.Test
import kotlin.test.assertEquals

class IosAudioRecoveryTest {
    @Test
    fun waveformPeaksCoverWholeTimeline() {
        val peaks = IosWaveformPeaks(totalFrames = 8, pointCount = 4)
        listOf(0.1f, 0.4f, 0.2f, 0.8f, 0.7f, 0.3f, 0.5f, 0.9f)
            .forEachIndexed { index, value -> peaks.add(index.toLong(), value) }

        assertEquals(listOf(0.4f, 0.8f, 0.7f, 0.9f), peaks.result())
    }

    @Test
    fun waveformPeaksClampActualSamples() {
        val peaks = IosWaveformPeaks(totalFrames = 2, pointCount = 2)
        peaks.add(0, -1f)
        peaks.add(1, 4f)
        assertEquals(listOf(0f, 1f), peaks.result())
    }
}
