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

import com.pingolin.note.importer.model.ImportProgress
import com.pingolin.note.importer.model.ImportedNote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Parses plain text and Markdown files.
 * Accepts either a single .txt/.md file or a ZIP containing multiple files.
 */
class PlainTextParser(private val isZip: Boolean) : NoteImportParser {

    override fun parse(inputStream: InputStream): Flow<ImportProgress> = flow {
        if (isZip) {
            var count = 0
            val zip = ZipInputStream(inputStream)
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && isTextFile(entry.name)) {
                    try {
                        val content = zip.bufferedReader().readText()
                        val name = entry.name
                            .substringAfterLast("/")
                            .substringBeforeLast(".")
                        val note = textToNote(name, content)
                        count++
                        emit(ImportProgress.Parsing(count, null, note.title))
                        emit(ImportProgress.Note(note))
                    } catch (_: Exception) {
                        // Skip unreadable entries
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            emit(ImportProgress.Done(count))
        } else {
            val content = inputStream.bufferedReader().readText()
            val note = textToNote("Imported note", content)
            emit(ImportProgress.Parsing(1, 1, note.title))
            emit(ImportProgress.Note(note))
            emit(ImportProgress.Done(1))
        }
    }

    private fun textToNote(filename: String, content: String): ImportedNote {
        // Use first line as title if it looks like a heading
        val lines = content.split("\n", limit = 2)
        val firstLine = lines[0].trimStart('#', ' ')
        val body = if (lines.size > 1) lines[1].trimStart() else ""

        return ImportedNote(
            title = firstLine.ifBlank { filename },
            body = body,
            noteType = "text",
            checklistItems = null,
            labels = emptyList(),
            color = null,
            isPinned = false,
            isArchived = false,
            createdAt = null,
            updatedAt = null,
        )
    }

    private fun isTextFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")
    }
}
