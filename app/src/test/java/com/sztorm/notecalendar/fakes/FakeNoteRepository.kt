package com.sztorm.notecalendar.fakes

import com.sztorm.notecalendar.core.common.yearMonth
import com.sztorm.notecalendar.data.sortedForDay
import com.sztorm.notecalendar.data.models.NoteData
import com.sztorm.notecalendar.domain.repositories.NoteRepository
import java.time.LocalDate
import java.time.YearMonth

class FakeNoteRepository(notes: List<NoteData>) : NoteRepository {
    private val notes = notes.toMutableList()

    override fun add(note: NoteData) {
        note.id.takeIf { it != 0L }?.let { id ->
            val index = notes.indexOfFirst { it.id == id }
            if (index >= 0) {
                notes[index] = note
                return
            }
        }
        notes.add(note)
    }

    override fun addAll(notes: List<NoteData>) {
        notes.forEach { add(it) }
    }

    override fun update(note: NoteData) {
        val index = notes.indexOfFirst { it.id == note.id }
        if (index >= 0) {
            notes[index] = note
        } else {
            notes.add(note)
        }
    }

    override fun delete(note: NoteData) {
        notes.removeIf { it.id == note.id && note.id != 0L }
    }

    override fun deleteAll(): Int {
        val size = notes.size
        notes.clear()

        return size
    }

    override fun getAll(): List<NoteData> = notes.toList()

    override fun getAllBy(date: LocalDate): List<NoteData> =
        notes.filter { it.date == date.toString() }.sortedForDay()

    override fun getBy(date: LocalDate): NoteData? = getAllBy(date).firstOrNull()

    override fun getBy(yearMonth: YearMonth): List<NoteData> =
        notes
            .filter { LocalDate.parse(it.date).yearMonth == yearMonth }
            .map { it }
}
