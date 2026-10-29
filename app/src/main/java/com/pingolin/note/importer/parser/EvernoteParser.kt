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
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * Parses Evernote ENEX export files.
 *
 * ENEX structure:
 * <en-export>
 *   <note>
 *     <title>...</title>
 *     <content><![CDATA[...XHTML...]]></content>
 *     <created>20240115T100000Z</created>
 *     <updated>20240115T120000Z</updated>
 *     <tag>label1</tag>
 *     <tag>label2</tag>
 *   </note>
 * </en-export>
 */
class EvernoteParser : NoteImportParser {

    override fun parse(inputStream: InputStream): Flow<ImportProgress> = flow {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(inputStream.reader())

        var count = 0
        var title = ""
        var content = ""
        var created: Long? = null
        var updated: Long? = null
        val tags = mutableListOf<String>()
        var inNote = false
        var currentTag = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag == "note") {
                        inNote = true
                        title = ""
                        content = ""
                        created = null
                        updated = null
                        tags.clear()
                    }
                }

                XmlPullParser.TEXT -> {
                    if (inNote) {
                        when (currentTag) {
                            "title" -> title = parser.text ?: ""
                            "content" -> content = parser.text ?: ""
                            "created" -> created = parseEnexTimestamp(parser.text)
                            "updated" -> updated = parseEnexTimestamp(parser.text)
                            "tag" -> parser.text?.let { tags.add(it) }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "note" && inNote) {
                        inNote = false
                        val body = stripHtml(content)
                        val note = ImportedNote(
                            title = title,
                            body = body,
                            noteType = "text",
                            checklistItems = null,
                            labels = tags.toList(),
                            color = null,
                            isPinned = false,
                            isArchived = false,
                            createdAt = created,
                            updatedAt = updated,
                        )
                        count++
                        emit(ImportProgress.Parsing(count, null, title))
                        emit(ImportProgress.Note(note))
                    }
                    currentTag = ""
                }
            }
            eventType = parser.next()
        }

        emit(ImportProgress.Done(count))
    }

    /** Strip HTML/XHTML tags to plain text. */
    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<div[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
            .replace(Regex("\n{3,}"), "\n\n")
    }

    /** Parse Evernote timestamp format: 20240115T100000Z */
    private fun parseEnexTimestamp(ts: String?): Long? {
        if (ts == null || ts.length < 15) return null
        return try {
            val year = ts.substring(0, 4).toInt()
            val month = ts.substring(4, 6).toInt() - 1
            val day = ts.substring(6, 8).toInt()
            val hour = ts.substring(9, 11).toInt()
            val minute = ts.substring(11, 13).toInt()
            val second = ts.substring(13, 15).toInt()

            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.set(year, month, day, hour, minute, second)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } catch (_: Exception) {
            null
        }
    }
}
