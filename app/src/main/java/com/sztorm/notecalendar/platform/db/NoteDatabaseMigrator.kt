package com.sztorm.notecalendar.platform.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import timber.log.Timber
import java.io.File

/**
 * Handles database migration from NoteCalendar 2.1.0 (schema version 3) to 2.2.0 (version 4).
 *
 * Version 4 allows multiple notes per day. Previously the `date` column of the [NoteData]
 * table carried a UNIQUE constraint, which SQLite cannot drop via `ALTER TABLE` itself.
 * This migrator rebuilds the table without the constraint while preserving all rows.
 *
 * It must run *before* [com.orm.SugarContext.init] so that Sugar ORM's own onUpgrade
 * (triggered by bumping the `VERSION` meta-data) can add the new `time` column afterwards
 * via its schema evolution.
 */
object NoteDatabaseMigrator {
    private const val DATABASE_NAME = "note_calendar.db"
    private const val NOTE_TABLE_NAME = "NoteData"
    private const val OLD_SCHEMA_VERSION = 3
    private const val NEW_SCHEMA_VERSION = 4

    fun migrate(context: Context) {
        try {
            migrateUnsafe(context)
        } catch (e: Exception) {
            Timber.e(e, "Notes database migration failed")
        }
    }

    private fun migrateUnsafe(context: Context) {
        val dbFile: File = context.getDatabasePath(DATABASE_NAME)
        if (!dbFile.exists()) return

        val db = SQLiteDatabase.openDatabase(
            dbFile.path, null, SQLiteDatabase.OPEN_READWRITE
        )
        try {
            if (db.version > OLD_SCHEMA_VERSION) return
            val createSql = readCreateSql(db) ?: return
            if (!createSql.contains(Regex("\\bUNIQUE\\b", RegexOption.IGNORE_CASE))) return

            Timber.i("Removing UNIQUE constraint from $NOTE_TABLE_NAME.")
            // Drop every inline UNIQUE constraint from the snapshot of the schema and
            // rebuild the table, copying the existing rows over.
            val cleanedSql = createSql.replace(Regex("\\bUNIQUE\\b", RegexOption.IGNORE_CASE), "")
            val columns = listOf("ID", "date", "text", "reminderDateTime")

            db.beginTransaction()
            try {
                db.execSQL("DROP TABLE IF EXISTS ${NOTE_TABLE_NAME}_migrated")
                db.execSQL(cleanedSql.replace(NOTE_TABLE_NAME, "${NOTE_TABLE_NAME}_migrated", false))
                db.execSQL(
                    "INSERT INTO ${NOTE_TABLE_NAME}_migrated (${columns.joinToString()}) " +
                        "SELECT ${columns.joinToString()} FROM $NOTE_TABLE_NAME"
                )
                db.execSQL("DROP TABLE $NOTE_TABLE_NAME")
                db.execSQL(
                    "ALTER TABLE ${NOTE_TABLE_NAME}_migrated RENAME TO $NOTE_TABLE_NAME"
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }

    private fun readCreateSql(db: SQLiteDatabase): String? {
        db.rawQuery(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(NOTE_TABLE_NAME)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }
}
