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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.RegisterFcmRequest
import com.pingolin.note.util.SessionManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Registers this device's push token / UnifiedPush endpoint with the note
 * server, retrying with exponential backoff until it lands.
 *
 * WHY THIS EXISTS: a single fire-and-forget register from
 * [com.pingolin.note.service.NoteUnifiedPushReceiver.onNewEndpoint] has no
 * retry. One unlucky moment — offline when the distributor delivers the
 * endpoint, a 401 mid-refresh, a backend blip — and push stays dead until the
 * endpoint happens to change again or the user manually re-picks their
 * distributor.
 *
 * The failure is invisible too: [SessionManager.savePushSettings] writes the
 * endpoint locally BEFORE the register call, so a status read off that local
 * value claims success while the server has no way to reach the device.
 * `push_token_synced` is the real signal.
 *
 * Mirrors the Track Android app's worker of the same name; keep the two in
 * step.
 */
@HiltWorker
class PushTokenSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val noteApi: NoteApi,
    private val sessionManager: SessionManager,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "PushTokenSyncWorker"
        private const val WORK_NAME = "note_push_token_sync"

        /**
         * Schedule a (re)register. Safe to call whenever push settings change or
         * on app launch: [doWork] no-ops when there is nothing to do, and
         * REPLACE keeps a single chain rather than stacking retries.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val work = OneTimeWorkRequestBuilder<PushTokenSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, work)
        }

        /** Drop any pending retry — used at logout, where the row is being removed. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        if (!sessionManager.isLoggedIn()) {
            Log.d(TAG, "Not logged in — nothing to sync")
            return Result.success()
        }
        if (sessionManager.isPushTokenSynced()) {
            Log.d(TAG, "Push registration already synced")
            return Result.success()
        }

        val pushType = sessionManager.getPushType()
        val token = sessionManager.getPushToken()
        val endpoint = sessionManager.getPushEndpoint()

        if (pushType == null || (token == null && endpoint == null)) {
            Log.d(TAG, "No push settings to sync")
            return Result.success()
        }
        // The server rejects a unifiedpush row with no endpoint (it
        // requires a non-empty push_endpoint), so retrying it would
        // burn the whole backoff chain on a guaranteed 400.
        if (pushType == SessionManager.PUSH_TYPE_UNIFIEDPUSH && endpoint.isNullOrBlank()) {
            Log.w(TAG, "UnifiedPush selected but no endpoint yet — waiting for onNewEndpoint")
            return Result.success()
        }

        return try {
            val response = noteApi.registerFcmDevice(
                RegisterFcmRequest(
                    deviceId = sessionManager.getOrCreateDeviceId(),
                    // For UnifiedPush the fcm_token field carries the endpoint;
                    // the server routes on push_type, not on which field is set.
                    fcmToken = if (pushType == SessionManager.PUSH_TYPE_UNIFIEDPUSH) endpoint!! else token!!,
                    platform = "android",
                    appVersion = runCatching {
                        applicationContext.packageManager
                            .getPackageInfo(applicationContext.packageName, 0).versionName
                    }.getOrNull(),
                    pushType = pushType,
                    pushEndpoint = endpoint,
                ),
            )
            if (response.isSuccessful) {
                sessionManager.setPushTokenSynced(true)
                Log.i(TAG, "Push registration synced ($pushType)")
                Result.success()
            } else {
                // 4xx other than 401/429 is a malformed registration this device
                // cannot fix by repeating it — retrying would spin the backoff
                // forever. 401 is worth a retry: the token refresh may land first.
                val code = response.code()
                if (code in 400..499 && code != 401 && code != 429) {
                    Log.e(TAG, "Push register rejected with $code — not retrying")
                    Result.failure()
                } else {
                    Log.w(TAG, "Push register failed with $code — retrying")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Push register exception — retrying", e)
            Result.retry()
        }
    }
}
