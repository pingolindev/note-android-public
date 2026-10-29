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

package com.pingolin.note.data.remote.dto

import com.google.gson.annotations.SerializedName

/** Standard API envelope: every endpoint wraps its payload as
 *  `{success, data, error, error_code}`. `data` is null on failure. */
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    @SerializedName("error_code") val errorCode: String? = null,
)

data class ItemDto(
    val id: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("item_type") val itemType: Int,
    @SerializedName("encrypted_content") val encryptedContent: String,
    @SerializedName("content_nonce") val contentNonce: String,
    val version: Long,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    val deleted: Boolean,
    @SerializedName("circle_id") val circleId: String?,
    @SerializedName("notebook_id") val notebookId: String?,
    @SerializedName("content_size") val contentSize: Int,
    // Server-computed: THIS user has shared the item with someone. Drives the
    // "Shared" badge without an N+1 of per-note share lookups. Gson leaves an
    // absent field at the JVM default rather than honouring a Kotlin default
    // (see the contentKeyGeneration note below) — for a Boolean that is
    // `false`, which is the value we want from a server that predates it.
    @SerializedName("shared_by_me") val sharedByMe: Boolean = false,
    // Per-note content key, wrapped with the owner's vault key. Absent
    // (null) on legacy notes that encrypt `encrypted_content` directly
    // under the vault key.
    @SerializedName("wrapped_content_key") val wrappedContentKey: String? = null,
    @SerializedName("wrapped_content_key_nonce") val wrappedContentKeyNonce: String? = null,
    // Server-emitted monotonic content-key generation (base 1, bumped by
    // exactly +1 only on rotate-shares). This is the CURRENT-gen source for
    // signing a fresh share wrap. Gson caveat: an ABSENT field deserializes
    // to 0L (not this Kotlin default of 1); callers that use it as the
    // current gen for a share-create coerceAtLeast(1) so a Gson-defaulted 0
    // never becomes the signed version.
    @SerializedName("content_key_generation") val contentKeyGeneration: Long = 1,
)

data class CreateItemRequest(
    val id: String? = null,
    @SerializedName("item_type") val itemType: Int,
    @SerializedName("encrypted_content") val encryptedContent: String,
    @SerializedName("content_nonce") val contentNonce: String,
    @SerializedName("circle_id") val circleId: String? = null,
    @SerializedName("notebook_id") val notebookId: String? = null,
    @SerializedName("wrapped_content_key") val wrappedContentKey: String? = null,
    @SerializedName("wrapped_content_key_nonce") val wrappedContentKeyNonce: String? = null,
)

data class UpdateItemRequest(
    @SerializedName("encrypted_content") val encryptedContent: String,
    @SerializedName("content_nonce") val contentNonce: String,
    @SerializedName("expected_version") val expectedVersion: Long,
    @SerializedName("wrapped_content_key") val wrappedContentKey: String? = null,
    @SerializedName("wrapped_content_key_nonce") val wrappedContentKeyNonce: String? = null,
    /**
     * Server-visible grouping, with THREE states the server distinguishes:
     * absent = leave the column alone, `null` = take the note out of its
     * notebook, a value = file it there.
     *
     * ⚠️ Typed as [com.google.gson.JsonElement] rather than `String?` because
     * Gson OMITS null fields by default — a plain nullable could express
     * "leave alone" and "file it", but never "un-file", so a note removed from
     * a notebook would keep its old grouping on the server forever. Use
     * [notebookIdField] to build it.
     */
    @SerializedName("notebook_id")
    @com.google.gson.annotations.JsonAdapter(ExplicitNullAdapter::class)
    val notebookId: com.google.gson.JsonElement? = null,
)

/**
 * Writes a JSON `null` that survives this app's `serializeNulls = false` Gson.
 *
 * The Retrofit Gson deliberately omits nulls — `NotificationPreferencesDto`
 * depends on it for partial updates, where an omitted key means "keep the
 * stored value" — so turning it on globally would clear every preference a PUT
 * did not mention. But `notebook_id` needs the OPPOSITE for one value: an
 * explicit null is how a note gets taken out of its notebook, and Gson's writer
 * drops `JsonNull` along with everything else.
 *
 * Scoped to this one field: a Java-null still never reaches here (the
 * reflective adapter skips the field entirely, which is the "leave it alone"
 * state), so this only ever forces the deliberate null.
 */
