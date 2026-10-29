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

package com.pingolin.note.util

import android.content.Context
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.RegisterFcmRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Push registration, outside any Activity: MainActivity is a router that
 * often finishes within one frame, and an activity-scoped
 * `lifecycleScope.launch` dies with it. Registration is process-global work,
 * so it runs on an app-scoped supervisor; callers fire and forget from
 * whichever activity is up.
 */
@Singleton
class PushRegistrar @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val sessionManager: SessionManager,
    private val pushManager: PushNotificationManager,
    private val noteApi: NoteApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun ensure() {
        if (!sessionManager.isLoggedIn()) return


        // Delegated to PushNotificationManager: picks UnifiedPush if a
        // distributor is installed, otherwise FCM. For UnifiedPush the
        // endpoint arrives asynchronously at NoteUnifiedPushReceiver
        // (which writes it to SessionManager and re-syncs to backend);
        // for FCM the token is in hand synchronously and we POST the
        // registration here.
        scope.launch {
            try {
                val result = pushManager.autoDetectAndRegister()
                if (result is PushNotificationManager.RegistrationResult.Success
                    && result.provider == PushNotificationManager.Provider.FCM
                    && result.token != null
                ) {
                    withContext(Dispatchers.IO) {
                        val response = noteApi.registerFcmDevice(
                            RegisterFcmRequest(
                                deviceId = sessionManager.getOrCreateDeviceId(),
                                fcmToken = result.token,
                                platform = "android",
                                appVersion = runCatching {
                                    appContext.packageManager
                                        .getPackageInfo(appContext.packageName, 0).versionName
                                }.getOrNull(),
                                pushType = SessionManager.PUSH_TYPE_FCM,
                                pushEndpoint = null,
                            )
                        )
                        if (response.isSuccessful) {
                            sessionManager.setPushTokenSynced(true)
                        }
                    }
                }
                // UnifiedPush registers from its receiver once
                // the distributor delivers an endpoint, so there
                // is nothing to POST here. But an endpoint we
                // already hold whose backend register never
                // landed would otherwise stay broken forever.
                // The worker no-ops when already synced, so this
                // is cheap on the happy path.
                if (!sessionManager.isPushTokenSynced()) {
                    com.pingolin.note.worker.PushTokenSyncWorker.schedule(appContext)
                }
            } catch (_: Exception) {
                // Non-fatal; next launch retries.
            }
        }
    }
}
