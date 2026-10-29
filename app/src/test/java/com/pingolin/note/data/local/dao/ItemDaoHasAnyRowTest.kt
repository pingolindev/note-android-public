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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * `hasAnyRow` against real SQLite: a sign-in with no saved account id treats any row this
 * device holds as another account's, so the probe must see every row — archived, soft-deleted,
 * dirty, metadata — and none on an empty database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ItemDaoHasAnyRowTest {

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

    private fun row(id: String, itemType: Int = 1, deleted: Boolean = false, archived: Boolean = false) = ItemEntity(
        id = id, userId = "u", itemType = itemType, encryptedContent = "e", contentNonce = "n",
        createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, deleted = deleted, isArchived = archived,
    )

    @Test
    fun `an empty database holds no row, and any row at all counts`() = runBlocking {
        assertFalse(dao.hasAnyRow())
        for (r in listOf(row("a", deleted = true), row("b", archived = true), row("c", itemType = 10), row("d", itemType = 2))) {
            db.clearAllTables()
            dao.upsert(r)
            assertTrue(r.id, dao.hasAnyRow())
        }
    }
}
