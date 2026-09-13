package brain.ios

import brain.domain.RecorderIssue
import brain.domain.RecorderIssueKind
import brain.domain.RecorderPhase
import brain.domain.RecorderSessionState

internal data class IosRecorderLifecycleContext(
    val state: RecorderSessionState,
    val systemInterruptionActive: Boolean = false,
    val waitingForExternalInput: Boolean = false,
)

internal data class IosRecorderLifecycleDecision(
    val context: IosRecorderLifecycleContext,
    val pauseRecorder: Boolean = false,
)

/**
 * Чистая iOS state machine для системных audio/lifecycle событий.
 *
 * Здесь нет AVAudioSession/AVAudioRecorder вызовов и нет общей бизнес-логики Kasha:
 * класс только переводит Apple-specific факты в общий RecorderSessionState.
 */
internal object IosRecorderLifecycle {
    fun reduce(
        current: IosRecorderLifecycleContext,
        event: IosAudioSystemEvent,
        recorderActuallyRecording: Boolean,
        recorderPresent: Boolean,
    ): IosRecorderLifecycleDecision = when (event) {
        is IosAudioSystemEvent.InterruptionBegan -> interruptionBegan(current)
        is IosAudioSystemEvent.InterruptionEnded -> interruptionEnded(current, event)
        is IosAudioSystemEvent.RouteChanged -> routeChanged(current, event)
        is IosAudioSystemEvent.ApplicationDidBecomeActive -> foreground(
            current,
            event,
            recorderActuallyRecording,
            recorderPresent,
        )
    }

    private fun interruptionBegan(
        current: IosRecorderLifecycleContext,
    ): IosRecorderLifecycleDecision {
        val next = if (current.state.phase == RecorderPhase.RECORDING) {
            current.state.copy(
                phase = RecorderPhase.INTERRUPTED,
                issue = RecorderIssue(RecorderIssueKind.INTERRUPTION, recoverable = false),
            )
        } else {
            current.state
        }
        return IosRecorderLifecycleDecision(
            context = current.copy(
                state = next,
                systemInterruptionActive = true,
            ),
            pauseRecorder = current.state.phase == RecorderPhase.RECORDING,
        )
    }

    private fun interruptionEnded(
        current: IosRecorderLifecycleContext,
        event: IosAudioSystemEvent.InterruptionEnded,
    ): IosRecorderLifecycleDecision {
        val inputUnavailable = if (current.waitingForExternalInput) {
            !event.currentHasExternalInput
        } else {
            !event.inputAvailable
        }
        val nextState = when {
            current.state.phase == RecorderPhase.INTERRUPTED &&
                current.state.issue?.kind == RecorderIssueKind.INTERRUPTION &&
                inputUnavailable -> current.state.copy(
                    issue = RecorderIssue(RecorderIssueKind.INPUT_UNAVAILABLE, recoverable = false),
                )

            current.state.phase == RecorderPhase.INTERRUPTED &&
                current.state.issue?.kind == RecorderIssueKind.INTERRUPTION -> current.state.copy(
                    issue = RecorderIssue(RecorderIssueKind.INTERRUPTION, recoverable = event.canResume),
                )

            current.state.phase == RecorderPhase.PAUSED && inputUnavailable -> current.state.copy(
                issue = RecorderIssue(RecorderIssueKind.INPUT_UNAVAILABLE, recoverable = false),
            )

            else -> current.state
        }
        return IosRecorderLifecycleDecision(
            current.copy(
                state = nextState,
                systemInterruptionActive = false,
                waitingForExternalInput = current.waitingForExternalInput && !event.currentHasExternalInput,
            )
        )
    }

    private fun routeChanged(
        current: IosRecorderLifecycleContext,
        event: IosAudioSystemEvent.RouteChanged,
    ): IosRecorderLifecycleDecision {
        val externalInputLost =
            event.reason == "oldDeviceUnavailable" &&
                event.previousHadExternalInput &&
                !event.currentHasExternalInput

        if (current.systemInterruptionActive) {
            val waiting = when {
                externalInputLost -> true
                current.waitingForExternalInput && event.currentHasExternalInput -> false
                else -> current.waitingForExternalInput
            }
            return IosRecorderLifecycleDecision(
                current.copy(waitingForExternalInput = waiting)
            )
        }

        val phase = current.state.phase
        if (
            phase != RecorderPhase.RECORDING &&
            phase != RecorderPhase.PAUSED &&
            phase != RecorderPhase.INTERRUPTED
        ) {
            return IosRecorderLifecycleDecision(current)
        }

        val noInput = event.reason == "noSuitableRouteForCategory" || !event.inputAvailable

        if (externalInputLost || noInput) {
            val issue = RecorderIssue(RecorderIssueKind.INPUT_UNAVAILABLE, recoverable = false)
            val nextState = if (phase == RecorderPhase.RECORDING) {
                current.state.copy(
                    phase = RecorderPhase.INTERRUPTED,
                    issue = issue,
                )
            } else {
                current.state.copy(issue = issue)
            }
            return IosRecorderLifecycleDecision(
                context = current.copy(
                    state = nextState,
                    waitingForExternalInput = externalInputLost,
                ),
                pauseRecorder = phase == RecorderPhase.RECORDING,
            )
        }

        if (current.state.issue?.kind != RecorderIssueKind.INPUT_UNAVAILABLE) {
            return IosRecorderLifecycleDecision(current)
        }

        val routeRecovered = if (current.waitingForExternalInput) {
            event.currentHasExternalInput
        } else {
            event.inputAvailable
        }
        if (!routeRecovered) return IosRecorderLifecycleDecision(current)

        return IosRecorderLifecycleDecision(
            current.copy(
                state = current.state.copy(
                    issue = RecorderIssue(RecorderIssueKind.INPUT_UNAVAILABLE, recoverable = true),
                ),
                waitingForExternalInput = false,
            )
        )
    }

    private fun foreground(
        current: IosRecorderLifecycleContext,
        event: IosAudioSystemEvent.ApplicationDidBecomeActive,
        recorderActuallyRecording: Boolean,
        recorderPresent: Boolean,
    ): IosRecorderLifecycleDecision {
        if (current.systemInterruptionActive) return IosRecorderLifecycleDecision(current)

        if (current.state.phase == RecorderPhase.RECORDING && !recorderActuallyRecording) {
            return IosRecorderLifecycleDecision(
                current.copy(
                    state = current.state.copy(
                        phase = RecorderPhase.INTERRUPTED,
                        issue = RecorderIssue(
                            RecorderIssueKind.SESSION_LOST,
                            recoverable = recorderPresent && event.inputAvailable,
                        ),
                    )
                )
            )
        }

        if (current.state.issue?.kind != RecorderIssueKind.INPUT_UNAVAILABLE) {
            return IosRecorderLifecycleDecision(current)
        }

        val routeRecovered = if (current.waitingForExternalInput) {
            event.currentHasExternalInput
        } else {
            event.inputAvailable
        }
        if (!routeRecovered) return IosRecorderLifecycleDecision(current)

        return IosRecorderLifecycleDecision(
            current.copy(
                state = current.state.copy(
                    issue = RecorderIssue(RecorderIssueKind.INPUT_UNAVAILABLE, recoverable = true),
                ),
                waitingForExternalInput = false,
            )
        )
    }
}
