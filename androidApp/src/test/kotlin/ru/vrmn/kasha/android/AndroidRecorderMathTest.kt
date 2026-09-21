package ru.vrmn.kasha.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRecorderMathTest {
    @Test
    fun reduceWaveformKeepsWholeTimelineAndPeaks() {
        val source = List(1_024) { index ->
            when (index) {
                10 -> 0.9f
                500 -> 0.8f
                1_000 -> 1.0f
                else -> 0.1f
            }
        }

        val reduced = reduceWaveform(source, 128)

        assertEquals(128, reduced.size)
        assertTrue(reduced.take(4).any { it >= 0.9f })
        assertTrue(reduced.slice(60..64).any { it >= 0.8f })
        assertTrue(reduced.takeLast(4).any { it >= 1.0f })
    }

    @Test
    fun reduceWaveformClampsValuesAndKeepsShortInput() {
        assertEquals(listOf(0f, 0.5f, 1f), reduceWaveform(listOf(-1f, 0.5f, 2f), 512))
    }

    @Test
    fun pcmPeakAccumulatorKeepsPeakPerTimeBucketAndFlushesTail() {
        val accumulator = PcmPeakAccumulator(samplesPerPoint = 4)
        listOf(0.1f, 0.8f, 0.2f, 0.3f, 0.4f, 0.6f).forEach(accumulator::add)

        assertEquals(listOf(0.8f, 0.6f), accumulator.finish())
    }

    @Test
    fun pcmPeakAccumulatorClampsDecodedSamples() {
        val accumulator = PcmPeakAccumulator(samplesPerPoint = 2)
        accumulator.add(-1f)
        accumulator.add(2f)

        assertEquals(listOf(1f), accumulator.finish())
    }
}
