package com.sztorm.notecalendar.feature.settings.notes

import android.util.Base64
import com.sztorm.notecalendar.core.common.Base64String
import com.sztorm.notecalendar.core.common.EncryptionParameters
import com.sztorm.notecalendar.core.common.EncryptionType
import com.sztorm.notecalendar.core.common.decrypt
import com.sztorm.notecalendar.core.common.encrypt
import com.sztorm.notecalendar.core.common.toBase64
import com.sztorm.notecalendar.core.common.toList
import com.sztorm.notecalendar.core.logging.AppLogger
import com.sztorm.notecalendar.core.logging.LogTags
import com.sztorm.notecalendar.domain.models.Note
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/**
 * Backup file formats.
 *
 * V1 stores `{"date", "text"}` pairs.
 * V2 adds an optional `"time"` (ISO-8601 time-of-day string) field to each note.
 *
 * Use V2 for new exports; V1 files are still importable.
 */
sealed class NotesBackupFile(val version: String) {
    abstract fun toJson(): String

    /** Common decryption facade implemented by every encrypted backup. */
    interface Decryptable {
        val salt: ByteArray
        val iv: IvParameterSpec
        fun decryptedNotes(secretKey: SecretKey, logger: AppLogger? = null): List<Note>?
    }

    /** Shared V1/V2 note-list JSON conversion helpers. */
    companion object {
        fun noteToJson(note: Note): JSONObject = JSONObject().apply {
            put("date", note.date.toString())
            put("text", note.text.toBase64(Charsets.UTF_8, Base64.NO_WRAP))
            put("time", note.time?.toString() ?: "")
        }

        fun List<Note>.toJsonArray(): String = joinToString(
            prefix = "[",
            postfix = "]",
            separator = ", "
        ) { noteToJson(it).toString() }

        fun JSONArray.toNotes(): List<Note> = toList()
            .mapNotNull { element ->
                val note = element as? JSONObject

                if (note == null) {
                    null
                } else {
                    val date = note.getString("date")
                    val text = note.getString("text").let { textBase64 ->
                        Base64.decode(textBase64, Base64.NO_WRAP).toString(Charsets.UTF_8)
                    }
                    val time = runCatching {
                        note.getString("time")
                    }.getOrDefault("")

                    Note(
                        date = LocalDate.parse(date),
                        text = text,
                        time = time.ifEmpty { null }?.let { LocalTime.parse(it) }
                    )
                }
            }

        fun fromJson(json: String): NotesBackupFile? = try {
            val jsonObject = JSONObject(json)
            val version = jsonObject.getString("version")

            when (version) {
                "1.0" -> readV1(jsonObject)
                "2.0" -> readV2(jsonObject)
                else -> null
            }
        } catch (_: JSONException) {
            null
        }

        private fun readV1(jsonObject: JSONObject): NotesBackupFile.V1? {
            val encryptionTypeInt = jsonObject.getInt("encryptionType")

            return when (EncryptionType.entries.getOrNull(encryptionTypeInt)) {
                EncryptionType.None -> {
                    val notes = jsonObject
                        .getJSONArray("notes")
                        .toList()
                        .mapNotNull {
                            val note = it as? JSONObject

                            if (note == null) {
                                null
                            } else {
                                val date = note.getString("date")
                                val text = Base64String(
                                    text = note.getString("text"),
                                    encodingFlags = Base64.NO_WRAP
                                ).decoded()
                                    .toString(Charsets.UTF_8)

                                Note(date = LocalDate.parse(date), text = text)
                            }
                        }
                    V1.Plain(notes = notes)
                }

                EncryptionType.Aes256 -> {
                    V1.Aes256Encrypted(
                        salt = Base64String(jsonObject.getString("salt"), Base64.NO_WRAP)
                            .decoded(),
                        iv = IvParameterSpec(
                            Base64String(jsonObject.getString("iv"), Base64.NO_WRAP)
                                .decoded()
                        ),
                        notes = Base64String(jsonObject.getString("notes"), Base64.NO_WRAP)
                    )
                }

                else -> null
            }
        }

        private fun readV2(jsonObject: JSONObject): NotesBackupFile.V2? {
            val encryptionTypeInt = jsonObject.getInt("encryptionType")

            return when (EncryptionType.entries.getOrNull(encryptionTypeInt)) {
                EncryptionType.None -> {
                    V2.Plain(notes = jsonObject.getJSONArray("notes").toNotes())
                }

                EncryptionType.Aes256 -> {
                    V2.Aes256Encrypted(
                        salt = Base64String(jsonObject.getString("salt"), Base64.NO_WRAP)
                            .decoded(),
                        iv = IvParameterSpec(
                            Base64String(jsonObject.getString("iv"), Base64.NO_WRAP)
                                .decoded()
                        ),
                        notes = Base64String(jsonObject.getString("notes"), Base64.NO_WRAP)
                    )
                }

                else -> null
            }
        }
    }

    sealed class V1(val encryptionType: EncryptionType) : NotesBackupFile(version = "1.0") {
        class Plain(val notes: List<Note>) : V1(encryptionType = EncryptionType.None) {
            private fun notesToJson() = notes.joinToString(
                prefix = "[",
                postfix = "]",
                separator = ", "
            ) { (date, text) ->
                val textBase64 = text.toBase64(Charsets.UTF_8, Base64.NO_WRAP)

                """{"date": "$date", "text": "$textBase64" }"""
            }

