package brain.ios

/**
 * iOS-only policy for system events while AVAudioPlayer exists.
 * The shared product state sees the result through AudioTelemetry; this layer only
 * prevents system interruptions or output-route loss from continuing playback silently.
 */
internal object IosPlaybackLifecycle {
    fun shouldPause(event: IosAudioSystemEvent): Boolean = when (event) {
        is IosAudioSystemEvent.InterruptionBegan -> true
        is IosAudioSystemEvent.InterruptionEnded -> true // enforce no hidden auto-resume
        is IosAudioSystemEvent.RouteChanged ->
            event.reason == "oldDeviceUnavailable" || event.reason == "noSuitableRouteForCategory"
        is IosAudioSystemEvent.ApplicationDidBecomeActive -> false
    }
}
