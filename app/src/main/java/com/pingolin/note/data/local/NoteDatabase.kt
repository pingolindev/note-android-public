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

package com.pingolin.note.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.local.dao.ReminderDao
import com.pingolin.note.data.local.entity.ItemEntity
import com.pingolin.note.data.local.entity.ReminderEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Room database encrypted with SQLCipher.
 * Passphrase stored in Android Keystore (hardware-backed when available).
 *
 * Schema mirrors the server-side items table — both store encrypted blobs.
 * The local DB additionally stores the decrypted content for offline search.
 */
@Database(
    entities = [
        ItemEntity::class,
        ReminderEntity::class,
    ],
    version = 7,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN notebook_id TEXT")
            }
        }

        // Per-note content keys. Columns are nullable so legacy rows
        // (encrypted directly with the vault key) stay readable; new
        // saves populate them.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN wrapped_content_key TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN wrapped_content_key_nonce TEXT")
            }
        }

        // sort_order column for drag-to-reorder. Existing rows all start
        // at 0, so they tie and the query's secondary `updated_at DESC`
        // sort decides their order — the list looks unchanged until the
        // user first drags, which rewrites sort_order 0..N across the
        // whole visible list.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Server-computed share state for the "Shared" badge. Defaults to 0
        // (false) for existing rows; the next /sync/pull fills in the real
        // value, so the badge simply appears a moment later rather than a
        // migration having to guess it.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN shared_by_me INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Add the indices the `items` table never had.
         *
         * IF NOT EXISTS because Room creates them itself on a fresh install; this
         * migration is only for databases that already exist. Costs about 80KB
         * against a 13.6MB database at 2,000 notes.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_items_active ON items " +
                        "(item_type, deleted, is_archived, is_pinned, sort_order, updated_at)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_items_updated_at ON items (updated_at)"
                )
            }
        }

        /**
         * Re-create `idx_items_active` with sort directions that match the query.
         *
         * 5→6 declared all six columns ASC. `observeActiveNotes` sorts
         * `is_pinned DESC, sort_order ASC, updated_at DESC`, and SQLite cannot
         * walk one index in two directions, so it sorted every matching row in a
         * temp B-tree before returning the first — 102.90ms on a Galaxy A50 at
         * 2,000 notes, against 0.87ms once the directions line up.
         *
         * DROP then CREATE, not CREATE IF NOT EXISTS: the index already exists
         * under this exact name from 5→6, so IF NOT EXISTS would silently keep
         * the all-ASC one and Room's schema validation would then fail on the
         * `orders` mismatch. The equality columns stay ASC — a `=` constraint is
         * direction-agnostic, and only the ORDER BY tail needs to match.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS idx_items_active")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_items_active ON items " +
                        "(item_type ASC, deleted ASC, is_archived ASC, " +
                        "is_pinned DESC, sort_order ASC, updated_at DESC)"
                )
            }
        }

        /** The on-disk name, shared so callers can locate the file itself —
         *  DatabaseRawKey needs it to read the salt out of the header. */
        const val DB_NAME = "note.db"

        fun create(context: Context, passphrase: ByteArray): NoteDatabase {
            com.pingolin.note.util.SqlCipherNative.ensure()
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(
                context.applicationContext,
                NoteDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(factory)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }
    }
}
