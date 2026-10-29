package com.sztorm.notecalendar.data.models

import com.orm.SugarRecord

data class NoteData(
    val date: String = "",
    val text: String = "",
    val reminderDateTime: String = "",
    // Optional time of day in ISO-8601 format (e.g. "09:30"). Empty means "no time set".
    // Allow it to be stored as nullable because schema migration via ALTER TABLE
    // leaves the column NULL for pre-existing rows.
    val time: String? = ""
) : SugarRecord() {
    /** Normalized time value: "" when the note has no time assigned. */
    val noteTime: String
        get() = time.orEmpty()
}