            override fun toJson(): String =
                StringBuilder().apply {
                    appendLine('{')
                    append("""    "version": """").append(version).appendLine("\",")
                    append("""    "encryptionType": """).append(encryptionType.ordinal)
                        .appendLine(",")
                    append("""    "notes": """).append(notesToJson()).appendLine()
                    appendLine('}')
                }.toString()

            fun encrypted(parameters: EncryptionParameters.Aes) = Aes256Encrypted(
                salt = parameters.salt,
                iv = parameters.iv,
                notes = encrypt(notesToJson().toByteArray(Charsets.UTF_8), parameters)
            )
        }

        class Aes256Encrypted(
            override val salt: ByteArray,
            override val iv: IvParameterSpec,
            val notes: Base64String
        ) : V1(encryptionType = EncryptionType.Aes256), Decryptable {
            override fun toJson(): String = StringBuilder().apply {
                appendLine('{')
                append("""    "version": """").append(version).appendLine("\",")
                append("""    "encryptionType": """).append(encryptionType.ordinal)
                    .appendLine(",")
                append("""    "salt": """").append(salt.toBase64(Base64.NO_WRAP))
                    .appendLine("\",")
                append("""    "iv": """").append(iv.iv.toBase64(Base64.NO_WRAP))
                    .appendLine("\",")
                append("""    "notes": """").append(notes).appendLine("\"")
                appendLine('}')
            }.toString()

            fun decrypted(secretKey: SecretKey, logger: AppLogger? = null): Plain? {
                val parameters = EncryptionParameters.Aes(
                    salt = salt,
                    iv = iv,
                    key = secretKey
                )
                decrypt(notes, parameters).onSuccess { notesJsonInBytes ->
                    runCatching {
                        JSONArray(notesJsonInBytes.toString(Charsets.UTF_8))
                            .toList()
                            .mapNotNull {
                                val note = it as? JSONObject

                                if (note == null) {
                                    null
                                } else {
                                    val date = note.getString("date")
                                    val text = note.getString("text").let { textBase64 ->
                                        Base64
                                            .decode(textBase64, Base64.NO_WRAP)
                                            .toString(Charsets.UTF_8)
                                    }
                                    Note(date = LocalDate.parse(date), text = text)
                                }
                            }
                    }.onSuccess {
                        logger?.info("${LogTags.CRYPTO} Notes decryption succeeded.")
                        return Plain(it)
                    }
                    logger?.error(message = "${LogTags.FILE_IO} Decrypted notes wrong JSON format.")
                    return null
                }
                logger?.error(message = "${LogTags.CRYPTO} Notes decryption failed.")
                return null
            }

            override fun decryptedNotes(secretKey: SecretKey, logger: AppLogger?): List<Note>? =
                decrypted(secretKey, logger)?.notes
        }
    }

    sealed class V2(val encryptionType: EncryptionType) : NotesBackupFile(version = "2.0") {
        class Plain(val notes: List<Note>) : V2(encryptionType = EncryptionType.None) {
            override fun toJson(): String =
                StringBuilder().apply {
                    appendLine('{')
                    append("""    "version": """").append(version).appendLine("\",")
                    append("""    "encryptionType": """).append(encryptionType.ordinal)
                        .appendLine(",")
                    append("""    "notes": """).append(notes.toJsonArray()).appendLine()
                    appendLine('}')
                }.toString()

            fun encrypted(parameters: EncryptionParameters.Aes) = Aes256Encrypted(
                salt = parameters.salt,
                iv = parameters.iv,
                notes = encrypt(notes.toJsonArray().toByteArray(Charsets.UTF_8), parameters)
            )
        }

        class Aes256Encrypted(
            override val salt: ByteArray,
            override val iv: IvParameterSpec,
            val notes: Base64String
        ) : V2(encryptionType = EncryptionType.Aes256), Decryptable {
            override fun toJson(): String = StringBuilder().apply {
                appendLine('{')
                append("""    "version": """").append(version).appendLine("\",")
                append("""    "encryptionType": """).append(encryptionType.ordinal)
                    .appendLine(",")
                append("""    "salt": """").append(salt.toBase64(Base64.NO_WRAP))
                    .appendLine("\",")
                append("""    "iv": """").append(iv.iv.toBase64(Base64.NO_WRAP))
                    .appendLine("\",")
                append("""    "notes": """").append(notes).appendLine("\"")
                appendLine('}')
            }.toString()

            fun decrypted(secretKey: SecretKey, logger: AppLogger? = null): Plain? {
                val parameters = EncryptionParameters.Aes(
                    salt = salt,
                    iv = iv,
                    key = secretKey
                )
                decrypt(notes, parameters).onSuccess { notesJsonInBytes ->
                    runCatching {
                        JSONArray(notesJsonInBytes.toString(Charsets.UTF_8)).toNotes()
                    }.onSuccess {
                        logger?.info("${LogTags.CRYPTO} Notes decryption succeeded.")
                        return Plain(it)
                    }
                    logger?.error(message = "${LogTags.FILE_IO} Decrypted notes wrong JSON format.")
                    return null
                }
                logger?.error(message = "${LogTags.CRYPTO} Notes decryption failed.")
                return null
            }

            override fun decryptedNotes(secretKey: SecretKey, logger: AppLogger?): List<Note>? =
                decrypted(secretKey, logger)?.notes
        }
    }
}
