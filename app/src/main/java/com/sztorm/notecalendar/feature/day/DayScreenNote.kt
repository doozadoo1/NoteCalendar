package com.sztorm.notecalendar.feature.day

import androidx.compose.ui.text.input.TextFieldValue
import com.sztorm.notecalendar.domain.models.ReminderNote
import com.sztorm.notecalendar.data.models.NoteData
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

data class DayScreenNote(
    val date: LocalDate,
    val textValue: TextFieldValue,
    val reminderDateTime: OffsetDateTime? = null,
    val time: LocalTime? = null,
    /** Database id, null when the note is not persisted yet. */
    val id: Long? = null
) {
    fun toReminderNoteOrNull() = reminderDateTime
        ?.let { ReminderNote(date, textValue.text, reminderDateTime, time) }

    companion object {
        fun from(noteData: NoteData): DayScreenNote = DayScreenNote(
            date = LocalDate.parse(noteData.date),
            textValue = TextFieldValue(text = noteData.text),
            reminderDateTime = noteData.reminderDateTime
                .ifEmpty { null }
                ?.let { OffsetDateTime.parse(it) },
            time = noteData.noteTime
                .ifEmpty { null }
                ?.let { LocalTime.parse(it) },
            id = noteData.id
        )
    }
}
