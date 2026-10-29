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
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GMS FLAVOUR [FcmTokenProvider] — the real Firebase one.
 *
 * The availability check, the `token`/`deleteToken` calls and their
 * `.await()` bridge live behind the interface, so `src/main` carries no
 * Firebase import.
 */
@Singleton
class FirebaseFcmTokenProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : FcmTokenProvider {

    override fun isAvailable(): Boolean = try {
        val availability = GoogleApiAvailability.getInstance()
        availability.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (_: Exception) {
        false
    }

    override suspend fun token(): String = FirebaseMessaging.getInstance().token.await()

    /** Propagates failures rather than swallowing them: both callers in
     *  [PushNotificationManager] already handle a throw, and one of them logs
     *  it. Silently succeeding here would hide a token that was never dropped. */
    override suspend fun deleteToken() {
        FirebaseMessaging.getInstance().deleteToken().await()
    }
}
