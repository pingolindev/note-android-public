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
import com.pingolin.note.data.remote.dto.UnregisterFcmRequest
import com.pingolin.note.util.PendingPushUnregisterStore
import com.pingolin.note.util.PushNotificationManager
import com.pingolin.note.util.SessionManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Replays a push-device unregister that couldn't be completed against the server
 * at logout / account-switch time (offline, or a failed call). Runs on next
 * connectivity ([NetworkType.CONNECTED]).
 *
 * Closes a cross-account leak: without this, an OFFLINE logout leaves the
 * departing account's server-side device row alive AND the FCM token unrotated,
 * so the next account re-registers the SAME token and inherits the departing
 * account's share/reminder tickles (NoteFirebaseMessagingService only gates on
 * `isLoggedIn()`). The two teardowns below neutralise it:
 *   1. delete the departing account's server row (authenticated with ITS OWN
 *      persisted token — the current session can't authorize deleting it); and
 *   2. force a provider-side token rotation so a NEW sign-in gets a fresh token
 *      instead of inheriting the departed one — but ONLY when nothing is
 *      currently registered, so we never knock out the CURRENT account's push.
 *
 * Idempotent: a terminal run clears the record, so a re-run is a no-op.
 */
@HiltWorker
class PushUnregisterWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val noteApi: NoteApi,
    private val store: PendingPushUnregisterStore,
    private val pushNotificationManager: PushNotificationManager,
    private val sessionManager: SessionManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pending = store.get() ?: return Result.success()

        // Belt-and-braces: if the account that armed this teardown is the one
        // signed in on THIS device right now (it logged out and came back before we
        // ran, or an account switch was aborted and kept it), the row we'd delete —
        // keyed by (user_id, device_id) — is its OWN fresh re-registration. Deleting
        // it would silently break the returned account's push. Skip the delete and
        // clear the record. (The login/abort disarm normally cancels us first; this
        // covers the race where we'd already started.)
        pending.userId?.let { armedFor ->
            if (com.pingolin.note.util.AccountIds.same(sessionManager.getUserId(), armedFor)) {
                store.clear()
                return Result.success()
            }
        } ?: run {
            // Null-armed record: the departing account had signed in via
            // the fail-closed no-user path of older builds
            // (bootstrapSession's clearUserId), or its saved id is no
            // UUID, so we never captured its id — and the login-side
            // disarm can't match it either (it early-returns on a null
            // getUserId). We therefore can't tell a genuinely-departed
            // null-user account apart from that SAME account having
            // re-registered here. If a signed-in, push-registered account
            // exists on this device now, deleting with the armed token
            // could knock out ITS OWN fresh (user_id, device_id) row — the
            // token still authenticates as the same server-side user — so
            // preserve the current push and clear. A truly-departed row is
            // left to the provider token rotation / server-side dedup on
            // the next registration. isLoggedIn() (NOT getUserId(), which
            // a re-logged-in fail-closed account leaves null) is the "an
            // account is signed in here" signal.
            if (sessionManager.isLoggedIn() && sessionManager.getPushType() != null) {
                store.clear()
                return Result.success()
            }
        }

        val serverDone = try {
            val resp = noteApi.unregisterFcmDeviceWithAuth(
                authorization = "Bearer ${pending.token}",
                request = UnregisterFcmRequest(pending.deviceId),
            )
            // 2xx = deleted; 404 = already gone; 401/403 = the departed token is
            // dead, so we can never delete the row — all terminal, stop retrying.
            resp.isSuccessful || resp.code() == 404 || resp.code() == 401 || resp.code() == 403
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "deferred server unregister failed: ${e.message}")
            false
        }

        if (!serverDone) {
            // Transient (network / 5xx) — back off and retry, bounded so a
            // permanently-unreachable server can't keep the record forever.
            return if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                // Giving up on deleting the departed server row, but still rotate
                // the provider token (under the same "nothing configured" guard as
                // the success path) so a returning / next account can't inherit the
                // departed token even though its row survives. Skipping the
                // rotation here would restore the whole leak on an exhausted retry.
                maybeRotateIfUnconfigured(pending.lane)
                store.clear()
                Result.success()
            }
        }

        maybeRotateIfUnconfigured(pending.lane)

        store.clear()
        return Result.success()
    }

    /**
     * Rotate the provider-side push token for [lane] so a NEW sign-in on this
     * device gets a fresh token instead of inheriting the departed account's —
     * but ONLY when nothing is currently configured here, otherwise we'd tear
     * down the CURRENT account's live registration (its device row is already
     * correct; rotating its token silently breaks delivery until it re-registers).
     *
     * The sentinel is the push TYPE ([SessionManager.getPushType] == null), NOT
     * the token field: a UnifiedPush registration stores no token
     * ([SessionManager.savePushSettings] is called with `token = null`), so a
     * `getPushToken() == null` guard reads a live UnifiedPush-registered account
     * as "unconfigured" and unregisters it. getPushType() is null only when
     * neither lane is configured, which is exactly the case we want to rotate.
     * Best-effort.
     */
    private suspend fun maybeRotateIfUnconfigured(lane: String) {
        if (sessionManager.getPushType() == null) {
            runCatching { pushNotificationManager.rotateProviderToken(lane) }
                .onFailure { Log.w(TAG, "provider token rotation failed: ${it.message}") }
        }
    }

    companion object {
        const val TAG = "PushUnregister"
        private const val MAX_ATTEMPTS = 5

        /**
         * Enqueue a replay bounded by [Constraints] to run on next connectivity.
         * Unique + REPLACE: the latest teardown supersedes any still-pending one
         * (the store is single-record, latest-wins).
         */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val work = OneTimeWorkRequestBuilder<PushUnregisterWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, work)
        }
    }
}
