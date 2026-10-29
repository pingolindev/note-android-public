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

package com.pingolin.note.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pingolin.note.service.PinnedNoteService
import com.pingolin.note.domain.repository.ReminderScheduler
import com.pingolin.note.worker.SyncWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restores pinned-note notifications and background sync after device boot.
 */
class BootReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun reminderScheduler(): ReminderScheduler
        fun itemDao(): com.pingolin.note.data.local.dao.ItemDao
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Re-enqueue periodic sync
        SyncWorker.enqueuePeriodicSync(context)

        // Notifications don't survive a reboot — re-post one per pinned
        // note. Runs outside the Hilt graph, so the database is opened
        // directly.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Hilt singleton via the EntryPoint above,
                // not a database opened here: a direct open
                // pays the full SQLCipher KDF inside the
                // BOOT_COMPLETED window, and closing it
                // afterwards would be wrong (Room's close is
                // one-way; it kills Flow invalidation for
                // the process). Constructor injection is
                // unavailable in a receiver;
                // EntryPointAccessors is not.
                EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
                    .itemDao()
                    .getAllActiveNotes()
                    .filter { it.isPinned }
                    .forEach { PinnedNoteService.pinNote(context, it) }
            } catch (e: Exception) {
                android.util.Log.w("BootReceiver", "Pinned-note restore failed", e)
            }
            // Alarms do not survive a reboot; the reminders table does. Re-arm
            // from LOCAL state only — no network, so a reminder still fires on
            // a phone that rebooted in a dead zone. Separate try: a failure
            // here must not be masked by, or mask, the pinned-note restore.
            try {
                EntryPointAccessors.fromApplication(
                    context.applicationContext, Deps::class.java,
                ).reminderScheduler().rescheduleFromLocal()
            } catch (e: Exception) {
                android.util.Log.w("BootReceiver", "Reminder re-arm failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
