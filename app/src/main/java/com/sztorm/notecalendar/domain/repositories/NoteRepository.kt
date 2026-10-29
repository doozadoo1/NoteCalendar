package com.sztorm.notecalendar.domain.repositories

import com.sztorm.notecalendar.data.models.NoteData
import java.time.LocalDate
import java.time.YearMonth

interface NoteRepository {
    fun add(note: NoteData)
    fun addAll(notes: List<NoteData>)
    fun update(note: NoteData)
    fun delete(note: NoteData)
    fun deleteAll(): Int
    fun getAll(): List<NoteData>
    fun getAllBy(date: LocalDate): List<NoteData>

    /** Returns a first note of the given date or null. Notes are ordered by time. */
    fun getBy(date: LocalDate): NoteData?
    fun getBy(yearMonth: YearMonth): List<NoteData>
}
