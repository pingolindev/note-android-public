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
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import android.util.Log
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Google Play Integrity API interactions for attestation-based CAPTCHA bypass.
 *
 * Play Integrity provides cryptographic verification that:
 * - The app is genuine (installed from Play Store)
 * - The device meets integrity requirements (not rooted, verified boot)
 * - The app is running on a real device (not an emulator)
 *
 * For degoogled devices without Play Services, use [HardwareAttestation] + Turnstile instead.
 *
 * GMS FLAVOUR. `src/libre` carries a same-named class with the same public API
 * that answers "unavailable" to everything — it is the only copy compiled into a
 * libre build, and it is what keeps `com.google.android.play:integrity` off that
 * variant's classpath. Change one signature here and you must change the other,
 * or the libre variant stops compiling. [PlayIntegrityError] and
 * [PlayIntegrityException] are deliberately NOT duplicated: they live in
 * `src/main` so the two copies cannot drift on the exception contract that
 * [AttestationManager] catches.
 */
@Singleton
class PlayIntegrityManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "PlayIntegrityManager"
    }

    private val integrityManager by lazy {
        IntegrityManagerFactory.create(context)
    }

    /**
     * Check if Google Play Services is available on this device.
     * Returns false on degoogled devices (LineageOS, GrapheneOS, etc.)
     */
    fun isPlayServicesAvailable(): Boolean {
        return try {
            val result = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context)
            result == ConnectionResult.SUCCESS
        } catch (e: Exception) {
            Log.d(TAG, "Play Services availability check failed: ${e.message}")
            false
        }
    }

    /**
     * Request an integrity token from Google Play Integrity API.
     *
     * @param nonce Server-generated nonce from /api/v1/auth/attest/challenge?platform=google
     * @return Integrity token to be verified by the server
     * @throws PlayIntegrityException if the request fails
     */
    suspend fun requestIntegrityToken(nonce: String): String {
        if (!isPlayServicesAvailable()) {
            throw PlayIntegrityException(
                PlayIntegrityError.PLAY_SERVICES_UNAVAILABLE,
                "Google Play Services not available"
            )
        }

        return try {
            val request = IntegrityTokenRequest.builder()
                .setNonce(nonce)
                .build()

            val response = integrityManager.requestIntegrityToken(request).await()
            response.token()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request integrity token", e)
            throw PlayIntegrityException(
                PlayIntegrityError.TOKEN_REQUEST_FAILED,
                "Failed to request integrity token: ${e.message}",
                e
            )
        }
    }

    /**
     * Full attestation flow: get challenge, request token, verify with server.
     * Returns the attestation token registration then presents.
     */
    suspend fun performAttestation(
        getChallenge: suspend () -> String,
        verifyToken: suspend (token: String, nonce: String) -> String
    ): String {
        val nonce = getChallenge()

        val integrityToken = requestIntegrityToken(nonce)

        return verifyToken(integrityToken, nonce)
    }
}
