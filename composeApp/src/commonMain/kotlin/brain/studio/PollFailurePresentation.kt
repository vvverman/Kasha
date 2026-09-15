package brain.studio

import brain.application.ApplicationPollFailure

/** Ошибка напоминаний остаётся в разделе задач, не перекрывая другой сценарий. */
internal fun pollFailureMessageKey(failure: ApplicationPollFailure): String? = when (failure) {
    ApplicationPollFailure.TRANSPORT -> "audioFailed"
    ApplicationPollFailure.CONTENT -> "actionFailed"
    ApplicationPollFailure.REMINDERS -> null
}
