package ru.vrmn.kasha.android

/** Вход — уже рассчитанные Core абсолютные сроки. Календарных правил здесь нет. */
internal object AndroidReminderPolicy {
    fun nextWakeAt(instants: Sequence<Long>, now: Long): Long? =
        instants.filter { it > 0L && it < Long.MAX_VALUE }.minOrNull()?.coerceAtLeast(now)
}
