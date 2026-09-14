package brain.desktop

import java.awt.Desktop
import java.awt.EventQueue

/** Routes a supported system Quit action through the window's save-and-close path. */
internal fun installDesktopQuitHandler(onQuit: () -> Unit): AutoCloseable {
    if (!Desktop.isDesktopSupported()) return AutoCloseable { }
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) return AutoCloseable { }
    desktop.setQuitHandler { _, response ->
        // Do not let the OS terminate the JVM before the shared state is flushed.
        response.cancelQuit()
        EventQueue.invokeLater { onQuit() }
    }
    return AutoCloseable { desktop.setQuitHandler(null) }
}
