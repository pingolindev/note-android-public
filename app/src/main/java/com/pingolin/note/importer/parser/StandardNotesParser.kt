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

import com.google.gson.JsonParser
import com.pingolin.note.importer.model.ImportProgress
import com.pingolin.note.importer.model.ImportedNote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.InputStream

/**
 * Parses Standard Notes JSON export files.
 *
 * Standard Notes export structure:
 * {
 *   "items": [
 *     {
 *       "content_type": "Note",
 *       "content": {
 *         "title": "...",
 *         "text": "..."
 *       },
 *       "created_at": "2024-01-15T10:00:00.000Z",
 *       "updated_at": "2024-01-15T12:00:00.000Z"
 *     }
 *   ]
 * }
 */
class StandardNotesParser : NoteImportParser {

    override fun parse(inputStream: InputStream): Flow<ImportProgress> = flow {
        val json = inputStream.bufferedReader().readText()
        val root = JsonParser.parseString(json).asJsonObject
        val items = root.getAsJsonArray("items") ?: run {
            emit(ImportProgress.Error("Invalid Standard Notes export: missing 'items' array"))
            return@flow
        }

        var count = 0
        for (element in items) {
            val item = element.asJsonObject
            val contentType = item.get("content_type")?.asString ?: continue
            if (contentType != "Note") continue

            val content = item.getAsJsonObject("content") ?: continue
            val title = content.get("title")?.asString ?: ""
            val text = content.get("text")?.asString ?: ""

            val createdAt = parseIsoTimestamp(item.get("created_at")?.asString)
            val updatedAt = parseIsoTimestamp(item.get("updated_at")?.asString)

            val note = ImportedNote(
                title = title,
                body = text,
                noteType = "text",
                checklistItems = null,
                labels = emptyList(),
                color = null,
                isPinned = false,
                isArchived = false,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )

            count++
            emit(ImportProgress.Parsing(count, null, title))
            emit(ImportProgress.Note(note))
        }

        emit(ImportProgress.Done(count))
    }

    private fun parseIsoTimestamp(iso: String?): Long? {
        if (iso == null) return null
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