class ExplicitNullAdapter : com.google.gson.TypeAdapter<com.google.gson.JsonElement>() {
    override fun write(out: com.google.gson.stream.JsonWriter, value: com.google.gson.JsonElement?) {
        val previous = out.serializeNulls
        out.serializeNulls = true
        try {
            com.google.gson.internal.Streams.write(value ?: com.google.gson.JsonNull.INSTANCE, out)
        } finally {
            out.serializeNulls = previous
        }
    }

    override fun read(reader: com.google.gson.stream.JsonReader): com.google.gson.JsonElement =
        com.google.gson.internal.Streams.parse(reader)
}

/**
 * Build [UpdateItemRequest.notebookId] from a note's notebook and the set of
 * notebooks that actually exist as rows.
 *
 * - notebook is real  -> its id, so the server records the grouping
 * - note has none     -> JSON `null`, so the server clears any stale grouping
 * - notebook is not a real row (a web-only one) -> `null` Kotlin, i.e. the
 *   field is OMITTED, because naming it would be a foreign-key violation and
 *   the save would fail outright. Leaving the column untouched is the safe
 *   degradation; the note still carries its notebook in its encrypted content.
 */
fun notebookIdField(
    notebookId: String?,
    realNotebookIds: Set<String>,
): com.google.gson.JsonElement? = when {
    notebookId == null -> com.google.gson.JsonNull.INSTANCE
    notebookId in realNotebookIds -> com.google.gson.JsonPrimitive(notebookId)
    else -> null
}

data class SyncPullResponse(
    val items: List<ItemDto>,
    val cursor: String?,
    // Tie-breaker paired with `cursor`: the id of the last item in the page.
    // Echoed back as `since_id` so same-`updated_at` notes split across a page
    // boundary are neither skipped nor re-sent. Defaulted null so an older
    // backend that omits the field decodes safely (→ plain `updated_at > since`).
    @SerializedName("cursor_id") val cursorId: String? = null,
    @SerializedName("has_more") val hasMore: Boolean,
)

data class SyncPushResponse(
    val created: Int,
    val conflicts: List<ItemDto>,
    val errors: List<String>,
)

data class ReminderDto(
    val id: String,
    @SerializedName("item_id") val itemId: String,
    @SerializedName("trigger_at") val triggerAt: String,
    @SerializedName("repeat_rule") val repeatRule: String?,
    val fired: Boolean,
    val dismissed: Boolean,
)

data class CreateReminderRequest(
    @SerializedName("item_id") val itemId: String,
    @SerializedName("trigger_at") val triggerAt: String,
    @SerializedName("repeat_rule") val repeatRule: String? = null,
    @SerializedName("encrypted_details") val encryptedDetails: String? = null,
    @SerializedName("details_nonce") val detailsNonce: String? = null,
)

data class RegisterFcmRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("fcm_token") val fcmToken: String,
    val platform: String = "android",
    @SerializedName("app_version") val appVersion: String? = null,
    /** "fcm" or "unifiedpush". Omitted → server defaults to "fcm" for
     *  back-compat with older clients. */
    @SerializedName("push_type") val pushType: String? = null,
    /** Required when pushType = "unifiedpush". Distributor's endpoint
     *  URL that the dispatcher POSTs to. */
    @SerializedName("push_endpoint") val pushEndpoint: String? = null,
)

/** Body of `POST /auth/logout`. Null is omitted, and the server then goes by
 * the bearer alone. */
data class LogoutRequest(
    @SerializedName("refresh_token") val refreshToken: String?,
)

data class UnregisterFcmRequest(
    @SerializedName("device_id") val deviceId: String,
)

