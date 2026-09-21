package brain.studio

import brain.application.KashaApplicationState
import brain.application.TransportOperation
import brain.domain.RecorderPhase
import brain.model.CaptureStatus

/** Представление общей операции Core; новых продуктовых состояний не хранит. */
internal fun captureProgressKey(state: KashaApplicationState): String? {
    val transport = state.transport
    val capture = state.current
    return when {
        transport.operation == TransportOperation.RECOVER ||
            (transport.operation == TransportOperation.LAUNCH && transport.hasPending) -> "captureRecovering"
        transport.operation == TransportOperation.FINISH ||
            transport.recorderPhase == RecorderPhase.FINALIZING -> "captureFinalizing"
        transport.operation == TransportOperation.START ||
            transport.operation == TransportOperation.CANCEL -> "preparing"
        capture?.status == CaptureStatus.TRANSCRIBING -> "transcribing"
        capture?.status == CaptureStatus.COMPACTING -> "compacting"
        capture?.status?.isWorking == true -> "preparing"
        capture?.status == CaptureStatus.READY && !capture.audioFinalized -> "captureFinalizing"
        else -> null // Ошибка и NEEDS_MODEL сохраняют редактор и существующий Retry.
    }
}
