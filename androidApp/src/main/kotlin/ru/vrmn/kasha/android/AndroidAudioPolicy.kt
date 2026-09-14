package ru.vrmn.kasha.android

/** Android AUDIOFOCUS_LOSS* — отрицательные события. Возврат focus не запускает звук. */
internal object AndroidAudioPolicy {
    fun pauseOnFocusChange(change: Int): Boolean = change < 0
    fun rate(value: Double): Float {
        require(value.isFinite()) { "invalidPlaybackRate" }
        return value.coerceIn(1.0, 2.0).toFloat()
    }
}