/**
 * Server-side notification preferences — GET/PUT
 * `/api/v1/notifications/preferences`. The server is the source of truth so
 * the toggles sync across a user's devices; the app additionally mirrors them
 * into the device-local [com.pingolin.note.util.SessionManager] cache that the
 * client-side notification gates read, so gating still works offline / before
 * the first sync.
 *
 * Every field is nullable so the SAME type serves both roles:
 *  - GET response: the server always returns the full row (a missing row
 *    yields the documented defaults — push ON, email OFF), so every field is
 *    populated; callers read with a `?: <default>` fallback anyway.
 *  - PUT request: a PARTIAL update — populate ONLY the changed field(s),
 *    leaving the rest null. The Retrofit Gson has no `serializeNulls`, so null
 *    fields are omitted from the wire body and the server keeps its stored
 *    value for every omitted key. A concrete DTO (rather than `@Body
 *    Map<String, Any>`) also sidesteps the Retrofit wildcard-rejection gotcha.
 *    The PUT echoes the full row back in the same envelope.
 *
 * `email_enabled` + the two `*_email` flags are a durable server home for the
 * email toggles; Note sends no email, so they are inert (stored server-side,
 * gate nothing) — see SettingsFragment.
 */
data class NotificationPreferencesDto(
    @SerializedName("reminders_push") val remindersPush: Boolean? = null,
    @SerializedName("shared_notes_push") val sharedNotesPush: Boolean? = null,
    @SerializedName("email_enabled") val emailEnabled: Boolean? = null,
    @SerializedName("reminders_email") val remindersEmail: Boolean? = null,
    @SerializedName("shared_notes_email") val sharedNotesEmail: Boolean? = null,
)

data class AcquireLockRequest(
    @SerializedName("display_name") val displayName: String,
)

data class LockRecordDto(
    @SerializedName("holder_user_id") val holderUserId: String,
    @SerializedName("holder_display_name") val holderDisplayName: String,
    @SerializedName("acquired_at") val acquiredAt: String,
    @SerializedName("expires_at") val expiresAt: String,
)

data class AttachmentDto(
    val id: String,
    @SerializedName("item_id") val itemId: String,
    @SerializedName("r2_key") val r2Key: String,
    @SerializedName("encrypted_meta") val encryptedMeta: String,
    @SerializedName("meta_nonce") val metaNonce: String,
    @SerializedName("size_bytes") val sizeBytes: Int,
    @SerializedName("created_at") val createdAt: String,
)

data class CreateAttachmentRequest(
    @SerializedName("r2_key") val r2Key: String,
    @SerializedName("item_id") val itemId: String,
    @SerializedName("encrypted_meta") val encryptedMeta: String,
    @SerializedName("meta_nonce") val metaNonce: String,
    @SerializedName("size_bytes") val sizeBytes: Int,
)

// PUT /api/v1/attachments/{id} — re-key an attachment onto the note's new
// content key. `r2Key` is a FRESH presigned object holding the blob
// re-encrypted under the new key; the server repoints the row + swaps the
// (also re-encrypted) metadata and deletes the replaced object.
data class RekeyAttachmentRequest(
    @SerializedName("r2_key") val r2Key: String,
    @SerializedName("encrypted_meta") val encryptedMeta: String,
    @SerializedName("meta_nonce") val metaNonce: String,
    @SerializedName("size_bytes") val sizeBytes: Int,
)

data class PresignedUrlResponse(
    val url: String,
    @SerializedName("object_key") val objectKey: String,
)

data class PresignUploadRequest(
    @SerializedName("size_bytes") val sizeBytes: Int,
)


data class ShareNoteRequest(
    val email: String,
    val permission: String,
    @SerializedName("wrapped_key") val wrappedKey: String? = null,
    @SerializedName("sharer_public_key") val sharerPublicKey: String? = null,
    /** 128-char lowercase hex Ed25519 signature over the canonical
     *  sig_input. Null when this client has no signing key loaded; the
     *  server accepts an unsigned wrap and the recipient treats it as
     *  unverified. */
    @SerializedName("wrap_signature") val wrapSignature: String? = null,
)

