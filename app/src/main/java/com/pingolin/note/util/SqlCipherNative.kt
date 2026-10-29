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

package com.pingolin.note.util

/**
 * SQLCipher's native library and its connection-pool cap, loaded exactly once, on
 * whatever thread first needs SQLCipher.
 *
 * Not in `NoteApplication.onCreate`: that would be a 5MB dlopen on the MAIN
 * thread of every launch for a database nothing opens until the sync runs,
 * off-Main and hundreds of milliseconds later. And not inside
 * `NoteDatabase.create()` either, which is why it lives here instead of inside
 * either caller:
 *
 * **`DatabaseRawKey` opens the database too**, to verify a candidate key, and it
 * does so BEFORE `NoteDatabase.create()` is reached. With the loader in
 * `create()`, that verification would hit `SQLiteConnection.nativeOpen` with no
 * library behind it — an `UnsatisfiedLinkError`, which is an `Error` and so slips
 * straight past a `catch (Exception)`. The visible symptom is `WM-WorkerWrapper:
 * Could not create Worker com.pingolin.note.worker.SyncWorker` on every launch:
 * the web UI is fine (it is a WebView and never touches Room), while the Room
 * mirror that widgets, reminders and the pinned-note notification read is quietly
 * dead.
 *
 * So: every entry point into SQLCipher calls [ensure] first. Kotlin's `by lazy`
 * is SYNCHRONIZED by default, so this stays one load per process however many
 * threads race for it.
 *
 * The pool cap has to stay attached to the load. `SQLiteConnectionPool` reads its
 * size at construction, so it must be set before the first OPEN rather than
 * merely before the first query — keeping the two in one place is what makes that
 * ordering impossible to get wrong. The default is 10 and EVERY connection pays
 * SQLCipher's key derivation on open; two is one writer plus one reader.
 */
object SqlCipherNative {

    private val loaded: Unit by lazy {
        System.loadLibrary("sqlcipher")
        net.zetetic.database.sqlcipher.SQLiteGlobal.setWALConnectionPoolSize(2)
    }

    /** Call before ANY use of net.zetetic.database.sqlcipher. */
    fun ensure() {
        loaded
    }
}
