@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber

internal sealed interface IosAudioSystemEvent {
    data class InterruptionBegan(val wasSuspended: Boolean) : IosAudioSystemEvent
    data class InterruptionEnded(val canResume: Boolean) : IosAudioSystemEvent
    data class RouteChanged(
        val reason: String,
        val inputAvailable: Boolean,
        val previousHadExternalInput: Boolean,
        val currentHasExternalInput: Boolean,
    ) : IosAudioSystemEvent
    data class ApplicationDidBecomeActive(
        val inputAvailable: Boolean,
        val currentHasExternalInput: Boolean,
    ) : IosAudioSystemEvent
}

/**
 * Синхронный мост между Kotlin iOS adapters и нативным Swift host.
 * AVAudioSession остаётся во Swift; Core не видит Apple-типы.
 */
internal object IosAudioSessionBridge {
    const val ACTIVATE_RECORDING = "KashaAudioSessionActivateRecording"
    const val ACTIVATE_PLAYBACK = "KashaAudioSessionActivatePlayback"
    const val DEACTIVATE = "KashaAudioSessionDeactivate"

    private const val INTERRUPTION_BEGAN = "KashaAudioSessionInterruptionBegan"
    private const val INTERRUPTION_ENDED = "KashaAudioSessionInterruptionEnded"
    private const val ROUTE_CHANGED = "KashaAudioRouteChanged"
    private const val APP_DID_BECOME_ACTIVE = "KashaApplicationDidBecomeActive"

    private var systemEventHandler: ((IosAudioSystemEvent) -> Unit)? = null
    private var observersInstalled = false

    fun activateRecording() = post(ACTIVATE_RECORDING)
    fun activatePlayback() = post(ACTIVATE_PLAYBACK)
    fun deactivate() = post(DEACTIVATE)

    /**
     * Foundation observers устанавливаются ровно один раз на процесс. При пересоздании
     * Compose host заменяется только текущий handler, поэтому системное событие не дублируется.
     * Swift гарантирует доставку bridge events на main thread.
     */
    fun observeSystemEvents(handler: (IosAudioSystemEvent) -> Unit) {
        systemEventHandler = handler
        if (observersInstalled) return
        observersInstalled = true

        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(INTERRUPTION_BEGAN, null, null) { notification ->
            systemEventHandler?.invoke(
                IosAudioSystemEvent.InterruptionBegan(notification.bool("wasSuspended"))
            )
        }
        center.addObserverForName(INTERRUPTION_ENDED, null, null) { notification ->
            systemEventHandler?.invoke(
                IosAudioSystemEvent.InterruptionEnded(notification.bool("canResume"))
            )
        }
        center.addObserverForName(ROUTE_CHANGED, null, null) { notification ->
            systemEventHandler?.invoke(
                IosAudioSystemEvent.RouteChanged(
                    reason = notification.string("reason"),
                    inputAvailable = notification.bool("inputAvailable"),
                    previousHadExternalInput = notification.bool("previousHadExternalInput"),
                    currentHasExternalInput = notification.bool("currentHasExternalInput"),
                )
            )
        }
        center.addObserverForName(APP_DID_BECOME_ACTIVE, null, null) { notification ->
            systemEventHandler?.invoke(
                IosAudioSystemEvent.ApplicationDidBecomeActive(
                    inputAvailable = notification.bool("inputAvailable"),
                    currentHasExternalInput = notification.bool("currentHasExternalInput"),
                )
            )
        }
    }

    private fun post(name: String) {
        NSNotificationCenter.defaultCenter.postNotificationName(name, null)
    }

    private fun NSNotification?.bool(key: String): Boolean =
        (this?.userInfo?.get(key) as? NSNumber)?.boolValue ?: false

    private fun NSNotification?.string(key: String): String =
        this?.userInfo?.get(key)?.toString().orEmpty()
}
