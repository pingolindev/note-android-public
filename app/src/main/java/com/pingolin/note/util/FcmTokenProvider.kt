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

/**
 * The FCM registration token, behind an interface so `src/main` never names
 * Firebase.
 *
 * [PushNotificationManager] is shared by both distribution flavours and is the
 * only caller. `src/gms` binds an implementation backed by
 * `FirebaseMessaging.getInstance()`; `src/libre` binds one that reports
 * unavailable, because a libre build resolves no Firebase artifact at all (see
 * `gmsImplementation` in app/build.gradle.kts). Bound in each flavour's own
 * `di/FcmModule.kt`.
 *
 * This is deliberately the whole surface: everything else about push — provider
 * preference, persisting the token, re-registering with the backend — is
 * flavour-independent and stays in [PushNotificationManager].
 */
interface FcmTokenProvider {

    /**
     * Whether FCM can be used on this build AND this device. False in a libre
     * build unconditionally; in a gms build it reflects whether Google Play
     * services are actually present, so a gms APK on a de-Googled phone answers
     * false here too.
     */
    fun isAvailable(): Boolean

    /**
     * The current FCM registration token. Throws when FCM is unavailable or the
     * token cannot be fetched — [PushNotificationManager.registerWithFcm]
     * already turns any throw into a `RegistrationResult.Failure`.
     */
    suspend fun token(): String

    /**
     * Drop the provider-side token so the next [token] mints a fresh one. May
     * throw; both call sites in [PushNotificationManager] already treat it as
     * best-effort, and so does
     * [com.pingolin.note.worker.PushUnregisterWorker] beyond them.
     */
    suspend fun deleteToken()
}