// Atomic rotate-shares endpoint (/items/{id}/rotate-shares)
data class RotateShareEntry(
    @SerializedName("share_id") val shareId: String,
    @SerializedName("wrapped_key") val wrappedKey: String,
    /** Ed25519 signature over the freshly-rewrapped key. */
    @SerializedName("wrap_signature") val wrapSignature: String? = null,
)

data class RotateSharesRequest(
    @SerializedName("encrypted_content") val encryptedContent: String,
    @SerializedName("content_nonce") val contentNonce: String,
    @SerializedName("wrapped_content_key") val wrappedContentKey: String,
    @SerializedName("wrapped_content_key_nonce") val wrappedContentKeyNonce: String,
    @SerializedName("expected_version") val expectedVersion: Long,
    @SerializedName("remove_share_ids") val removeShareIds: List<String> = emptyList(),
    @SerializedName("update_shares") val updateShares: List<RotateShareEntry> = emptyList(),
)

data class RotateSharesResponse(
    val version: Long,
    /** True when the rotate-shares transaction also deleted a public
     *  share_link row. Stale URLs would silently fail to decrypt so
     *  the backend revokes the token atomically. Clients surface this
     *  to remind the owner their public link is gone. */
    @SerializedName("revoked_share_link") val revokedShareLink: Boolean = false,
    /** The item's content-key generation AFTER the server's +1 bump in
     *  this rotate. Returned so a caller can mirror the
     *  post-rotation value locally. Android doesn't persist a gen column
     *  (the next rotate re-reads it fresh via getItem), so this is
     *  informational here. Gson defaults an absent field to 0L. */
    @SerializedName("content_key_generation") val contentKeyGeneration: Long = 1,
)

// Owner's view of a single share row.
data class ShareInfoDto(
    val id: String,
    @SerializedName("recipient_email") val recipientEmail: String,
    val permission: String,
    @SerializedName("created_at") val createdAt: String,
    /**
     * Does the row carry a key at all? False for someone invited before they had an account:
     * nothing could be sealed for them. Null from a server that does not say, which is read
     * as "it does".
     */
    @SerializedName("has_wrap") val hasWrap: Boolean? = null,
)

// Shares inbox — notes shared with the current user by others.
data class InboxShareDto(
    @SerializedName("share_id") val shareId: String,
    @SerializedName("item_id") val itemId: String,
    /** Sender's user_id, needed by the recipient to reconstruct the
     *  canonical sig_input when verifying the wrap signature. */
    @SerializedName("owner_id") val ownerId: String,
    val permission: String,
    @SerializedName("wrapped_key") val wrappedKey: String?,
    @SerializedName("sharer_public_key") val sharerPublicKey: String?,
    @SerializedName("owner_email") val ownerEmail: String?,
    /** Hex-encoded Ed25519 signature over the wrap. NULL when the sender
     *  didn't sign; the client surfaces an "unverified" warning but still
     *  accepts the share. */
    @SerializedName("wrap_signature") val wrapSignature: String?,
    /**
     * What KIND of item was shared: 1 = note, 2 = notebook.
     *
     * Gson ignores Kotlin default values, so an older server that omits the
     * field leaves this at the JVM default of 0 rather than 1 — hence
     * [InboxShareDto.itemTypeOrNote], which every caller should use instead of
     * reading this directly.
     */
    @SerializedName("item_type") val itemType: Int = 1,
    @SerializedName("encrypted_content") val encryptedContent: String,
    @SerializedName("content_nonce") val contentNonce: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("created_at") val createdAt: String,
    /** Server-emitted monotonic content-key generation, incremented on each
     *  per-note content-key rotation. Passed to the SDK wrap-verify as the
     *  content-key version (verify-both accepts this generation OR the
     *  legacy 0). Defaulted so a response omitting the field still parses. */
    @SerializedName("content_key_generation") val contentKeyGeneration: Long = 1,
) {
    /**
     * The item type, treating anything unset as a NOTE.
     *
     * ⚠️ Gson ignores Kotlin default values: an older server that omits
     * `item_type` leaves the field at the JVM default of **0**, not 1. Reading
     * [itemType] directly would then classify every share as "neither a note
     * nor a notebook" and drop the lot.
     */
    val itemTypeOrNote: Int get() = if (itemType == 0) ITEM_TYPE_NOTE else itemType

    val isNotebook: Boolean get() = itemTypeOrNote == ITEM_TYPE_NOTEBOOK
}

