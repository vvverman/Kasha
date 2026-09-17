package brain.studio

import brain.application.ApplicationPollFailure

/** Ошибка напоминаний остаётся в разделе задач, не перекрывая другой сценарий. */
internal fun pollFailureMessageKey(failure: ApplicationPollFailure): String? = when (failure) {
    ApplicationPollFailure.TRANSPORT -> "audioFailed"
    ApplicationPollFailure.CONTENT -> "loadFailed"
    ApplicationPollFailure.REMINDERS -> null
}

/** Известная причина сохраняется; сбой запуска не называется ошибкой произвольного действия. */
internal fun actionFailureMessageKey(message: String?, initialized: Boolean): String =
    message?.takeIf(Copy::has) ?: if (initialized) "actionFailed" else "loadFailed"
