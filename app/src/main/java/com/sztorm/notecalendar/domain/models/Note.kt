package com.sztorm.notecalendar.domain.models

import com.sztorm.notecalendar.data.models.NoteData
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

data class Note(
    val date: LocalDate,
    val text: String,
    val reminderDateTime: OffsetDateTime? = null,
    val time: LocalTime? = null
) {
    fun toNoteData() = NoteData(
        date = date.toString(),
        text = text,
        reminderDateTime = reminderDateTime?.toString() ?: "",
        time = time?.toString() ?: ""
    )

    @Suppress("unused")
    fun toReminderNoteOrNull() = reminderDateTime
        ?.let { ReminderNote(date, text, reminderDateTime, time) }
}

fun NoteData.toNote() = Note(
    date = LocalDate.parse(date),
    text = text,
    reminderDateTime = reminderDateTime
        .ifEmpty { null }
        ?.let { OffsetDateTime.parse(it) },
    time = noteTime
        .ifEmpty { null }
        ?.let { LocalTime.parse(it) }
)
