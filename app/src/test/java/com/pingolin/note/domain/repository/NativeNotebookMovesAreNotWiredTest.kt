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

package com.pingolin.note.domain.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app does not move a note between notebooks in native code, and must not start to by
 * calling the native functions below.
 *
 * Notes are edited and moved by the page the app shows (the Note web editor). The page
 * carries a move through whenever it is sent: a card remembers where the SERVER has its note
 * until the old notebook's people have lost it, the note has a new key, and the new
 * notebook's people have it (the web editor's notebook-move logic). None of the native
 * functions below does that:
 *
 *  - `NoteRepository.updateNote` settles the sharing once, straight after the local write,
 *    whether or not the PUT reached the server; the later push (`retryDirtyItems`) shares
 *    nothing. A note moved out of a shared notebook with no connection would stay with that
 *    notebook's people;
 *  - `NotebookRepository.addNoteToNotebook` and `removeNoteFromNotebook` change the notebook
 *    on the row and nothing else;
 *  - `NotebookRepository.deleteNotebook` moves the children out whether or not its
 *    best-effort unshare did anything.
 *
 * Nothing calls them. This keeps it so: whoever wires one up meets this test first, and
 * makes the function carry a move through before taking its name off the list below.
 *
 * The same for a note MADE natively. `NoteRepository.createNote` passes a note in a shared
 * notebook on before the note is on the server, which fails; its two callers make notes in
 * no notebook, and any further caller has to be checked here.
 */
class NativeNotebookMovesAreNotWiredTest {

    private val roots = listOf("src/main/java", "src/gms/java", "src/libre/java")

    /** Every line of the app's own Kotlin that is not a comment line, with its file's name. */
    private fun code(): List<Pair<String, String>> = roots.flatMap { root ->
        File(root).walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().map { it.trim() }
                .filter { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
                .map { file.name to it }
        }.toList()
    }

    private val notWired = listOf(
        "NoteRepository.kt" to "updateNote",
        "NotebookRepository.kt" to "addNoteToNotebook",
        "NotebookRepository.kt" to "removeNoteFromNotebook",
        "NotebookRepository.kt" to "deleteNotebook",
    )

    @Test
    fun `the sources this reads are there`() {
        // Run from somewhere else, every scan below would find nothing and pass.
        for (root in roots) assertTrue("$root is not a directory from ${File(".").absolutePath}", File(root).isDirectory)
        assertTrue(code().size > 1000)
    }

    @Test
    fun `each of the native moves is still declared where this says`() {
        // Renamed or removed, its line below would guard nothing: take it off the list then.
        val lines = code()
        for ((file, name) in notWired) {
            val declared = lines.count { (f, line) -> f == file && Regex("""\bsuspend fun $name\(""").containsMatchIn(line) }
            assertEquals("$file declares `suspend fun $name(`", 1, declared)
        }
    }

    @Test
    fun `nothing in the app calls a native move`() {
        val lines = code()
        for ((_, name) in notWired) {
            val call = Regex("""\b$name\s*\(""")
            val declaration = Regex("""\bfun\s+$name\s*\(""")
            val calls = lines.filter { (_, line) -> call.containsMatchIn(line) && !declaration.containsMatchIn(line) }
                .map { (file, line) -> "$file: $line" }
            assertEquals(
                "`$name` is called, and it does not carry a move through (see this test's notes): " +
                    "a note moved out of a shared notebook would stay with that notebook's people",
                emptyList<String>(),
                calls,
            )
        }
    }

    @Test
    fun `the only notes made natively are in no notebook`() {
        val call = Regex("""\bcreateNote\s*\(""")
        val declaration = Regex("""\bfun\s+createNote\s*\(""")
        val calls = code().filter { (_, line) -> call.containsMatchIn(line) && !declaration.containsMatchIn(line) }
            .map { (file, line) -> "$file: $line" }.sorted()
        assertEquals(
            "a new caller of createNote: if it can name a notebook, the note has to be passed on to that " +
                "notebook's people AFTER it is on the server, which createNote does not do",
            listOf(
                "AttachmentUploader.kt: noteRepository.createNote(NoteContent(extras = mapOf(\"has_attachments\" to true)))",
                "LockScreenCaptureActivity.kt: noteRepository.createNote(contentFor(titleText, bodyText, checklistItems))",
            ),
            calls,
        )
        // `contentFor` is the lock screen's own, and names no notebook: nothing in its file does.
        val lockScreen = File("src/main/java/com/pingolin/note/ui/LockScreenCaptureActivity.kt").readText()
        assertTrue(lockScreen.contains("private fun contentFor("))
        assertFalse("the lock screen's capture names a notebook", lockScreen.contains("notebookId"))
    }
}
