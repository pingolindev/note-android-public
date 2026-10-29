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

import javax.inject.Inject
import javax.inject.Singleton

/**
 * LIBRE FLAVOUR [FcmTokenProvider] — there is no Firebase in this build.
 *
 * Reporting unavailable is enough to keep FCM out of the running end to end,
 * without any other flavour-specific code:
 *  - [PushNotificationManager.detectAvailableProviders] omits
 *    `Provider.FCM`, so [PushNotificationManager.preferredProvider] resolves to
 *    UnifiedPush when a distributor is installed and `NONE` otherwise;
 *  - `SettingsFragment.setupPushNotifications` disables the "Google FCM" chip
 *    from that same list, so the user cannot select it either.
 *
 * Push on a libre build is therefore UnifiedPush — a distributor app the user
 * installs — or nothing, in which case the periodic sync is what surfaces new
 * shares and reminders.
 */
@Singleton
class NoFcmTokenProvider @Inject constructor() : FcmTokenProvider {

    override fun isAvailable(): Boolean = false

    override suspend fun token(): String =
        throw UnsupportedOperationException("FCM is not available in the libre build")

    /** No-op: nothing was ever registered, so there is nothing to drop. */
    override suspend fun deleteToken() = Unit
}