/** Server item_type values. Notes are 1, notebooks 2, user metadata 10. */
const val ITEM_TYPE_NOTE = 1
const val ITEM_TYPE_NOTEBOOK = 2

// Public-key lookup DTOs
data class PublicKeyLookupRequest(
    val emails: List<String>,
)

data class PublicKeyLookupEntry(
    val email: String,
    @SerializedName("user_id") val userId: String?,
    @SerializedName("sharing_public_key") val sharingPublicKey: String?,
    /** Hex-encoded Ed25519 verifying key. The note backend forwards it
     *  from the auth service's internal public-keys endpoint. */
    @SerializedName("signing_public_key") val signingPublicKey: String?,
)

data class PublicKeyLookupResponse(
    val users: List<PublicKeyLookupEntry>,
)

// User profile DTOs
data class UpdateProfileRequest(
    @SerializedName("display_name") val displayName: String,
)

data class ProfileResponse(
    val id: String,
    val email: String,
    @SerializedName("display_name") val displayName: String?,
)

/** Subscription tier + current usage. All sizes in bytes; `-1` for
 *  `max_notes` means unlimited; `remaining_notes` is
 *  `i64::MAX` for unlimited and the UI must check `tier == "paid"`
 *  rather than treating the literal as a count. */
data class QuotaDto(
    val tier: String,
    @SerializedName("max_notes") val maxNotes: Long,
    @SerializedName("current_notes") val currentNotes: Long,
    @SerializedName("remaining_notes") val remainingNotes: Long,
    @SerializedName("max_attachment_bytes") val maxAttachmentBytes: Long,
    @SerializedName("current_attachment_bytes") val currentAttachmentBytes: Long,
    @SerializedName("remaining_attachment_bytes") val remainingAttachmentBytes: Long,
    @SerializedName("sharing_enabled") val sharingEnabled: Boolean,
    @SerializedName("reminders_enabled") val remindersEnabled: Boolean,
)

// Auth DTOs (for token refresh)
data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
)

// SRP Auth DTOs
data class SrpStep1Request(
    val email: String,
)

data class SrpStep1Response(
    val salt: String,
    @SerializedName("server_public") val serverPublic: String,
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("bcrypt_salt") val bcryptSalt: String?,
)

data class SrpStep2Request(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("client_public") val clientPublic: String,
    @SerializedName("client_proof") val clientProof: String,
)

data class SrpStep2Response(
    @SerializedName("server_proof") val serverProof: String,
    val user: UserResponse?,
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("kdf_params") val kdfParams: KdfParamsResponse?,
    @SerializedName("encrypted_keys") val encryptedKeys: EncryptedKeysResponse?,
    /** Base64-encoded 32-byte Ed25519 verifying key. Only compared against
     *  the key this sign-in opened, and a disagreement logged
     *  (AuthViewModel.checkSigningKey); never a reason to replace a key. */
    @SerializedName("signing_public_key") val signingPublicKey: String? = null,
    /** True when the account has two-factor authentication enrolled. The server
     * then issues no access token here — it issues [twoFactorToken] instead,
     * which the client exchanges for a session via `auth/login/2fa`.
     *
     * Without these two fields the challenge decodes to nothing, `accessToken`
     * comes back null, and every 2FA login fails as "Login succeeded but no
     * token received". */
    @SerializedName("requires_2fa") val requires2fa: Boolean? = null,
    @SerializedName("two_factor_token") val twoFactorToken: String? = null,
    /** The second-step methods the challenge accepts, e.g. `["totp"]`,
     * `["totp","passkey"]` or `["passkey"]`. This app can only answer
     * "totp" (a 6-digit code); null means an older server that did not say,
     * which is treated as TOTP. */
    @SerializedName("available_methods") val availableMethods: List<String>? = null,
)

