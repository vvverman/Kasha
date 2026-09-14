package brain.studio

import brain.model.Note

/** UI передаёт команды единому API Core, не обращаясь к аудиоадаптерам напрямую. */
internal suspend fun StudioState.cancelActiveRecording(): Boolean = cancelRecordingInCore()

internal suspend fun StudioState.seekPlayback(seconds: Double): Boolean = seekPlaybackInCore(seconds)

internal suspend fun StudioState.moveNotePin(note: Note, delta: Int): Boolean =
    moveNotePinInCore(note, delta)
