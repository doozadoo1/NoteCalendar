package com.sztorm.notecalendar.data

import com.sztorm.notecalendar.data.models.NoteData

/**
 * Orders notes of a single day for display: untimed notes first ("all day"),
 * followed by timed notes in chronological order.
 */
fun Iterable<NoteData>.sortedForDay(): List<NoteData> = sortedWith(
    compareBy<NoteData>(
        { it.noteTime.isNotEmpty() },
        { it.noteTime },
        { it.id }
    )
)