/**
 * Exchange a TOTP code for a real session, completing a login that stopped at
 * the two-factor challenge.
 *
 * `two_factor_token` is the name the server reads. Any other name
 * (e.g. `temp_token`) is silently dropped and the request fails as a
 * missing field.
 */
data class TwoFactorVerifyRequest(
    @SerializedName("two_factor_token") val twoFactorToken: String,
    val code: String,
    val method: String, // "totp" or "passkey"
)

/**
 * Reply to `auth/login/2fa` — the same shape as a password-only sign-in's
 * reply ([SrpStep2Response]). It carries the KDF params and encrypted key
 * blobs, which Note needs: the vault key is derived from them plus the
 * password, and a session without one cannot read a single note.
 */
data class TwoFactorVerifyResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String? = null,
    val user: UserResponse? = null,
    @SerializedName("kdf_params") val kdfParams: KdfParamsResponse? = null,
    @SerializedName("encrypted_keys") val encryptedKeys: EncryptedKeysResponse? = null,
    @SerializedName("signing_public_key") val signingPublicKey: String? = null,
)

/** Step 2 of the authenticated SRP step-up. Reuses [SrpStep2Request] on the way
 *  out; the reply carries a short-lived token instead of a session. */
data class ReauthStep2Response(
    @SerializedName("reauth_token") val reauthToken: String,
    @SerializedName("server_proof") val serverProof: String? = null,
)

/**
 * Request to delete the account.
 *
 * Identity is proven with a zero-knowledge SRP reauth token (see the
 * /auth/reauth/srp step1|step2 endpoints); the raw password is never sent.
 * `password` is a legacy fallback the server may accept, and is left unset by
 * this client.
 *
 * Note sends no `pseudonyms`: those exist so Track's location data, which is
 * keyed by per-circle pseudonym rather than user id, can be found. Note has
 * nothing of the kind, and the server defaults the field.
 */
data class DeleteAccountRequest(
    @SerializedName("reauth_token") val reauthToken: String? = null,
    val password: String? = null,
    /**
     * The user's explicit answer to the 409 the server returns when the account
     * still holds bought credit. Deleting destroys it — the record goes with the
     * account — so the server refuses the first attempt rather than doing it
     * quietly. Never set on the first try.
     */
    @SerializedName("forfeit_credit_balance") val forfeitCreditBalance: Boolean? = null,
)

/** Response after successful account deletion. Every billing field defaults, so
 *  a build talking to an older backend still decodes a successful delete. */
data class DeleteAccountResponse(
    val deleted: Boolean,
    /** Subscriptions the server stopped as part of the deletion. */
    @SerializedName("subscriptions_canceled") val subscriptionsCanceled: Int = 0,
    /**
     * App Store subscriptions still running. Only the customer can cancel these,
     * in their Apple account settings — Apple offers no server-side call.
     */
    @SerializedName("app_store_subscriptions_remaining") val appStoreSubscriptionsRemaining: Int = 0,
    /** Cancellations that failed. Non-zero means they are still being billed. */
    @SerializedName("subscription_cancellations_failed") val subscriptionCancellationsFailed: Int = 0,
    /**
     * What happened to the location and note data: "completed",
     * "not_applicable", "queued" or "failed". Defaulted so an older backend
     * still decodes.
     */
    @SerializedName("data_erasure") val dataErasure: String = "completed",
    /**
     * One flag to key off, so each client does not re-derive it from the counts
     * and one of them get it wrong. True when the user has to act themselves.
     */
    @SerializedName("billing_needs_attention") val billingNeedsAttention: Boolean = false,
)

data class SubscriptionInfo(
    val service: String = "",
    val plan: String = "",
    val status: String = "",
    @SerializedName("product_slug") val productSlug: String? = null,
)

