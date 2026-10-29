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
import androidx.security.crypto.EncryptedSharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable, encrypted record of a push-device unregister that couldn't be
 * completed against the server at logout / account-switch time (offline, or a
 * failed / timed-out call).
 *
 * It deliberately lives in its OWN EncryptedSharedPreferences file — NOT
 * `note_session` — so it survives BOTH a full logout ([SessionManager.clear])
 * and the account-switch wipe. [com.pingolin.note.worker.PushUnregisterWorker]
 * then replays the delete on the next connectivity, removing the DEPARTING
 * account's device row so a NEXT account on this device can't inherit its
 * still-live push registration and receive its share/reminder tickles.
 *
 * The record carries the departing account's OWN access token: the server keys
 * device rows by `(user_id, device_id)` and authorizes the DELETE from the
 * token's user, so the current session (signed out, or a different account)
 * cannot delete account A's row. Single-record, latest-wins — the token is
 * short-lived and the worker clears the record as soon as it reaches a terminal
 * outcome, so nothing lingers at rest longer than one reconnect.
 */
@Singleton
class PendingPushUnregisterStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = SecurePrefs.open(context, "note_pending_push_unregister")

    data class Pending(
        val deviceId: String,
        val token: String,
        val lane: String,
        /** The DEPARTING account's user id, captured at teardown. Lets the disarm
         *  path (a login/bootstrap of the SAME account, or an aborted switch that
         *  keeps it) and the worker's belt-and-braces recognise when the row this
         *  record targets belongs to the account that is CURRENTLY signed in on
         *  this device — in which case the delete must NOT run (it would remove the
         *  returned account's own fresh push registration). Null only if the
         *  departing session had no persisted id (fail-closed path). */
        val userId: String?,
    )

    fun save(deviceId: String, token: String, lane: String, userId: String?) {
        val editor = prefs.edit()
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_TOKEN, token)
            .putString(KEY_LANE, lane)
        if (userId != null) editor.putString(KEY_USER_ID, userId) else editor.remove(KEY_USER_ID)
        editor.apply()
    }

    fun get(): Pending? {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val lane = prefs.getString(KEY_LANE, null) ?: SessionManager.PUSH_TYPE_FCM
        val userId = prefs.getString(KEY_USER_ID, null)
        return Pending(deviceId, token, lane, userId)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN = "token"
        const val KEY_LANE = "lane"
        const val KEY_USER_ID = "user_id"
    }
}
