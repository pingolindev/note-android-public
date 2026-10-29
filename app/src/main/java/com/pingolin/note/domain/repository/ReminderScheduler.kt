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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pingolin.note.data.local.dao.ReminderDao
import com.pingolin.note.data.local.entity.ReminderEntity
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ReminderDto
import com.pingolin.note.receiver.ReminderReceiver
import com.pingolin.note.util.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps device alarms in step with the server's reminders — the piece that
 * makes reminders fire like every other note app's do: locally, on time,
 * offline included.
 *
 * WHY THIS EXISTS. Reminders are server-side objects. Without this, no local
 * alarm would exist for a reminder created on the WEB, so delivery would
 * depend entirely on a server push arriving at the right moment, with network,
 * on a build with a working distributor. The libre build has no distributor.
 * The local `reminders` table needs a writer too: it is what the boot path
 * re-arms alarms from.
 *
 * This reconciles on every sync: pull the server's list, mirror it into the
 * local table, schedule an exact alarm for everything pending, cancel alarms
 * for anything dismissed or deleted elsewhere. A reminder whose time passed
 * while the device was off or offline schedules in the past, which
 * AlarmManager fires immediately — that is the missed-reminder delivery, and
 * the locally-persisted `fired` flag (set by [ReminderReceiver] via
 * [markFired]) is what keeps a delivered one from re-firing on every
 * subsequent sync.
 *
 * The alarm PendingIntent is keyed by the NOTE id — the product model is one
 * reminder per note (ReminderDao.getByItemId), and keeping the key stable
 * means alarms set by older builds are updated, not duplicated. The intent
 * carries ONLY the note id, never a title: the alarm outlives logout, and
 * ReminderReceiver re-resolves the title from Room at fire time, scoped to
 * whoever is signed in then.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val reminderDao: ReminderDao,
    private val noteApi: NoteApi,
    private val sessionManager: SessionManager,
) {

    /**
     * Reconcile local state + alarms against the server. Called from the tail
     * of every successful sync; quiet on any failure (the next sync retries,
     * and the alarms from the previous reconciliation stay armed meanwhile).
     */
    suspend fun refresh() {
        if (!sessionManager.isLoggedIn()) return
        val server = runCatching { noteApi.listReminders() }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()?.data
            ?: return
        val local = reminderDao.getAll().associateBy { it.id }
        val serverIds = server.mapTo(mutableSetOf()) { it.id }

        for (dto in server) {
            val triggerAt = parseTrigger(dto.triggerAt) ?: continue
            // Preserve a locally-recorded firing: the server has no
            // mark-fired call from this client, so without this a delivered
            // reminder would re-arm (in the past) and re-fire on every sync.
            val firedLocally = local[dto.id]?.fired == true
            val entity = ReminderEntity(
                id = dto.id,
                itemId = dto.itemId,
                triggerAt = triggerAt,
                repeatRule = dto.repeatRule,
                fired = dto.fired || firedLocally,
                dismissed = dto.dismissed,
            )
            reminderDao.upsert(entity)
            if (!entity.fired && !entity.dismissed) {
                schedule(entity.itemId, triggerAt.toEpochMilli())
            } else {
                cancel(entity.itemId)
            }
        }

        // Deleted or replaced elsewhere: drop the row and disarm its alarm.
        for (stale in local.values) {
            if (stale.id !in serverIds) {
                cancel(stale.itemId)
                reminderDao.delete(stale.id)
            }
        }
    }

    /**
     * Re-arm alarms from the LOCAL table only — the boot path. Android clears
     * every alarm on reboot; the table survives, so this needs no network,
     * which is the point: a reminder must fire on a phone that rebooted in a
     * dead zone. A fresh install with an empty table is covered by the first
     * sync's [refresh].
     */
    suspend fun rescheduleFromLocal() {
        for (r in reminderDao.getAll()) {
            if (!r.fired && !r.dismissed) schedule(r.itemId, r.triggerAt.toEpochMilli())
        }
    }

    /** A reminder this device just created — mirror and arm it immediately
     *  rather than waiting for the next sync. */
    suspend fun record(dto: ReminderDto) {
        val triggerAt = parseTrigger(dto.triggerAt) ?: return
        reminderDao.upsert(
            ReminderEntity(
                id = dto.id,
                itemId = dto.itemId,
                triggerAt = triggerAt,
                repeatRule = dto.repeatRule,
                fired = dto.fired,
                dismissed = dto.dismissed,
            ),
        )
        if (!dto.fired && !dto.dismissed) schedule(dto.itemId, triggerAt.toEpochMilli())
    }

    /**
     * The alarm for [itemId] just went off. Persist that fact so [refresh]
     * never re-arms it — the server cannot be told (no mark-fired endpoint
     * from this client), so the local flag is the only refire guard.
     */
    suspend fun markFired(itemId: String) {
        val row = reminderDao.getByItemId(itemId) ?: return
        if (!row.fired) reminderDao.upsert(row.copy(fired = true))
    }

    private fun parseTrigger(raw: String): Instant? =
        runCatching { Instant.parse(raw) }
            .onFailure { Log.w(TAG, "Unparseable reminder trigger: $raw") }
            .getOrNull()

    private fun schedule(itemId: String, triggerAtMillis: Long) {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntentFor(itemId)
        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pi,
            )
        } catch (e: SecurityException) {
            // SCHEDULE_EXACT_ALARM revoked by the user (API 31+ Settings
            // toggle). An inexact reminder still beats none.
            Log.w(TAG, "Exact alarms unavailable; scheduling inexact", e)
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    private fun cancel(itemId: String) {
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntentFor(itemId))
    }

    private fun pendingIntentFor(itemId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_NOTE_ID, itemId)
        }
        return PendingIntent.getBroadcast(
            context,
            itemId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val TAG = "ReminderScheduler"
    }
}