data class UserResponse(
    val id: String,
    val email: String,
    @SerializedName("display_name") val displayName: String? = null,
    val subscriptions: List<SubscriptionInfo>? = null,
    /** Optional second address that can receive a password-reset link. Omitted
     * by the server when unset, so null here means "no recovery address", not
     * "field missing". */
    @SerializedName("recovery_email") val recoveryEmail: String? = null,
    @SerializedName("recovery_email_verified") val recoveryEmailVerified: Boolean = false,
    /**
     * True when somebody else set this account up and still holds its password
     * — a guardian holding a young person's account.
     *
     * Drives the standing line above the notes list. Nothing else tells that
     * person their notes are readable: location sharing puts a permanent
     * notification in the shade, a read produces no signal at all, and there is
     * no new-sign-in email. Defaults false, so a server that does not send it
     * shows nothing rather than a claim we cannot support.
     */
    @SerializedName("guardian_managed") val guardianManaged: Boolean = false,
) {
    val isPremium: Boolean
        get() = subscriptions?.any { it.status == "active" || it.status == "trialing" } == true

    val tierLabel: String
        get() = if (isPremium) "Premium" else "Free"
}

data class KdfParamsResponse(
    val salt: String,
    @SerializedName("memory_kib") val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
)

data class EncryptedKeysResponse(
    @SerializedName("master_key") val masterKey: EncryptedBlobResponse,
    @SerializedName("sharing_private_key") val sharingPrivateKey: EncryptedBlobResponse? = null,
    @SerializedName("signing_private_key") val signingPrivateKey: EncryptedBlobResponse? = null,
)

data class EncryptedBlobRequest(
    val nonce: String,
    val ciphertext: String,
)

/** GET /api/config — Turnstile site key + similar runtime configuration
 *  that the unauthenticated AuthActivity needs. */
data class AuthConfigResponse(
    @SerializedName("turnstileSiteKey") val turnstileSiteKey: String?,
)

/** Request-side companion to [KdfParamsResponse], used by
 *  POST /auth/srp/register. */
data class KdfParamsRequest(
    val salt: String,
    @SerializedName("memory_kib") val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
)

/** POST /api/v1/auth/srp/register body. */
data class SrpRegisterRequest(
    val email: String,
    @SerializedName("srp_salt") val srpSalt: String,
    @SerializedName("srp_verifier") val srpVerifier: String,
    @SerializedName("bcrypt_salt") val bcryptSalt: String,
    @SerializedName("kdf_params") val kdfParams: KdfParamsRequest,
    @SerializedName("encrypted_master_key") val encryptedMasterKey: EncryptedBlobRequest,
    @SerializedName("sharing_public_key") val sharingPublicKey: String,
    @SerializedName("encrypted_sharing_private_key") val encryptedSharingPrivateKey: EncryptedBlobRequest,
    @SerializedName("signing_public_key") val signingPublicKey: String,
    @SerializedName("encrypted_signing_private_key") val encryptedSigningPrivateKey: EncryptedBlobRequest,
    @SerializedName("recovery_key_hash") val recoveryKeyHash: String,
    @SerializedName("encrypted_master_key_for_recovery") val encryptedMasterKeyForRecovery: EncryptedBlobRequest,
    @SerializedName("captcha_token") val captchaToken: String?,
    /**
     * Somebody else is setting this account up and will keep the password —
     * a guardian creating one for a young person.
     *
     * Independent of age: it records the ARRANGEMENT. Note renders a standing
     * disclosure from it, telling the account's own user that whoever holds the
     * password can read their notes, because nothing else does — location
     * sharing is announced by the OS, a read is not, and there is no
     * new-sign-in email.
     */
    @SerializedName("guardian_managed") val guardianManaged: Boolean = false,
    /**
     * True when this surface actually SHOWED the Terms and got an explicit act
     * — the age and guardian-consent tick on the sign-up form. The server
     * records "presented" from it and "implied" otherwise, so sending true from
     * a path that showed nothing would make Terms §5 false.
     */
    @SerializedName("terms_presented") val termsPresented: Boolean = false,
    /** Which surface created the account, so a later dispute can be matched
     *  against what that surface displayed at the time. */
    @SerializedName("signup_surface") val signupSurface: String? = null,
    /**
     * The address of the person the account is FOR.
     *
     * Supplying it turns a guardian-managed signup into a real two-credential
     * account: the guardian keeps a bounded credential of their own, and a
     * second, unclaimed one is created for this address. The invitee is emailed
     * a link and sets their OWN password, which the guardian never sees.
     *
     * Without it the server creates an ordinary single-credential account — one
     * password, held by the guardian — because an arrangement the server can
     * only see one side of is the status quo, not a new capability.
     */
    @SerializedName("owner_email") val ownerEmail: String? = null,
    /** Random token sealing the invitee's copy of the master key. The server
     *  emails it and stores only its SHA-256. */
    @SerializedName("owner_setup_token") val ownerSetupToken: String? = null,
    /** The master key sealed under a key derived from [ownerSetupToken], with
     *  the SAME salt and KDF params as [encryptedMasterKey]. */
    @SerializedName("encrypted_master_key_for_owner") val encryptedMasterKeyForOwner: EncryptedBlobRequest? = null,
    /** When the guardian's access ends by itself, as yyyy-MM-dd. Clamped
     *  server-side to two years; absent means the ceiling. */
    @SerializedName("guardian_handover_date") val guardianHandoverDate: String? = null,
)

