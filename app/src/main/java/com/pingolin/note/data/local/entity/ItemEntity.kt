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

package com.pingolin.note.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Local representation of an encrypted note item.
 *
 * Stores both the encrypted blob (for sync with server) and the decrypted
 * content (for local search and display). Decrypted content never leaves
 * the SQLCipher-encrypted database.
 *
 * ⚠️ The indices are load-bearing.
 *
 * Without them, `EXPLAIN QUERY PLAN` on the note-list query gives "SCAN items"
 * plus "USE TEMP B-TREE FOR ORDER BY" — a full table scan and a sort, on a
 * table whose rows carry both the base64 ciphertext AND the decrypted JSON.
 * Under SQLCipher every page that scan touches is decrypted first, so the cost
 * is worse on device than the raw row count suggests.
 *
 * ⚠️ NUMBERS BELOW ARE DEVICE NUMBERS. On a Galaxy A50 under real SQLCipher
 * the same 2,000-note query is ~247ms. Do not quote a host-JVM SQLite number
 * as a device number — the KDF and per-page decrypt make the device roughly
 * 10x worse, which is the whole reason these indices matter.
 *
 * `orders` is the load-bearing half. The query sorts `is_pinned DESC,
 * sort_order ASC, updated_at DESC`; an all-ASC index cannot be walked in two
 * directions, so SQLite materialises and sorts every matching row before
 * returning the first:
 *
 *     SEARCH items USING INDEX idx_items_active (item_type=? AND ...)
 *     USE TEMP B-TREE FOR LAST 2 TERMS OF ORDER BY
 *
 * Declaring the directions to match removes the sort step entirely — first
 * screen at 2,000 notes, LIMIT 40: **102.90ms → 0.87ms on device**. The
 * equality columns keep ASC because a `=` constraint is direction-agnostic.
 *
 * `idx_items_updated_at` serves getMaxUpdatedAt(), which runs on EVERY sync
 * pull and would otherwise scan the whole table to find one value. With the
 * index it plans as a covering index at 0.13ms.
 */
@Entity(
    tableName = "items",
    indices = [
        Index(
            name = "idx_items_active",
            value = ["item_type", "deleted", "is_archived", "is_pinned", "sort_order", "updated_at"],
            orders = [
                Index.Order.ASC, Index.Order.ASC, Index.Order.ASC,
                Index.Order.DESC, Index.Order.ASC, Index.Order.DESC,
            ],
        ),
        Index(name = "idx_items_updated_at", value = ["updated_at"]),
    ],
)
data class ItemEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    /** 1=note, 2=notebook, 3=label */
    @ColumnInfo(name = "item_type")
    val itemType: Int,

    /** Encrypted content blob (base64) — sent to/from server */
    @ColumnInfo(name = "encrypted_content")
    val encryptedContent: String,

    @ColumnInfo(name = "content_nonce")
    val contentNonce: String,

    /** Decrypted content JSON — only in local DB, never synced */
    @ColumnInfo(name = "decrypted_content")
    val decryptedContent: String? = null,

    /** Decrypted title — extracted for list display and search */
    @ColumnInfo(name = "title")
    val title: String? = null,

    @ColumnInfo(name = "version")
    val version: Long = 1,

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,

    @ColumnInfo(name = "deleted")
    val deleted: Boolean = false,

    @ColumnInfo(name = "circle_id")
    val circleId: String? = null,

    @ColumnInfo(name = "notebook_id")
    val notebookId: String? = null,

    @ColumnInfo(name = "content_size")
    val contentSize: Int = 0,

    /** Server-computed: this user has shared the note with someone. Cached so
     *  the grid can badge shared notes offline, like every other display field
     *  here. Refreshed on each sync; never written by a local edit. */
    @ColumnInfo(name = "shared_by_me")
    val sharedByMe: Boolean = false,

    /** Color extracted from decrypted content for local display */
    @ColumnInfo(name = "color")
    val color: String? = null,

    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false,

    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,

    /** Dirty flag: true = modified locally, needs sync push */
    @ColumnInfo(name = "is_dirty")
    val isDirty: Boolean = false,

    /** Per-note content key, wrapped with the vault key.
     *  Null on legacy notes that still use vault-key encryption directly. */
    @ColumnInfo(name = "wrapped_content_key")
    val wrappedContentKey: String? = null,

    @ColumnInfo(name = "wrapped_content_key_nonce")
    val wrappedContentKeyNonce: String? = null,

    /** User-chosen display order. Mirrors the `sort_order` field inside
     *  the encrypted note body (see [com.pingolin.note.domain.repository.NoteContent]) —
     *  the body still carries the truth for cross-device sync, but a
     *  Room column lets the DAO ORDER BY without parsing decrypted JSON. */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
) {
    /**
     * This row as the database hands it back: its times to the millisecond, which is all
     * [com.pingolin.note.data.local.Converters] stores. The server's times carry microseconds, so
     * a row built from an answer never equals the row read back that was written from that same
     * answer, unless it is compared like this.
     */
    fun asStored(): ItemEntity = copy(
        createdAt = Instant.ofEpochMilli(createdAt.toEpochMilli()),
        updatedAt = Instant.ofEpochMilli(updatedAt.toEpochMilli()),
    )
}
