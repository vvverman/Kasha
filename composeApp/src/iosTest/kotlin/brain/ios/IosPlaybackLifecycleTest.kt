package brain.ios

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosPlaybackLifecycleTest {
    @Test
    fun interruptionsAlwaysForcePauseInsteadOfAutoResume() {
        assertTrue(
            IosPlaybackLifecycle.shouldPause(
                IosAudioSystemEvent.InterruptionBegan(wasSuspended = false)
            )
        )
        assertTrue(
            IosPlaybackLifecycle.shouldPause(
                IosAudioSystemEvent.InterruptionEnded(
                    canResume = true,
                    inputAvailable = true,
                    currentHasExternalInput = false,
                )
            )
        )
    }

    @Test
    fun disconnectedHeadphonesPauseButNewDeviceDoesNot() {
        assertTrue(
            IosPlaybackLifecycle.shouldPause(
                IosAudioSystemEvent.RouteChanged(
                    reason = "oldDeviceUnavailable",
                    inputAvailable = true,
                    previousHadExternalInput = false,
                    currentHasExternalInput = false,
                )
            )
        )
        assertFalse(
            IosPlaybackLifecycle.shouldPause(
                IosAudioSystemEvent.RouteChanged(
                    reason = "newDeviceAvailable",
                    inputAvailable = true,
                    previousHadExternalInput = false,
                    currentHasExternalInput = false,
                )
            )
        )
    }

    @Test
    fun foregroundAndOwnRouteChangesDoNotInventPlaybackActions() {
        assertFalse(
            IosPlaybackLifecycle.shouldPause(
                IosAudioSystemEvent.ApplicationDidBecomeActive(
                    inputAvailable = true,
                    currentHasExternalInput = false,
                )
            )
        )
        assertFalse(
            IosPlaybackLifecycle.shouldPause(
                IosAudioSystemEvent.RouteChanged(
                    reason = "categoryChange",
                    inputAvailable = true,
                    previousHadExternalInput = false,
                    currentHasExternalInput = false,
                )
            )
        )
    }
}
