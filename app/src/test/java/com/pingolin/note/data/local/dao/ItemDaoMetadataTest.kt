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

package com.pingolin.note.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pingolin.note.data.local.NoteDatabase
import com.pingolin.note.data.local.entity.ItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * `getMetadataItems` against real SQLite, because the guarantee under test IS
 * the generated SQL.
 *
 * There is supposed to be one item_type=10 row per user, and nothing on the
 * device enforces it (rows from before the server limited an account to one
 * live row can coexist). Every client then has to pick, and picking arbitrarily
 * makes notebooks, share recipients and editor-mode pins appear to revert at
 * random. `ORDER BY updated_at DESC LIMIT 1` is the pick; these are the cases
 * that would go wrong without it.
 *
 * Robolectric + `inMemoryDatabaseBuilder` rather than the app's SQLCipher
 * helper: the question is which row Room's query returns, and that is the same
 * query either way.
 *
 * `sdk = [35]`, matching the other Robolectric tests here — the app targets 36
 * and Robolectric 4.14.1 has no framework jar for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ItemDaoMetadataTest {

    private lateinit var db: NoteDatabase
    private lateinit var dao: ItemDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NoteDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.itemDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    /** The rows only differ in `updated_at` and id, so which one comes back
     *  can only be the ordering. `decryptedContent` carries the id so a failure
     *  says which copy was served. */
    private fun metadataRow(id: String, updatedAt: String, deleted: Boolean = false) = ItemEntity(
        id = id,
        userId = "user-1",
        itemType = 10,
        encryptedContent = "ciphertext-$id",
        contentNonce = "nonce-$id",
        decryptedContent = """{"notebooks":[{"id":"$id","name":"$id"}]}""",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse(updatedAt),
        deleted = deleted,
    )

    @Test
    fun `getMetadataItems_returnsNewest_whenTheNewestRowWasInsertedLast`() = runBlocking {
        dao.upsert(metadataRow("old", "2026-01-01T00:00:00Z"))
        dao.upsert(metadataRow("middle", "2026-02-01T00:00:00Z"))
        dao.upsert(metadataRow("new", "2026-03-01T00:00:00Z"))

        assertEquals("new", dao.getMetadataItems().firstOrNull()?.id)
    }

    /**
     * The same three rows in the opposite order. Applying every metadata row a
     * pull meets would let the LAST row of the page win — correct only while
     * the server serves rows oldest-first. Insertion order must not be able to
     * decide.
     */
    @Test
    fun `getMetadataItems_returnsNewest_whenTheNewestRowWasInsertedFirst`() = runBlocking {
        dao.upsert(metadataRow("new", "2026-03-01T00:00:00Z"))
        dao.upsert(metadataRow("middle", "2026-02-01T00:00:00Z"))
        dao.upsert(metadataRow("old", "2026-01-01T00:00:00Z"))

        assertEquals("new", dao.getMetadataItems().firstOrNull()?.id)
    }

    /** A tombstone for the newest row hands the blob back to the newest row
     *  that is still live, rather than to a deleted one or to nothing. */
    @Test
    fun `getMetadataItems_skipsDeletedRows`() = runBlocking {
        dao.upsert(metadataRow("old", "2026-01-01T00:00:00Z"))
        dao.upsert(metadataRow("new-but-deleted", "2026-03-01T00:00:00Z", deleted = true))

        assertEquals("old", dao.getMetadataItems().firstOrNull()?.id)
    }

    /** Notes are not metadata, however new they are. */
    @Test
    fun `getMetadataItems_ignoresOtherItemTypes`() = runBlocking {
        dao.upsert(metadataRow("a-note", "2026-03-01T00:00:00Z").copy(itemType = 1))

        assertNull(dao.getMetadataItems().firstOrNull())
    }
}
