@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import platform.Foundation.NSNotificationCenter

/**
 * Синхронный мост из Kotlin iOS adapters в нативный Swift host.
 * Сам AVAudioSession настраивается в iosApp, потому что setActive не экспортирован
 * текущими Kotlin/Native AVFAudio bindings. Бизнес-логики здесь нет.
 */
internal object IosAudioSessionBridge {
    const val ACTIVATE_RECORDING = "KashaAudioSessionActivateRecording"
    const val ACTIVATE_PLAYBACK = "KashaAudioSessionActivatePlayback"
    const val DEACTIVATE = "KashaAudioSessionDeactivate"

    fun activateRecording() = post(ACTIVATE_RECORDING)
    fun activatePlayback() = post(ACTIVATE_PLAYBACK)
    fun deactivate() = post(DEACTIVATE)

    private fun post(name: String) {
        NSNotificationCenter.defaultCenter.postNotificationName(name, object = null)
    }
}
