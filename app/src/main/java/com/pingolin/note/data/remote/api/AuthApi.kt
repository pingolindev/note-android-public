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

package com.pingolin.note.data.remote.api

import com.pingolin.note.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Auth backend API — SRP login and token refresh.
 */
interface AuthApi {

    /** Refresh the access token.
     *
     * The auth backend reads the refresh credential from the
     * `X-Refresh-Token` header (mobile) or `refresh_token` cookie
     * (web) — NOT from a request body. Posting `{refresh_token: …}`
     * in the body makes the server take its legacy JWT-only path,
     * which rejects the request with "Please log in again to upgrade
     * session" for every session issued by the current backend. */
    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(
        @Header("X-Refresh-Token") refreshToken: String? = null,
        @Body request: Map<String, String> = emptyMap(),
    ): Response<ApiResponse<TokenResponse>>

    /** SRP Step 1: Initiate authentication, get salt and server public value */
    @POST("api/v1/auth/srp/step1")
    suspend fun srpStep1(@Body request: SrpStep1Request): Response<ApiResponse<SrpStep1Response>>

    /** SRP Step 2: Send client proof, receive server proof and JWT */
    @POST("api/v1/auth/srp/step2")
    suspend fun srpStep2(@Body request: SrpStep2Request): Response<ApiResponse<SrpStep2Response>>

    /**
     * Complete a login that stopped at the two-factor challenge.
     *
     * The route is `auth/login/2fa`; there is no `auth/2fa/verify` (it 404s).
     */
    @POST("api/v1/auth/login/2fa")
    suspend fun verify2fa(
        @Body request: com.pingolin.note.data.remote.dto.TwoFactorVerifyRequest
    ): Response<ApiResponse<com.pingolin.note.data.remote.dto.TwoFactorVerifyResponse>>

    /** SRP-style account registration: the server receives an SRP verifier
     *  (never the password) plus the client-generated encrypted
     *  master/sharing/signing keypairs and recovery key hash. An
     *  attestation token, if the device produced one, goes in
     *  X-Attestation-Token to bypass the inline Turnstile. */
    @POST("api/v1/auth/srp/register")
    suspend fun srpRegister(
        @Body request: SrpRegisterRequest,
        @Header("X-Attestation-Token") attestationToken: String? = null,
    ): Response<ApiResponse<SrpRegisterResponse>>

    /** Public unauth endpoint — returns the Turnstile site key so the
     *  AuthActivity register form can render the inline challenge. */
    @GET("api/config")
    suspend fun getAuthConfig(): Response<AuthConfigResponse>

    /** POST a verification token (from the deep-link in the email) to
     *  flip the user's email_verified flag server-side. */
    @POST("api/v1/auth/verify-email")
    suspend fun verifyEmail(@Body request: VerifyEmailRequest): Response<ApiResponse<Unit>>

    @POST("api/v1/auth/email/resend-verification")
    suspend fun resendVerification(): Response<ApiResponse<Unit>>

    /** Polled by the "Check your email" screen until the user taps the
     *  link in their inbox. */
    @GET("api/v1/auth/email/status")
    suspend fun getVerificationStatus(): Response<ApiResponse<EmailVerificationStatusResponse>>

    /** Identities the user recovered after a password reset. Empty for almost
     *  every account; see PreviousIdentityLoader. */
    @GET("api/v1/auth/previous-identities")
    suspend fun getPreviousIdentities(): Response<ApiResponse<PreviousIdentitiesResponse>>

    @GET("api/v1/auth/me")
    suspend fun getCurrentUser(): Response<ApiResponse<UserResponse>>

    @PATCH("api/v1/auth/me")
    suspend fun updateDisplayName(@Body request: UpdateProfileRequest): Response<ApiResponse<Unit>>

    @GET("api/v1/billing/subscription")
    suspend fun getSubscriptions(): Response<ApiResponse<List<SubscriptionInfo>>>

    /** Ends THIS session: the refresh token names it, so it ends even when the
     * access token has already expired. The interceptor still attaches whatever
     * bearer the app holds — an expired one is fine, and a bearer is what skips
     * the auth server's CSRF check should a Cookie header ever ride along. Never
     * refreshed on a 401 (the sentinel; see TokenAuthenticator): a refresh here
     * would mint new tokens right before the wipe. */
    @Headers("X-Pingolin-No-Refresh: 1")
    @POST("api/v1/auth/logout")
    suspend fun logout(@Body request: LogoutRequest): Response<Unit>

    /** SRP step-up, step 1. Bodyless: the server identifies the account from
     *  the session rather than an email, so there is no enumeration surface. */
    @POST("api/v1/auth/reauth/srp/step1")
    suspend fun reauthStep1(): Response<ApiResponse<SrpStep1Response>>

    /** SRP step-up, step 2. Same (A, M1) body as login step 2; a valid proof
     *  mints a short-lived `reauth_token` for a password-gated endpoint.
     *  A wrong password fails proof verification server-side (401). */
    @POST("api/v1/auth/reauth/srp/step2")
    suspend fun reauthStep2(@Body request: SrpStep2Request): Response<ApiResponse<ReauthStep2Response>>

    /** Delete the account (GDPR Article 17). Deletes the auth account and
     *  cascades into every app hanging off it — note AND track — so the
     *  confirmation UI has to say so before this is called.
     *
     *  DELETE with a body: Retrofit needs @HTTP(hasBody = true) for that, as
     *  @DELETE cannot carry one. */
    @HTTP(method = "DELETE", path = "api/v1/auth/me", hasBody = true)
    suspend fun deleteAccount(@Body request: DeleteAccountRequest): Response<ApiResponse<DeleteAccountResponse>>

    // Attestation (device trust)

    @GET("api/v1/auth/attest/status")
    suspend fun getAttestationStatus(): Response<ApiResponse<AttestationStatusResponse>>

    @GET("api/v1/auth/attest/challenge")
    suspend fun getAttestationChallenge(@Query("platform") platform: String): Response<ApiResponse<AttestationChallengeResponse>>

    @POST("api/v1/auth/attest/google")
    suspend fun verifyGoogleIntegrity(@Body request: GoogleIntegrityVerifyRequest): Response<ApiResponse<AttestationVerifyResponse>>

    @POST("api/v1/auth/attest/android-hardware")
    suspend fun verifyAndroidKeystore(@Body request: AndroidKeystoreVerifyRequest): Response<ApiResponse<AndroidKeystoreVerifyResponse>>

    @POST("api/v1/auth/attest/turnstile-combo")
    suspend fun verifyTurnstileCombo(@Body request: TurnstileComboVerifyRequest): Response<ApiResponse<AttestationVerifyResponse>>
}
