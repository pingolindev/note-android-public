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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pingolin.note.data.local.entity.ReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Query("SELECT * FROM reminders WHERE fired = 0 AND dismissed = 0 ORDER BY trigger_at ASC")
    fun observePending(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE item_id = :itemId")
    suspend fun getByItemId(itemId: String): ReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity)

    /** Every row, fired and dismissed included — the reconciliation set for
     *  ReminderScheduler.refresh(), which needs the full picture to know what
     *  to disarm as well as what to arm. */
    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<ReminderEntity>

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: String)

    /** Drop reminders that have already fired or been dismissed and
     *  whose trigger time is older than [cutoff]. Called from the
     *  sync worker so the table doesn't grow unbounded. */
    @Query("DELETE FROM reminders WHERE (fired = 1 OR dismissed = 1) AND trigger_at < :cutoff")
    suspend fun purgeOldFired(cutoff: Long)
}
