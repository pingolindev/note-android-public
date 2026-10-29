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
import com.pingolin.note.importer.model.ImportedChecklistItem
import com.pingolin.note.importer.model.ImportedNote
import com.pingolin.note.importer.util.ColorMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Parses Google Keep exports from Google Takeout (ZIP containing per-note JSON files).
 *
 * Keep JSON structure:
 * - title, textContent (text notes), listContent (checklists)
 * - labels[].name, color, isPinned, isArchived, isTrashed
 * - userEditedTimestampUsec (microseconds)
 */
class GoogleKeepParser : NoteImportParser {

    override fun parse(inputStream: InputStream): Flow<ImportProgress> = flow {
        val zip = ZipInputStream(inputStream)
        var count = 0

        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.endsWith(".json")) {
                try {
                    val json = zip.bufferedReader().readText()
                    val note = parseKeepJson(json)
                    if (note != null) {
                        count++
                        emit(ImportProgress.Parsing(count, null, note.title))
                        emit(ImportProgress.Note(note))
                    }
                } catch (_: Exception) {
                    // Skip malformed entries
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }

        emit(ImportProgress.Done(count))
    }

    private fun parseKeepJson(json: String): ImportedNote? {
        val obj = JsonParser.parseString(json).asJsonObject

        // Skip trashed notes
        if (obj.has("isTrashed") && obj.get("isTrashed").asBoolean) return null

        val title = obj.get("title")?.asString ?: ""

        // Determine note type and content
        val hasListContent = obj.has("listContent") &&
            obj.getAsJsonArray("listContent").size() > 0

        val noteType: String
        val body: String
        val checklistItems: List<ImportedChecklistItem>?

        if (hasListContent) {
            noteType = "checklist"
            val items = obj.getAsJsonArray("listContent").map { item ->
                val itemObj = item.asJsonObject
                ImportedChecklistItem(
                    text = itemObj.get("text")?.asString ?: "",
                    checked = itemObj.get("isChecked")?.asBoolean ?: false,
                )
            }
            checklistItems = items
            body = items.joinToString("\n") { (if (it.checked) "[x] " else "[ ] ") + it.text }
        } else {
            noteType = "text"
            body = obj.get("textContent")?.asString ?: ""
            checklistItems = null
        }

        // Labels
        val labels = if (obj.has("labels")) {
            obj.getAsJsonArray("labels").mapNotNull { it.asJsonObject.get("name")?.asString }
        } else {
            emptyList()
        }

        // Color mapping
        val color = ColorMapper.fromKeepColor(obj.get("color")?.asString)

        // Timestamps (Keep uses microseconds)
        val timestampUsec = obj.get("userEditedTimestampUsec")?.asLong
        val timestampMs = timestampUsec?.let { it / 1000 }

        return ImportedNote(
            title = title,
            body = body,
            noteType = noteType,
            checklistItems = checklistItems,
            labels = labels,
            color = color,
            isPinned = obj.get("isPinned")?.asBoolean ?: false,
            isArchived = obj.get("isArchived")?.asBoolean ?: false,
            createdAt = timestampMs,
            updatedAt = timestampMs,
        )
    }
}
