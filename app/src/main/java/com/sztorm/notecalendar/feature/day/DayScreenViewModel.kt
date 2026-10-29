package com.sztorm.notecalendar.feature.day

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sztorm.notecalendar.core.common.remainingDurationFromNow
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.time.Duration

enum class DayUiState {
    Viewing,
    EditingNote,
    AddingNote,
    EditingReminder,
}

class DayScreenViewModel(initialState: DayScreenState) : ViewModel() {
    var state by mutableStateOf(initialState)
        private set

    /** Replaces the notes of the currently shown (initial) date and selects a note to edit. */
    private fun notesCopy(
        notes: List<DayScreenNote>,
        affectedNoteIndex: Int?,
        resetBackup: Boolean = false,
        restoreReminderTime: Boolean = true
    ) = state.copy(
        notes = notes,
        affectedNoteIndex = affectedNoteIndex,
        noteBackup = if (resetBackup) null else state.noteBackup,
        remainingReminderTime = if (restoreReminderTime) {
            notes.mapNotNull { it.reminderDateTime }
                .filter { it > OffsetDateTime.now() }
                .minOrNull()
                ?.remainingDurationFromNow()
        } else null
    )

    fun onEvent(event: DayScreenEvent) {
        state = when (event) {
            is DayScreenEvent.UiStateChange -> state.copy(uiState = event.state)

            is DayScreenEvent.NoteChange -> notesCopy(
                notes = event.notes,
                affectedNoteIndex = event.affectedNoteIndex
            )

            is DayScreenEvent.DeleteNoteChange -> state.copy(
                notes = event.notes,
                affectedNoteIndex = null,
                noteBackup = event.deletedNote,
                remainingReminderTime = event.notes.mapNotNull { it.reminderDateTime }
                    .filter { it > OffsetDateTime.now() }
                    .minOrNull()
                    ?.remainingDurationFromNow()
            )

            is DayScreenEvent.RestoreNoteChange -> notesCopy(
                notes = event.notes,
                affectedNoteIndex = null,
                resetBackup = true
            )

            is DayScreenEvent.DateChange -> state.copy(
                notes = event.notes,
                prevNotes = event.prevNotes,
                nextNotes = event.nextNotes,
                affectedNoteIndex = null,
                noteBackup = null,
                remainingReminderTime = event.notes.mapNotNull { it.reminderDateTime }
                    .filter { it > OffsetDateTime.now() }
                    .minOrNull()
                    ?.remainingDurationFromNow()
            )

            is DayScreenEvent.ReminderRemainingTimeChange ->
                state.copy(remainingReminderTime = event.remainingTime)

            is DayScreenEvent.ReminderPermissionsChange ->
                state.copy(haveReminderPermissionsBeenDenied = event.areDenied)
        }
    }

    /** Returns the note currently being edited, added or having its reminder changed. */
    fun affectedNote(): DayScreenNote? {
        val index = state.affectedNoteIndex ?: return null

        return state.notes.getOrNull(index)
    }
}

class DayScreenViewModelFactory(val initialState: DayScreenState) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(DayScreenViewModel::class.java) ->
            DayScreenViewModel(initialState) as T

        else -> throw IllegalArgumentException("Invalid modelClass")
    }
}

sealed class DayScreenEvent {
    data class UiStateChange(val state: DayUiState) : DayScreenEvent()

    /**
     * Replaces the list of notes (of the initial date). [affectedNoteIndex] selects the note
     * being edited/added, or null to deselect. The backup note is left unchanged.
     */
    data class NoteChange(
        val notes: List<DayScreenNote>,
        val affectedNoteIndex: Int? = null
    ) : DayScreenEvent()

    /** Marks a note as deleted and stores it for possible restore. */
    data class DeleteNoteChange(
        val notes: List<DayScreenNote>,
        val deletedNote: DayScreenNote
    ) : DayScreenEvent()

    /** Restores a previously deleted note and clears the backup. */
    data class RestoreNoteChange(val notes: List<DayScreenNote>) : DayScreenEvent()

    data class DateChange(
        val notes: List<DayScreenNote>,
        val prevNotes: List<DayScreenNote>,
        val nextNotes: List<DayScreenNote>
    ) : DayScreenEvent()

    data class ReminderRemainingTimeChange(val remainingTime: Duration?) : DayScreenEvent()
    data class ReminderPermissionsChange(val areDenied: Boolean) : DayScreenEvent()
}

data class DayScreenState(
    val uiState: DayUiState,
    val initialDate: LocalDate,
    val notes: List<DayScreenNote>,
    val prevNotes: List<DayScreenNote>,
    val nextNotes: List<DayScreenNote>,
    /** Index into [DayScreenState.notes] of the note being edited/added, when any. */
    val affectedNoteIndex: Int? = null,
    val noteBackup: DayScreenNote? = null,
    val remainingReminderTime: Duration? = null,
    val haveReminderPermissionsBeenDenied: Boolean = false,
)