/** Successful POST /api/v1/auth/srp/register response. */
data class SrpRegisterResponse(
    @SerializedName("user_id") val userId: String,
    val email: String,
    @SerializedName("email_verification_sent") val emailVerificationSent: Boolean,
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
)

/** POST /api/v1/auth/verify-email body. */
data class VerifyEmailRequest(
    val token: String,
)

/** GET /api/v1/auth/email/status response. */
data class EmailVerificationStatusResponse(
    @SerializedName("email_verified") val emailVerified: Boolean,
    val email: String,
    @SerializedName("verification_sent_at") val verificationSentAt: String?,
    @SerializedName("can_resend") val canResend: Boolean,
)

data class EncryptedBlobResponse(
    val nonce: String,
    val ciphertext: String,
    @SerializedName("key_id") val keyId: String,
)

// Attestation DTOs (device trust)

data class AttestationStatusResponse(
    @SerializedName("apple_attest_enabled") val appleAttestEnabled: Boolean,
    @SerializedName("google_integrity_enabled") val googleIntegrityEnabled: Boolean,
    @SerializedName("android_keystore_enabled") val androidKeystoreEnabled: Boolean,
    @SerializedName("turnstile_enabled") val turnstileEnabled: Boolean,
)

data class AttestationChallengeResponse(
    val challenge: String,
    val method: String,
    @SerializedName("expires_at") val expiresAt: String,
)

data class GoogleIntegrityVerifyRequest(
    @SerializedName("integrity_token") val integrityToken: String,
    val nonce: String,
)

data class AndroidKeystoreVerifyRequest(
    @SerializedName("certificate_chain") val certificateChain: List<String>,
    val challenge: String,
)

data class TurnstileComboVerifyRequest(
    @SerializedName("turnstile_token") val turnstileToken: String,
    @SerializedName("certificate_chain") val certificateChain: List<String>?,
    val challenge: String?,
    @SerializedName("device_fingerprint") val deviceFingerprint: String?,
)

data class AttestationVerifyResponse(
    @SerializedName("attestation_token") val attestationToken: String,
    val platform: String,
    @SerializedName("expires_at") val expiresAt: String,
)

data class AndroidKeystoreVerifyResponse(
    @SerializedName("attestation_token") val attestationToken: String,
    val platform: String,
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("security_level") val securityLevel: String,
    @SerializedName("verified_boot_state") val verifiedBootState: String,
)

// ===== Identities from before a password reset =====

/**
 * A recovered pre-reset private key, sealed for this app by the account portal.
 *
 * The portal's own copy is wrapped under the auth MASTER key, which this app
 * never holds — so it seals a second copy under a value the SDK derives from the
 * key we already have. Absent when the recovery predates that.
 */
data class SealedPreviousIdentityDto(
    val nonce: String,
    val ciphertext: String
)

data class PreviousIdentityDto(
    @SerializedName("key_version") val keyVersion: Int,
    @SerializedName("app_sealed_private_key") val appSealedPrivateKey: SealedPreviousIdentityDto? = null
)

data class PreviousIdentitiesResponse(
    val identities: List<PreviousIdentityDto> = emptyList()
)
