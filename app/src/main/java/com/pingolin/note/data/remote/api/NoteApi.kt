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
import retrofit2.http.*

/**
 * Note backend REST API.
 * All item content is encrypted client-side — server stores opaque blobs.
 */
interface NoteApi {

    // Items
    @POST("api/v1/items")
    suspend fun createItem(@Body request: CreateItemRequest): Response<ApiResponse<ItemDto>>

    @GET("api/v1/items/{id}")
    suspend fun getItem(@Path("id") id: String): Response<ApiResponse<ItemDto>>

    @PUT("api/v1/items/{id}")
    suspend fun updateItem(@Path("id") id: String, @Body request: UpdateItemRequest): Response<ApiResponse<ItemDto>>

    @DELETE("api/v1/items/{id}")
    suspend fun deleteItem(@Path("id") id: String): Response<Unit>

    /** Hard-deletes every trashed item for the current user. Used by
     *  the "Empty trash" action in the trash tab. */
    @DELETE("api/v1/trash")
    suspend fun purgeTrash(): Response<Unit>

    /** Subscription tier + current usage. Single canonical source for
     *  client-side gating decisions (badges, dimmed controls,
     *  pre-action explainer sheet). Server is still the authority —
     *  this is just a hint so the UI doesn't have to discover gates by
     *  bouncing off a 402. */
    @GET("api/v1/quota")
    suspend fun getQuota(): Response<ApiResponse<QuotaDto>>

    // Delta sync
    @GET("api/v1/sync/pull")
    suspend fun syncPull(
        @Query("since") since: String,
        // Tie-breaker paired with `since` (the compound (updated_at, id) keyset
        // cursor). Null → Retrofit omits the param → backend falls back to a
        // plain `updated_at > since`.
        @Query("since_id") sinceId: String? = null,
        @Query("limit") limit: Int = 100,
    ): Response<ApiResponse<SyncPullResponse>>

    @POST("api/v1/sync/push")
    suspend fun syncPush(@Body items: List<CreateItemRequest>): Response<ApiResponse<SyncPushResponse>>

    // Reminders
    @POST("api/v1/reminders")
    suspend fun createReminder(@Body request: CreateReminderRequest): Response<ApiResponse<ReminderDto>>

    @GET("api/v1/reminders")
    suspend fun listReminders(): Response<ApiResponse<List<ReminderDto>>>

    @DELETE("api/v1/reminders/{id}")
    suspend fun deleteReminder(@Path("id") id: String): Response<Unit>

    // Push / FCM
    @POST("api/v1/push/fcm-register")
    suspend fun registerFcmDevice(@Body request: RegisterFcmRequest): Response<ApiResponse<Unit>>

    @HTTP(method = "DELETE", path = "api/v1/push/fcm-register", hasBody = true)
    suspend fun unregisterFcmDevice(@Body request: UnregisterFcmRequest): Response<Unit>

    /**
     * Worker-run variant of [unregisterFcmDevice] used by
     * [com.pingolin.note.worker.PushUnregisterWorker] to delete a DEPARTED
     * account's device row on reconnect, authenticating with THAT account's own
     * (persisted) bearer rather than the current session's — the server keys
     * device rows by `(user_id, device_id)`, so only the departing user's token
     * can delete their row. The explicit Authorization header is honoured by the
     * token-attach interceptor (which won't override an already-present bearer)
     * and the static `X-Pingolin-No-Refresh` sentinel makes
     * [com.pingolin.note.util.TokenAuthenticator] bail instead of
     * refreshing-and-retrying onto the current session's token (which would
     * delete the wrong account's row).
     */
    @Headers("X-Pingolin-No-Refresh: 1")
    @HTTP(method = "DELETE", path = "api/v1/push/fcm-register", hasBody = true)
    suspend fun unregisterFcmDeviceWithAuth(
        @Header("Authorization") authorization: String,
        @Body request: UnregisterFcmRequest,
    ): Response<Unit>

    // Notification preferences (per-category push/email toggles). GET returns
    // the full row (missing row → documented defaults); PUT takes a PARTIAL
    // NotificationPreferencesDto — only the changed fields — and echoes the
    // full row back. See [NotificationPreferencesDto] for why every field is
    // nullable.
    @GET("api/v1/notifications/preferences")
    suspend fun getNotificationPreferences(): Response<ApiResponse<NotificationPreferencesDto>>

    @PUT("api/v1/notifications/preferences")
    suspend fun updateNotificationPreferences(
        @Body prefs: NotificationPreferencesDto,
    ): Response<ApiResponse<NotificationPreferencesDto>>

    // Cooperative edit lock
    @POST("api/v1/items/{id}/lock")
    suspend fun acquireLock(
        @Path("id") id: String,
        @Body request: AcquireLockRequest,
    ): Response<ApiResponse<LockRecordDto>>

