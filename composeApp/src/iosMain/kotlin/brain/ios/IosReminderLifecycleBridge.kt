@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package brain.ios

import platform.Foundation.NSNotificationCenter

/** Process-level bridge для пересинхронизации системных reminders. */
internal object IosReminderLifecycleBridge {
    private const val APP_DID_BECOME_ACTIVE = "KashaApplicationDidBecomeActive"
    private const val SIGNIFICANT_TIME_CHANGE = "KashaApplicationSignificantTimeChange"

    private var handler: (() -> Unit)? = null
    private var observersInstalled = false

    fun observe(value: () -> Unit): () -> Unit {
        handler = value
        installOnce()
        return {
            if (handler === value) handler = null
        }
    }

    private fun installOnce() {
        if (observersInstalled) return
        observersInstalled = true
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(APP_DID_BECOME_ACTIVE, null, null) {
            handler?.invoke()
        }
        center.addObserverForName(SIGNIFICANT_TIME_CHANGE, null, null) {
            handler?.invoke()
        }
    }
}
