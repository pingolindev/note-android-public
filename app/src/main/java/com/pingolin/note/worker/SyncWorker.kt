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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.local.dao.ReminderDao
import com.pingolin.note.domain.QuotaExceededException
import com.pingolin.note.domain.repository.SyncRepository
import com.pingolin.note.util.plainText
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Pushes dirty items and pulls remote changes on a schedule. Runs under
 * WorkManager so it survives app restarts and Doze.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncRepository: SyncRepository,
    private val itemDao: ItemDao,
    private val reminderDao: ReminderDao,
    private val quotaManager: com.pingolin.note.domain.repository.QuotaManager,
    private val syncStatus: com.pingolin.note.util.SyncStatus,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // Housekeep before sync. Cheap and prevents the items table
            // from accumulating soft-deleted rows forever (the user
            // empties trash from the UI but the DAO just flips
            // deleted=1) and stale reminders from filling the reminders
            // table after the user's notifications fire.
            housekeep()
            val result = syncRepository.sync()
            android.util.Log.i(TAG, "Sync complete: pushed=${result.pushed}, pulled=${result.pulled}, conflicts=${result.conflicts}")
            // A successful background sync clears any stale "couldn't reach
            // server" banner left by an earlier failed foreground refresh.
            syncStatus.clear()
            Result.success()
        } catch (e: QuotaExceededException) {
            // Don't retry — quota is a deterministic server decision
            // until tier changes. Refresh QuotaManager so the next UI
            // open shows the actual remaining count (likely 0/100) and
            // the Plan card reflects reality. Also surface the system
            // notification — workers run when no foreground UI is
            // around, so the in-app Premium sheet wouldn't reach the
            // user here.
            quotaManager.refresh()
            showQuotaNotification(applicationContext, e.plainText(QuotaExceededException.NOTE_LIMIT_TEXT))
            Result.failure()
        } catch (e: CancellationException) {
            // Worker was stopped (constraints lost, app killed, etc.). Let it
            // cancel cleanly — it's not a failure, and a returned Result is
            // ignored for a cancelled worker. Rethrow so it isn't logged as an
            // error or pointlessly retried.
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Sync failed", e)
            // Surface the failure so the list shows the cached-data banner,
            // consistent with a failed foreground refresh.
            syncStatus.markFailure()
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun housekeep() {
        val now = System.currentTimeMillis()
        try {
            // Hard-delete soft-deleted items older than 90 days. The user
            // already trashed them; we kept the rows for sync echo back
            // but no other client should still need them after 90d.
            val trashCutoff = now - TRASH_RETENTION_MS
            itemDao.purgeOldTrash(trashCutoff)

            // Drop reminders that fired or were dismissed >30 days ago.
            val reminderCutoff = now - REMINDER_RETENTION_MS
            reminderDao.purgeOldFired(reminderCutoff)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Housekeep failed; continuing with sync", e)
        }
    }

    private fun showQuotaNotification(context: Context, message: String) {
        val channelId = "quota_alerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Quota Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            )
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.pingolin.note.R.drawable.ic_notification)
            .setColor(androidx.core.content.ContextCompat.getColor(context, com.pingolin.note.R.color.primary))
            .setContentTitle("Upgrade Required")
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID_QUOTA, notification)
    }

    companion object {
        const val TAG = "NoteSync"
        private const val NOTIFICATION_ID_QUOTA = 9001
        private const val TRASH_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
        private const val REMINDER_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * Cancel every scheduled or in-flight sync — both the periodic worker
         * (unique name [TAG]) and the expedited immediate one
         * ("${TAG}_immediate"). Called by the account-switch / logout wipe
         * BEFORE it clears the DB so a sync that started for the outgoing
         * account can't upsert a just-fetched page into the incoming account's
         * session. Cancellation is cooperative/async, so SyncRepository also
         * re-checks the acting user at the upsert site.
         */
        fun cancelAllSync(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(TAG)
            wm.cancelUniqueWork("${TAG}_immediate")
        }

        fun enqueuePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncWork = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES, // Minimum periodic interval
                5, TimeUnit.MINUTES   // Flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    TAG,
                    ExistingPeriodicWorkPolicy.KEEP,
                    syncWork
                )
        }

        fun enqueueImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val builder = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .addTag("${TAG}_immediate")

            // Android 12+ expedited path so a "reconnect → drain queue"
            // sync doesn't wait behind other deferred work. Falls back
            // to a regular OneTimeWorkRequest when the OS denies quota.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }

            // KEEP is required: REPLACE aborts an in-flight drain mid-batch
            // (interrupting e.g. conflict resolution) and surfaces a spurious
            // "couldn't reach server" blip, so a flurry of onAvailable callbacks
            // during Wi-Fi reattach would keep restarting the sync instead of
            // coalescing into the running one. A finished unique work doesn't
            // block KEEP, so the next trigger still syncs; the 15-min periodic
            // worker backstops anything enqueued while one was already running.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "${TAG}_immediate",
                    ExistingWorkPolicy.KEEP,
                    builder.build(),
                )
        }
    }
}
