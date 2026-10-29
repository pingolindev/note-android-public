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
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What SQLite actually DOES with the DAO's SQL.
 *
 * WHY THIS FILE EXISTS
 *   Every index in [com.pingolin.note.data.local.entity.ItemEntity] exists for a performance
 *   reason, and that reason is written down in a KDoc comment which nothing checks. A comment
 *   cannot notice a migration that drops an index, a WHERE clause that grows a column the
 *   index does not carry, or an ORDER BY term appended to a query — all of which turn a SEARCH
 *   into a SCAN silently, in a way no correctness test can see because the rows coming back
 *   are identical either way: right answer, wrong cost, green suite.
 *
 *   `EXPLAIN QUERY PLAN` is the countable form of the invariant. It is deterministic — no
 *   timing, no load sensitivity, no flake — and it runs against the schema Room really builds
 *   from the real entity annotations.
 *
 * ON EMPTY TABLES
 *   These plans are taken with no rows and no ANALYZE, which is deliberate: with no
 *   sqlite_stat1 the planner uses its fixed default estimates, so the answer depends only on
 *   the schema and the SQL and cannot drift with test data. It is also the pessimistic case —
 *   an index the planner picks with no statistics is one it will still pick with them.
 *
 * WHY THE INDEX DECLARES ITS SORT DIRECTIONS
 *   `observeActiveNotes` sorts `is_pinned DESC, sort_order ASC, updated_at DESC`. Against an
 *   all-ASC `idx_items_active` its plan is:
 *
 *     SEARCH items USING INDEX idx_items_active (item_type=? AND deleted=? AND is_archived=?),
 *     USE TEMP B-TREE FOR RIGHT PART OF ORDER BY
 *
 *   SQLite can walk an index backwards, but not change direction part-way, so everything after
 *   the first ORDER BY term has to be sorted — and because it is a sort, it has to visit EVERY
 *   matching row before it can return the first one. Measured on a Galaxy A50 at 2,000 notes,
 *   first screen with LIMIT 40: **102.90ms → 0.87ms** once the directions match.
 *
 *   `@Index(orders = ...)` declares them, so the assertion below pins the ABSENCE of any temp
 *   B-tree. If someone drops `orders` the sort comes back and this fails, which is the entire
 *   point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ItemDaoQueryPlanTest {

    private lateinit var db: NoteDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NoteDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun teardown() = db.close()

    /** The `detail` column of EXPLAIN QUERY PLAN, one entry per plan step. */
    private fun plan(sql: String): List<String> =
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) }
        }

    private fun assertPlan(name: String, sql: String, requirement: String, ok: (List<String>) -> Boolean) {
        val p = plan(sql)
        assertTrue("$name must $requirement, but SQLite planned:\n  ${p.joinToString("\n  ")}", ok(p))
    }

    /** A step that reads the table itself rather than an index — the shape a
     *  lost index degrades into. "SCAN items USING INDEX x" is an index scan
     *  and is NOT this. */
    private fun List<String>.hasBareTableScan() =
        any { it.startsWith("SCAN items") && !it.contains("USING") }

    // ---- The note list -----------------------------------------------------
    //
    // A Flow: it re-runs on every write to `items`, so every sync page, every
    // edit and every pin re-executes it. It is the query that most has to stay
    // off a table scan.

    @Test
    fun `the note list query is served by its index`() {
        val sql = "SELECT * FROM items WHERE item_type = 1 AND deleted = 0 AND is_archived = 0 " +
            "ORDER BY is_pinned DESC, sort_order ASC, updated_at DESC"
        assertPlan("observeActiveNotes", sql, "use idx_items_active") { p ->
            p.any { it.contains("idx_items_active") }
        }
        assertPlan("observeActiveNotes", sql, "never scan the table") { p -> !p.hasBareTableScan() }
        // The three equality predicates must be satisfied BY the
        // index. Losing that (a new column in the WHERE, a dropped
        // index) is the slowdown this whole file exists to catch, and
        // it shows up here as the index not being credited with the
        // constraints.
        assertPlan("observeActiveNotes", sql, "resolve its filter through the index") { p ->
            p.any { it.contains("SEARCH items USING INDEX idx_items_active") && it.contains("item_type=?") }
        }
        // The sort must come ENTIRELY from the index — no temp B-tree of any
        // kind. SQLite spells a partial sort several ways ("RIGHT PART OF",
        // "LAST 2 TERMS OF") depending on version, so match the stem rather
        // than any one phrasing. Dropping `orders` from @Index brings the sort
        // back and fails here. See the KDoc above.
        assertPlan("observeActiveNotes", sql, "get its whole ORDER BY from the index") { p ->
            p.none { it.contains("TEMP B-TREE") }
        }
    }

    @Test
    fun `the note count is answered from the index alone`() {
        // COUNT(*) must not touch the table: a covering index means the whole
        // answer comes out of idx_items_active.
        assertPlan(
            "countActiveNotes",
            "SELECT COUNT(*) FROM items WHERE item_type = 1 AND deleted = 0 AND is_archived = 0",
            "be answered by a covering index",
        ) { p -> p.any { it.contains("COVERING INDEX idx_items_active") } }
    }

    // ---- Sync --------------------------------------------------------------

    @Test
    fun `the sync cursor does not scan the table`() {
        // getMaxUpdatedAt runs on EVERY sync pull (see ItemEntity's KDoc for
        // idx_items_updated_at). MAX() over an indexed column is a single index
        // lookup; without the index it is a full scan of every note the user
        // owns.
        assertPlan("getMaxUpdatedAt", "SELECT MAX(updated_at) FROM items", "use idx_items_updated_at") { p ->
            p.any { it.contains("idx_items_updated_at") }
        }
        assertPlan("getMaxUpdatedAt", "SELECT MAX(updated_at) FROM items", "never scan the table") { p ->
            !p.hasBareTableScan()
        }
    }

    @Test
    fun `the push page reads in index order rather than sorting`() {
        // This is an index SCAN, not a SEARCH — every row is visited and
        // filtered on is_dirty, because no index carries is_dirty. What the
        // assertion protects is that it at least gets its ORDER BY from
        // idx_items_updated_at instead of materialising and sorting the user's
        // whole note table on every push page.
        val sql = "SELECT * FROM items WHERE is_dirty = 1 AND deleted = 0 " +
            "ORDER BY updated_at ASC LIMIT 50 OFFSET 0"
        assertPlan("getDirtyItemsPaged", sql, "take its order from idx_items_updated_at") { p ->
            p.any { it.contains("idx_items_updated_at") } && p.none { it.contains("TEMP B-TREE") }
        }
    }

    @Test
    fun `a fetch by id uses the primary key`() {
        // The single most-executed query in the app — every open, every save,
        // every conflict check. It must be a primary-key lookup.
        assertPlan("getById", "SELECT * FROM items WHERE id = 'x'", "search on the primary key") { p ->
            p.any { it.startsWith("SEARCH items USING INDEX sqlite_autoindex_items_1") }
        }
    }
}
