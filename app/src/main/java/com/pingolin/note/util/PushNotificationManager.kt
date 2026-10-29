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
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.unifiedpush.android.connector.UnifiedPush
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks a push provider at runtime and persists the result.
 *
 * UnifiedPush is preferred over FCM when a distributor is installed —
 * the user has gone out of their way to install one, so they're
 * signalling a preference for Google-free push. FCM is the fallback.
 *
 * Endpoint flow:
 *  - FCM: synchronous — the token comes back from FirebaseMessaging
 *    and we save it directly.
 *  - UnifiedPush: asynchronous — we call register(); the distributor
 *    delivers the endpoint to [NoteUnifiedPushReceiver.onNewEndpoint]
 *    which writes it via [SessionManager.savePushSettings] and triggers
 *    a server re-registration.
 *
 * FCM is reached only through [FcmTokenProvider], never through Firebase
 * directly: this class is shared by both distribution flavours, and the libre
 * one resolves no Firebase artifact. In that build the injected provider
 * answers `isAvailable() == false`, so [Provider.FCM] simply never enters
 * [detectAvailableProviders] and UnifiedPush is all that is left.
 */
@Singleton
class PushNotificationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionManager: SessionManager,
    private val fcmTokenProvider: FcmTokenProvider,
) {
    companion object {
        private const val TAG = "NotePush"
    }

    enum class Provider { FCM, UNIFIEDPUSH, NONE }

    sealed class RegistrationResult {
        data class Success(
            val provider: Provider,
            val token: String?,
            val endpoint: String?,
        ) : RegistrationResult()
        data class Pending(val provider: Provider, val message: String) : RegistrationResult()
        data class Failure(val provider: Provider, val error: String) : RegistrationResult()
    }

    /** Detect available providers in preference order. */
    fun detectAvailableProviders(): List<Provider> {
        val out = mutableListOf<Provider>()
        if (isUnifiedPushAvailable()) out.add(Provider.UNIFIEDPUSH)
        if (isGooglePlayServicesAvailable()) out.add(Provider.FCM)
        if (out.isEmpty()) Log.w(TAG, "No push providers available")
        return out
    }

    fun preferredProvider(): Provider =
        detectAvailableProviders().firstOrNull() ?: Provider.NONE

    fun currentProvider(): Provider = when (sessionManager.getPushType()) {
        SessionManager.PUSH_TYPE_FCM -> Provider.FCM
        SessionManager.PUSH_TYPE_UNIFIEDPUSH -> Provider.UNIFIEDPUSH
        else -> Provider.NONE
    }

    /** Called at MainActivity startup. If push isn't yet configured,
     *  pick the preferred provider and register. If it is configured,
     *  do nothing — don't second-guess the user's previous choice. */
    suspend fun autoDetectAndRegister(): RegistrationResult? {
        if (currentProvider() != Provider.NONE) {
            Log.d(TAG, "Push already configured: ${currentProvider()}")
            return null
        }
        return registerWith(preferredProvider())
    }

    suspend fun registerWith(provider: Provider): RegistrationResult = when (provider) {
        Provider.FCM -> registerWithFcm()
        Provider.UNIFIEDPUSH -> registerWithUnifiedPush()
        Provider.NONE -> RegistrationResult.Failure(Provider.NONE, "No push provider available")
    }

    private suspend fun registerWithFcm(): RegistrationResult = try {
        val token = fcmTokenProvider.token()
        sessionManager.savePushSettings(
            token = token,
            type = SessionManager.PUSH_TYPE_FCM,
            endpoint = null,
        )
        Log.i(TAG, "FCM registration successful")
        RegistrationResult.Success(Provider.FCM, token, null)
    } catch (e: Exception) {
        Log.e(TAG, "FCM registration failed", e)
        RegistrationResult.Failure(Provider.FCM, e.message ?: "FCM register failed")
    }

    private fun registerWithUnifiedPush(): RegistrationResult = try {
        val distributors = UnifiedPush.getDistributors(context)
        if (distributors.isEmpty()) {
            RegistrationResult.Failure(
                Provider.UNIFIEDPUSH,
                "No UnifiedPush distributor installed",
            )
        } else {
            val distributor = distributors.first()
            UnifiedPush.saveDistributor(context, distributor)
            // The endpoint arrives asynchronously at NoteUnifiedPushReceiver,
            // so all we can report from here is Pending.
            UnifiedPush.register(context)
            Log.i(TAG, "UnifiedPush registration initiated with $distributor")
            RegistrationResult.Pending(
                Provider.UNIFIEDPUSH,
                "Registration initiated with $distributor; endpoint pending",
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "UnifiedPush registration failed", e)
        RegistrationResult.Failure(Provider.UNIFIEDPUSH, e.message ?: "register failed")
    }

    /** Installed UnifiedPush distributor package names (ntfy, NextPush, …).
     *  Empty when none are installed. Drives the settings distributor picker. */
    fun getUnifiedPushDistributors(): List<String> = try {
        UnifiedPush.getDistributors(context)
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Register with a specific UnifiedPush distributor the user chose in
     * settings. Asynchronous like [registerWithUnifiedPush]; persisting the
     * endpoint additionally emits [SessionManager.pushSettingsChanged].
     * Returns Success only when an endpoint is already on file (re-selecting
     * the same distributor); otherwise Pending.
     */
    fun registerWithUnifiedPushDistributor(distributor: String): RegistrationResult = try {
        UnifiedPush.saveDistributor(context, distributor)
        UnifiedPush.register(context)
        Log.i(TAG, "UnifiedPush registration initiated with $distributor")
        val existingEndpoint = sessionManager.getPushEndpoint()
        if (existingEndpoint != null && sessionManager.getPushType() == SessionManager.PUSH_TYPE_UNIFIEDPUSH) {
            RegistrationResult.Success(Provider.UNIFIEDPUSH, null, existingEndpoint)
        } else {
            RegistrationResult.Pending(
                Provider.UNIFIEDPUSH,
                "Registration initiated with $distributor; endpoint pending",
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "UnifiedPush registration failed with $distributor", e)
        RegistrationResult.Failure(Provider.UNIFIEDPUSH, e.message ?: "register failed")
    }

    suspend fun unregister() {
        when (currentProvider()) {
            Provider.FCM -> try {
                fcmTokenProvider.deleteToken()
            } catch (e: Exception) {
                Log.w(TAG, "FCM deleteToken failed", e)
            }
            Provider.UNIFIEDPUSH -> try {
                UnifiedPush.unregister(context)
            } catch (e: Exception) {
                Log.w(TAG, "UnifiedPush unregister failed", e)
            }
            Provider.NONE -> {}
        }
        sessionManager.clearPushSettings()
    }

    /**
     * Rotate/drop the provider-side push token for [lane] WITHOUT touching
     * SessionManager (unlike [unregister], which also clears the local push
     * settings). Used by [com.pingolin.note.worker.PushUnregisterWorker] after a
     * logout / account-switch to force a fresh token, so a NEW sign-in on this
     * device can't inherit the departed account's token — the local push settings
     * were already cleared at teardown. Best-effort; safe to call with no active
     * registration.
     */
    suspend fun rotateProviderToken(lane: String) {
        when (lane) {
            SessionManager.PUSH_TYPE_FCM ->
                fcmTokenProvider.deleteToken()
            SessionManager.PUSH_TYPE_UNIFIEDPUSH ->
                UnifiedPush.unregister(context)
            else -> { /* unknown lane — nothing to rotate */ }
        }
    }

    private fun isGooglePlayServicesAvailable(): Boolean = fcmTokenProvider.isAvailable()

    private fun isUnifiedPushAvailable(): Boolean = try {
        UnifiedPush.getDistributors(context).isNotEmpty()
    } catch (_: Exception) {
        false
    }
}