    @GET("api/v1/items/{id}/lock")
    suspend fun peekLock(@Path("id") id: String): Response<ApiResponse<LockRecordDto?>>

    @DELETE("api/v1/items/{id}/lock")
    suspend fun releaseLock(@Path("id") id: String): Response<Unit>

    // Attachments
    @POST("api/v1/attachments")
    suspend fun createAttachment(@Body request: CreateAttachmentRequest): Response<ApiResponse<AttachmentDto>>

    @GET("api/v1/items/{itemId}/attachments")
    suspend fun listAttachments(@Path("itemId") itemId: String): Response<ApiResponse<List<AttachmentDto>>>

    @DELETE("api/v1/attachments/{id}")
    suspend fun deleteAttachment(@Path("id") id: String): Response<Unit>

    // Re-key an attachment after a content-key rotation / re-mint: the owner
    // re-encrypts the blob under the note's NEW content key, uploads it to a
    // fresh presigned object, then repoints the row + swaps its metadata so
    // survivors/recipients decrypt it under the current key (no permanent
    // reliance on retained old keys). Owner-only server-side.
    @PUT("api/v1/attachments/{id}")
    suspend fun rekeyAttachment(
        @Path("id") id: String,
        @Body request: RekeyAttachmentRequest,
    ): Response<ApiResponse<AttachmentDto>>

    @POST("api/v1/attachments/presign-upload")
    suspend fun presignUpload(
        @Body request: PresignUploadRequest,
    ): Response<ApiResponse<PresignedUrlResponse>>

    @GET("api/v1/attachments/{id}/download")
    suspend fun downloadAttachment(@Path("id") id: String): Response<okhttp3.ResponseBody>

    // Notebooks
    @GET("api/v1/notebooks/{id}/items")
    suspend fun listNotebookItems(@Path("id") notebookId: String): Response<ApiResponse<List<ItemDto>>>

    @POST("api/v1/items/{id}/restore")
    suspend fun restoreItem(@Path("id") id: String): Response<Unit>

    /** Share a note, or FINISH an invitation's keyless share ([ShareNoteRequest.finish]). What a
     *  refusal means is read with [ShareCallResult.of]. */
    @POST("api/v1/items/{noteId}/share")
    suspend fun shareNote(
        @Path("noteId") noteId: String,
        @Body request: ShareNoteRequest,
    ): Response<ApiResponse<Unit>>

    /** The caller's shares an invitation left without a key (made before the address had an
     *  account), claimed ones first: what the owner's devices finish (InvitationFinisher). */
    @GET("api/v1/shares/unfinished")
    suspend fun listUnfinishedShares(): Response<ApiResponse<List<UnfinishedShareDto>>>

    // Look up X25519 public keys for a batch of emails. The note backend
    // proxies this to the auth service's internal endpoint.
    @POST("api/v1/users/public-keys")
    suspend fun lookupPublicKeys(
        @Body request: PublicKeyLookupRequest,
    ): Response<ApiResponse<PublicKeyLookupResponse>>

    // Incoming shares (notes shared with me by others)
    // One page, newest first, of at most 500 rows. The next page is asked for
    // with the last row's created_at and share_id; with neither, the first.
    //
    // Asked with page_info=true the server answers { shares, has_more,
    // declined_notebooks }; an older server ignores the parameter and answers
    // with the bare list. So the payload is left as JSON here and read by
    // InboxAnswer.read, which knows both shapes.
    @GET("api/v1/shares/inbox")
    suspend fun listInbox(
        @Query("before") before: String? = null,
        @Query("before_id") beforeId: String? = null,
        @Query("page_info") pageInfo: Boolean = true,
    ): Response<ApiResponse<com.google.gson.JsonElement>>

    // List recipients of a note's shares (for the owner's share panel)
    @GET("api/v1/items/{noteId}/shares")
    suspend fun listShares(
        @Path("noteId") noteId: String,
    ): Response<ApiResponse<List<ShareInfoDto>>>

    @DELETE("api/v1/shares/{shareId}")
    suspend fun removeShare(
        @Path("shareId") shareId: String,
    ): Response<Unit>

    // Atomic content-key rotation: update item ciphertext + wrap, delete
    // revoked shares, rewrap remaining recipients — all in one transaction.
    @POST("api/v1/items/{itemId}/rotate-shares")
    suspend fun rotateShares(
        @Path("itemId") itemId: String,
        @Body request: RotateSharesRequest,
    ): Response<ApiResponse<RotateSharesResponse>>

    // Share link (public/expiring link for a note)

    // User profile
    @PUT("api/v1/profile")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): Response<ApiResponse<Unit>>

    @GET("api/v1/profile")
    suspend fun getProfile(): Response<ApiResponse<ProfileResponse>>
}
