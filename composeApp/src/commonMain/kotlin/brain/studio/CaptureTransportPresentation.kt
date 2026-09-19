package brain.studio

/** Представление одного транспорта; аудиосессия и команды принадлежат Core. */
internal enum class CaptureTransportPresentation { HIDDEN, EXPANDED_RECORDING, COMPACT }

internal fun captureTransportPresentation(
    captureHome: Boolean,
    recording: Boolean,
    hasCapture: Boolean,
    hasSource: Boolean,
    pending: Boolean,
    controlBusy: Boolean,
): CaptureTransportPresentation = when {
    captureHome && recording -> CaptureTransportPresentation.EXPANDED_RECORDING
    !recording && !hasCapture && !hasSource && !pending && !controlBusy -> CaptureTransportPresentation.HIDDEN
    else -> CaptureTransportPresentation.COMPACT
}
