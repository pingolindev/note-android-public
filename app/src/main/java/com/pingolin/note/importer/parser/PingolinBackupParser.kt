/*
 * Copyright (c) 2026 Pingolin LLC
 * This file is part of Pingolin Note.
 *
 * Pingolin Note is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Pingolin Note is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pingolin Note. If not, see <https://www.gnu.org/licenses/>.
 */

package com.pingolin.note.importer.parser

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pingolin.note.importer.model.ImportProgress
import com.pingolin.note.importer.model.ImportedChecklistItem
import com.pingolin.note.importer.model.ImportedNote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.InputStream

/**
 * Restores a Pingolin Note backup — the file this app's own export writes.
 *
 * Every other parser here reads a foreign format and is lossy by nature: Keep
 * has no notebooks, Evernote has no pin. This one is the round trip, so it is
 * the only parser that carries ids, sort order, editor mode, notebook
 * membership and the forward-compat extras bag. The id is the load-bearing one
 * — without it a restore cannot tell a note it already restored from a new one,
 * and running it twice doubles the account.
 *
 * Mirrors the Note web editor's `pingolinBackup.ts` parser. Same envelope, same
 * field names, same refusal rules. Don't drift: the whole point of the format
 * is that all three clients read and write one thing.
 */
class PingolinBackupParser(
    /** Supplied only when the picked file turned out to be sealed and the user
     *  has since entered it. Null on the first pass, which is how the sealed
     *  case is discovered at all. */
    private val passphrase: String? = null,
) : NoteImportParser {

    override fun parse(inputStream: InputStream): Flow<ImportProgress> = flow {
        val text = try {
            inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            android.util.Log.w("PingolinBackupParser", "Couldn't read the file: ${e.javaClass.simpleName}")
            emit(ImportProgress.Error(COULD_NOT_READ))
            return@flow
        }

        // Unseal first when the file is a container. Branch on CONTENT rather
        // than the filename, and let the SDK do it so every client agrees on a
        // format whose header is bound in as AEAD associated data.
        val body = if (looksLikeSealedBackup(text)) {
            if (passphrase.isNullOrEmpty()) {
                emit(ImportProgress.Error(NEEDS_PASSPHRASE))
                return@flow
            }
            try {
                uniffi.pingolin_mobile.openBackupFile(text, passphrase)
            } catch (e: Exception) {
                // The SDK's own words stay in the log; by
                // far the likeliest cause is the
                // passphrase.
                android.util.Log.w("PingolinBackupParser", "Couldn't open the sealed backup: ${e.javaClass.simpleName}")
                emit(ImportProgress.Error(COULD_NOT_OPEN))
                return@flow
            }
        } else {
            text
        }

        val root: JsonObject = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            android.util.Log.w("PingolinBackupParser", "Couldn't parse the file: ${e.javaClass.simpleName}")
            emit(ImportProgress.Error(COULD_NOT_READ))
            return@flow
        }

        val format = root.get("format")?.takeIf { it.isJsonPrimitive }?.asString
        if (format != BACKUP_FORMAT) {
            emit(ImportProgress.Error("That file is not a Pingolin Note backup. Use Import for notes from other apps."))
            return@flow
        }

        val version = root.get("formatVersion")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        if (version > BACKUP_FORMAT_VERSION) {
            // Half-restoring a format we do not understand produces an account
            // that looks whole and is not.
            emit(ImportProgress.Error("That backup was written by a newer version of Pingolin (format $version). Update the app, then restore it."))
            return@flow
        }

        val notes = root.getAsJsonArray("notes") ?: run {
            emit(ImportProgress.Error("That backup is missing its notes."))
            return@flow
        }

        // A note may only be filed into a notebook the SAME file defines. A
        // dangling id would file it somewhere the user has no way to open.
        val knownNotebooks: Set<String> = root.getAsJsonArray("notebooks")
            ?.mapNotNull { it.asJsonObjectOrNull()?.stringOrNull("id") }
            ?.toSet()
            ?: emptySet()

        var count = 0
        for (element in notes) {
            val obj = element.asJsonObjectOrNull() ?: continue
            count++
            val title = obj.stringOrNull("title") ?: ""
            emit(ImportProgress.Parsing(count, notes.size(), title))
            emit(ImportProgress.Note(toImportedNote(obj, knownNotebooks)))
        }

        emit(ImportProgress.Done(count))
    }

    /**
     * A backup note is already in this app's shape, so this is a copy rather
     * than a translation — but it still has to be defensive. The file is
     * whatever the user handed us, and a hand-edited or truncated backup must
     * not throw halfway through a restore.
     */
    private fun toImportedNote(o: JsonObject, knownNotebooks: Set<String>): ImportedNote {
        val rawType = o.stringOrNull("noteType")
        val checklist = o.getAsJsonArray("checklistItems")
            ?.mapNotNull { el ->
                val i = el.asJsonObjectOrNull() ?: return@mapNotNull null
                val text = i.stringOrNull("text") ?: return@mapNotNull null
                ImportedChecklistItem(text, i.boolOrNull("checked") ?: false)
            }

        return ImportedNote(
            title = o.stringOrNull("title") ?: "",
            body = o.stringOrNull("body") ?: "",
            noteType = if (rawType == "checklist") "checklist" else "text",
            checklistItems = checklist,
            labels = o.getAsJsonArray("labels")
                ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
                ?: emptyList(),
            color = o.stringOrNull("color"),
            isPinned = o.boolOrNull("isPinned") ?: false,
            isArchived = o.boolOrNull("isArchived") ?: false,
            createdAt = parseIsoTimestamp(o.stringOrNull("createdAt")),
            updatedAt = parseIsoTimestamp(o.stringOrNull("updatedAt")),
            // A malformed id is worse than none: it would be rejected on sync
            // and LOSE the note, where dropping it only costs idempotence for
            // that one row.
            id = o.stringOrNull("id")?.takeIf { UUID_RE.matches(it) },
            sortOrder = o.get("sortOrder")?.takeIf { it.isJsonPrimitive }?.let {
                runCatching { it.asInt }.getOrNull()
            },
            editMode = o.stringOrNull("editMode")?.takeIf { it == "rich" || it == "markdown" },
            notebookId = o.stringOrNull("notebookId")?.takeIf { knownNotebooks.contains(it) },
            extras = o.getAsJsonObject("extras")?.let { extrasOf(it) } ?: emptyMap(),
            // `hasAttachments` is READ FROM the backup and deliberately NOT
            // restored. A backup names attachments but does not carry their
            // bytes, so the restored note genuinely has none — and there is no
            // attachment row for it to point at either. Setting the flag would
            // render the attachment strip for files that do not exist, which is
            // worse than showing nothing: it reads as data loss inside the app
            // rather than an honest gap the restore already warned about. Do not
            // "fix" this by wiring the flag through.
        )
    }

    /** org.json-compatible values, matching what `NoteContent.extras` holds. */
    private fun extrasOf(o: JsonObject): Map<String, Any?> =
        o.entrySet().associate { (k, v) -> k to jsonToAny(v) }

    private fun jsonToAny(e: JsonElement): Any? = when {
        e.isJsonNull -> null
        e.isJsonPrimitive -> e.asJsonPrimitive.let { p ->
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> p.asNumber
                else -> p.asString
            }
        }
        e.isJsonArray -> e.asJsonArray.map { jsonToAny(it) }
        e.isJsonObject -> extrasOf(e.asJsonObject)
        else -> null
    }

    private fun parseIsoTimestamp(iso: String?): Long? {
        if (iso == null) return null
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        if (isJsonObject) asJsonObject else null

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.boolOrNull(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    companion object {
        /** Keep in step with the web editor's
         * `backupFormat.ts`.
         */
        const val BACKUP_FORMAT = "pingolin-note-backup"
        /** Emitted as an Error message when the file is sealed and no
         * passphrase was supplied. A caller matches on it to prompt for
         * the passphrase instead of showing a dead end. */
        const val NEEDS_PASSPHRASE = "That backup is encrypted. Enter its passphrase to restore it."
        /** A file that could not be read or parsed: this app's words,
         * never the parser's. */
        const val COULD_NOT_READ = "Couldn't read that file."
        /** A sealed backup the SDK would not open. The SDK's own sentence for
         *  its likeliest cause, a wrong passphrase, as this app's line. */
        const val COULD_NOT_OPEN = "That backup could not be opened. Check the passphrase."
        const val BACKUP_FORMAT_VERSION = 2

        private val UUID_RE =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

/**
 * Whether a picked file is a sealed container, decided WITHOUT the native SDK.
 *
 * The crypto belongs in one shared place — that is the whole point of the SDK
 * module — but the detection is a single field compare, and doing it through
 * the binding would make every plain-backup parse load
 * `libpingolin_mobile.so`. On a JVM unit test there is no such library, so a
 * pure-parsing class would become untestable; on a device it is wasted work on
 * the common path. Detect here, decrypt there.
 */
internal fun looksLikeSealedBackup(text: String): Boolean = runCatching {
    val root = com.google.gson.JsonParser.parseString(text)
    root.isJsonObject &&
        root.asJsonObject.get("format")?.takeIf { it.isJsonPrimitive }?.asString ==
        "pingolin-encrypted-backup"
}.getOrDefault(false)
