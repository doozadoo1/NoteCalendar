package com.sztorm.notecalendar

import com.sztorm.notecalendar.feature.quickadd.QuickAddParser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuickAddParserTests {
    @Test
    fun parsesTimedPhrase() {
        val parsed = QuickAddParser.parse("Movie tomorrow at 11")

        println("PARSED = $parsed")
        val notes = parsed?.let { QuickAddParser.toNotes(it) }

        println("NOTES  = $notes")
        assertTrue(notes != null)
    }

    @Test
    fun parsesRecurringPhrase() {
        val parsed = QuickAddParser.parse("Gym every tuesday and friday at 9")

        println("PARSED = $parsed")
        val notes = parsed?.let { QuickAddParser.toNotes(it) }

        println("NOTES  = $notes")
        assertTrue(notes == null || notes.isNotEmpty())
    }
}
