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

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.pingolin.note.data.remote.api.AuthApi
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.LogoutRequest
import com.pingolin.note.data.remote.dto.UnregisterFcmRequest
import com.pingolin.note.util.PendingPushUnregisterStore
import com.pingolin.note.util.PushNotificationManager
import com.pingolin.note.util.SessionManager
import com.pingolin.note.worker.PushUnregisterWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The whole sign-out orchestration, shared by SettingsViewModel.logout() and
 * the web shell's sign-out scheme, so the page's "Sign out" runs the same
 * flow instead of stranding the shell on the in-WebView portal. Ordering is
 * the point of this class; see each step's comment. Runs on LocalDataWiper's
 * application-scoped coroutine, so callers may finish immediately.
 */
@Singleton
class LogoutOrchestrator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val sessionManager: SessionManager,
    private val syncRepository: SyncRepository,
    private val authApi: AuthApi,
    private val noteApi: NoteApi,
    private val localDataWiper: LocalDataWiper,
    private val pushNotificationManager: PushNotificationManager,
    private val pendingPushUnregisterStore: PendingPushUnregisterStore,
) {

    fun logout() {
        // FIRST and synchronously: from this instant isLoggedIn()
        // answers false, so AuthActivity's auto-forward (and the
        // router's session gate) cannot bounce the user back into
        // a half-wiped app while the flush/wipe below run on the
        // application scope.
        sessionManager.beginSignOut()

        // Snapshot the departing account's push identity BEFORE the wipe
        // clears it. If we can't reach the server to delete this device's
        // row now (offline / failure), PushUnregisterWorker replays the
        // delete with THIS account's token once it can, so a NEXT account
        // can't inherit its still-live push registration and receive its
        // share/reminder tickles.
        val deviceId = sessionManager.getOrCreateDeviceId()
        val departingToken = sessionManager.getAccessToken()
        val departingUserId = sessionManager.getUserId()
        val pushLane = sessionManager.getPushType()
        val hadPushRegistration = pushLane != null || sessionManager.getPushToken() != null

        // Flush -> revoke -> wipe, all on LocalDataWiper's appContext-scoped
        // coroutine so the immediate post-logout navigation (which cancels
        // viewModelScope) can't abort any step. The flush pushes unsynced
        // offline edits to the server BEFORE the wipe destroys them, while the
        // access token + vault key are still present (wipeBlocking clears them).
        // Best-effort + bounded: offline or a flaky network just falls back to
        // the wipe (the logout dialog warns about that).
        localDataWiper.wipe(
            preWipe = {
                if (isOnline()) {
                    runCatching {
                        // Bound the flush so a flaky network can't hang logout;
                        // paged pushes clear is_dirty incrementally, so partial
                        // progress is durable.
                        withTimeoutOrNull(FLUSH_TIMEOUT_MS) { syncRepository.pushPendingChanges() }
                    }.onFailure { Log.w(TAG, "pre-logout flush failed", it) }
                }
                // Tear down this device's FCM/UnifiedPush registration
                // while the access token is still valid (BEFORE the
                // authApi.logout() revoke below). The server keys device
                // rows by (user_id, device_id) and never rehomes them, so
                // without this the signed-out account's row keeps
                // dispatching its shares/reminders to this device and
                // they'd surface once another account signs in here.
                // Best-effort + bounded; the final sessionManager.clear()
                // drops the local push settings.
                var serverUnregisterDone = !hadPushRegistration // nothing registered → nothing to delete
                if (isOnline()) {
                    runCatching {
                        withTimeoutOrNull(PUSH_UNREGISTER_TIMEOUT_MS) {
                            val resp = runCatching {
                                noteApi.unregisterFcmDevice(UnregisterFcmRequest(deviceId))
                            }.onFailure { Log.w(TAG, "server push unregister failed", it) }
                                .getOrNull()
                            if (resp != null && (resp.isSuccessful || resp.code() == 404)) {
                                serverUnregisterDone = true
                            }
                            pushNotificationManager.unregister()
                        }
                    }.onFailure { Log.w(TAG, "push unregister failed", it) }
                }
                // If the server-row delete didn't complete (offline / failed / timed
                // out), defer it: persist the departing account's {deviceId, token,
                // lane} and let PushUnregisterWorker replay it on next connectivity
                // (and force a token rotation once safe) so a NEW account can't
                // inherit this device's live push token. Idempotent + self-clearing;
                // the record survives sessionManager.clear() (separate store).
                if (!serverUnregisterDone && departingToken != null) {
                    runCatching {
                        pendingPushUnregisterStore.save(
                            deviceId, departingToken, pushLane ?: SessionManager.PUSH_TYPE_FCM,
                            departingUserId,
                        )
                        PushUnregisterWorker.enqueue(appContext)
                    }.onFailure { Log.w(TAG, "deferring push unregister failed", it) }
                }
                // Server-side session revoke AFTER the flush —
                // revoking first would invalidate the token
                // mid-flush — and before the wipe. It names the
                // session by its refresh token, so an access
                // token that has already expired still ends it.
                // Bounded: a sign-out never waits long on the
                // network.
                val refreshToken = sessionManager.getRefreshToken()
                if (refreshToken != null || departingToken != null) {
                    runCatching {
                        withTimeoutOrNull(REVOKE_TIMEOUT_MS) { authApi.logout(LogoutRequest(refreshToken)) }
                    }.onFailure { Log.w(TAG, "server session revoke failed", it) }
                }
            },
            onComplete = { sessionManager.clear() },
        )
    }

    /** True when the device currently has a validated, internet-capable network. */
    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        private const val TAG = "LogoutOrchestrator"

        /** Upper bound on the pre-logout flush so a flaky network can't hang sign-out. */
        const val FLUSH_TIMEOUT_MS = 8_000L

        /** Upper bound on the pre-logout push unregister (server DELETE + provider
         *  deleteToken/unregister) so a flaky network can't hang sign-out. */
        const val PUSH_UNREGISTER_TIMEOUT_MS = 5_000L

        /** Upper bound on the server-side revoke: four seconds, as on iOS. */
        const val REVOKE_TIMEOUT_MS = 4_000L
    }
}
