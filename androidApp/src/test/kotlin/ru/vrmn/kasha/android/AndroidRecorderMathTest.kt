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
}
