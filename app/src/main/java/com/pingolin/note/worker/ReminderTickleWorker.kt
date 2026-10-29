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

package com.pingolin.note.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pingolin.note.service.NoteTickleHandler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Owns a reminder tickle long enough to finish it.
 *
 * A UnifiedPush broadcast receiver can be killed as soon as onReceive returns,
 * so a GlobalScope fetch started there usually works in a warm app and
 * disappears in a cold one. WorkManager supplies the process lifetime, network
 * constraint and coalescing that the reminder notification needs.
 */
@HiltWorker
class ReminderTickleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val tickleHandler: NoteTickleHandler,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "ReminderTickleWorker"
        private const val WORK_NAME = "note_reminder_tickle"

        fun schedule(context: Context) {
            val work = OneTimeWorkRequestBuilder<ReminderTickleWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, work)
        }
    }

    override suspend fun doWork(): Result = try {
        tickleHandler.handle(kind = "reminder", type = "tickle")
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "Reminder tickle failed", e)
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
