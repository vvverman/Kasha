package ru.vrmn.kasha.android

import brain.domain.AudioGateway
import brain.studio.AudioTelemetry

/** Playback подключается на следующем этапе; не изображаем работающий player заранее. */
internal object AndroidUnavailableAudio : AudioGateway {
    override suspend fun playCapture(captureId: String, compact: Boolean, fromSeconds: Double, rate: Double) =
        error("audioPlaybackNotConfigured")

    override fun telemetry(): AudioTelemetry = AudioTelemetry()
    override fun stop() = Unit
}
