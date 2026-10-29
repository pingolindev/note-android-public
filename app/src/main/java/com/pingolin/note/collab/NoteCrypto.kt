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

package com.pingolin.note.collab

/**
 * Thin wrapper over the uniffi `note-collab-sdk` per-note crypto
 * primitives. Exists so unit tests can stub out the native calls
 * without pulling in the Android arm64 `.so` — those functions are
 * top-level `fun`s in the generated bindings and can't be mocked
 * directly with MockK.
 */
interface NoteCrypto {
    fun generateContentKey(): ByteArray
    fun wrapWithVault(contentKey: ByteArray, vaultKey: ByteArray): WrappedKeyPair
    fun unwrapWithVault(wrappedKeyB64: String, nonceB64: String, vaultKey: ByteArray): ByteArray?
    fun encryptNoteWithContentKey(plaintext: String, contentKey: ByteArray): NotePayload
    fun decryptNoteWithContentKey(nonceB64: String, ciphertextB64: String, contentKey: ByteArray): String?

    /** AAD v2 (context-bound) note-content crypto: the ciphertext is bound
     *  to [itemId], so a server can't present note A's ciphertext (plus its
     *  wrapped content key) as note B. [itemId] MUST be the note's server
     *  item id, byte-identical to the value every other client binds — a v2
     *  AAD that doesn't byte-match is unreadable cross-client. Encrypt
     *  writes v2; decrypt tries v2 then falls back to v1 for ciphertext
     *  written before the binding existed. Use these for note CONTENT only:
     *  the user-metadata item (item_type 10) stays on the 2-arg path, which
     *  carries no item_id binding. */
    fun encryptNoteWithContentKeyV2(plaintext: String, contentKey: ByteArray, itemId: String): NotePayload
    fun decryptNoteWithContentKeyV2(nonceB64: String, ciphertextB64: String, contentKey: ByteArray, itemId: String): String?

    /** Wrap the 32-byte content key for a recipient's X25519 public key
     *  (hex or base64). Returns a base64-encoded sealed box. */
    fun sealForRecipient(contentKey: ByteArray, recipientPublicKey: String): String
    /** Unwrap a sealed box we received, using our X25519 private scalar. */
    fun openSeal(wrappedB64: String, privateKey: ByteArray): ByteArray?
    /** Derive our hex-encoded X25519 public key from the 32-byte private scalar. */
    fun deriveSharingPublicKey(privateKey: ByteArray): String?

    /** Sign a note share-invite wrap. Returns a 128-char lowercase hex
     *  Ed25519 signature, or null if signing isn't available right now
     *  (no signing seed loaded, malformed inputs). [signingSeed] is the
     *  account's 32-byte Ed25519 signing private key.
     *
     *  [contentKeyVersion] binds the item's content-key generation into the
     *  signature. A share-create signs under the item's CURRENT generation;
     *  a rotate signs under CURRENT + 1 (the server bumps by exactly 1).
     *  Recipients feed the verified generation into a per-item rollback
     *  floor so a replayed older wrap (valid sig, lower generation) is
     *  rejected. Base 1 when the local generation is unknown. */
    fun signNoteShareWrap(
        signingSeed: ByteArray,
        senderUserId: String,
        recipientUserId: String,
        itemId: String,
        contentKeyVersion: UInt,
        wrappedKeyB64: String,
    ): String?

    /** Verify a note share-invite wrap signature. Returns true on valid,
     *  false on cryptographically invalid, null when inputs can't be parsed
     *  (caller treats as 'unverified'). [contentKeyVersion] is the note's
     *  server-emitted content-key generation; the SDK verify-both accepts a
     *  signature made under it OR the legacy 0. Prefer
     *  [verifyNoteShareWrapGen] for the rollback-aware reader path. */
    fun verifyNoteShareWrap(
        signingPubkeyHex: String,
        signatureHex: String,
        senderUserId: String,
        recipientUserId: String,
        itemId: String,
        contentKeyVersion: UInt,
        wrappedKeyB64: String,
    ): Boolean?

    /** Verify a note share-invite wrap AND report the generation the
     *  signature ACTUALLY verified under. Returns null when the inputs
     *  can't be parsed (caller treats as a drop). The SDK
     *  verify-both tries [contentKeyVersion] then legacy 0; the returned
     *  [WrapVerify.effectiveGeneration] is the version that actually
     *  verified — the passed one, or 0 on the legacy-0 fallback (so a
     *  legacy sig can't launder a high claimed generation). The reader
     *  feeds [WrapVerify.effectiveGeneration] into a persisted per-item
     *  rollback floor. */
    fun verifyNoteShareWrapGen(
        signingPubkeyHex: String,
        signatureHex: String,
        senderUserId: String,
        recipientUserId: String,
        itemId: String,
        contentKeyVersion: UInt,
        wrappedKeyB64: String,
    ): WrapVerify?
}

data class WrappedKeyPair(val wrappedKeyB64: String, val nonceB64: String)

data class NotePayload(val nonceB64: String, val ciphertextB64: String)

/** Result of [NoteCrypto.verifyNoteShareWrapGen]: whether the wrap
 *  signature verified, and the content-key generation it verified
 *  UNDER ([effectiveGeneration] — the passed version, or 0 on the SDK's
 *  legacy-0 fallback). Mirrors uniffi.collab.WrapVerifyResult. */
data class WrapVerify(val verified: Boolean, val effectiveGeneration: UInt)

/** Production implementation — delegates straight to the uniffi bindings. */
object DefaultNoteCrypto : NoteCrypto {
    override fun generateContentKey(): ByteArray = uniffi.collab.generateContentKey()

    override fun wrapWithVault(contentKey: ByteArray, vaultKey: ByteArray): WrappedKeyPair {
        val w = uniffi.collab.wrapWithVault(contentKey, vaultKey)
        return WrappedKeyPair(w.wrappedKeyB64, w.nonceB64)
    }

    override fun unwrapWithVault(wrappedKeyB64: String, nonceB64: String, vaultKey: ByteArray): ByteArray? =
        uniffi.collab.unwrapWithVault(wrappedKeyB64, nonceB64, vaultKey)

    override fun encryptNoteWithContentKey(plaintext: String, contentKey: ByteArray): NotePayload {
        val c = uniffi.collab.encryptNoteWithContentKey(plaintext, contentKey)
        return NotePayload(c.nonceB64, c.ciphertextB64)
    }

    override fun decryptNoteWithContentKey(nonceB64: String, ciphertextB64: String, contentKey: ByteArray): String? =
        uniffi.collab.decryptNoteWithContentKey(nonceB64, ciphertextB64, contentKey)

    override fun encryptNoteWithContentKeyV2(plaintext: String, contentKey: ByteArray, itemId: String): NotePayload {
        val c = uniffi.collab.encryptNoteWithContentKeyV2(plaintext, contentKey, itemId)
        return NotePayload(c.nonceB64, c.ciphertextB64)
    }

    override fun decryptNoteWithContentKeyV2(nonceB64: String, ciphertextB64: String, contentKey: ByteArray, itemId: String): String? =
        uniffi.collab.decryptNoteWithContentKeyV2(nonceB64, ciphertextB64, contentKey, itemId)

    override fun sealForRecipient(contentKey: ByteArray, recipientPublicKey: String): String =
        uniffi.collab.sealForRecipient(contentKey, recipientPublicKey)

    override fun openSeal(wrappedB64: String, privateKey: ByteArray): ByteArray? =
        uniffi.collab.openSeal(wrappedB64, privateKey)

    override fun deriveSharingPublicKey(privateKey: ByteArray): String? =
        uniffi.collab.deriveSharingPublicKey(privateKey)

    override fun signNoteShareWrap(
        signingSeed: ByteArray,
        senderUserId: String,
        recipientUserId: String,
        itemId: String,
        contentKeyVersion: UInt,
        wrappedKeyB64: String,
    ): String? = try {
        uniffi.collab.signNoteShareWrap(
            signingSeed,
            uniffi.collab.NoteShareWrapParams(
                senderUserId = senderUserId,
                recipientUserId = recipientUserId,
                itemId = itemId,
                contentKeyVersion = contentKeyVersion,
                wrappedKeyB64 = wrappedKeyB64,
            ),
        )
    } catch (e: Exception) {
        android.util.Log.w("NoteCrypto", "signNoteShareWrap failed", e)
        null
    }

    override fun verifyNoteShareWrapGen(
        signingPubkeyHex: String,
        signatureHex: String,
        senderUserId: String,
        recipientUserId: String,
        itemId: String,
        contentKeyVersion: UInt,
        wrappedKeyB64: String,
    ): WrapVerify? = try {
        uniffi.collab.verifyNoteShareWrapGen(
            signingPubkeyHex,
            signatureHex,
            uniffi.collab.NoteShareWrapParams(
                senderUserId = senderUserId,
                recipientUserId = recipientUserId,
                itemId = itemId,
                contentKeyVersion = contentKeyVersion,
                wrappedKeyB64 = wrappedKeyB64,
            ),
        ).let { WrapVerify(it.verified, it.effectiveGeneration) }
    } catch (e: Exception) {
        android.util.Log.w("NoteCrypto", "verifyNoteShareWrapGen input parse failed", e)
        null
    }

    override fun verifyNoteShareWrap(
        signingPubkeyHex: String,
        signatureHex: String,
        senderUserId: String,
        recipientUserId: String,
        itemId: String,
        contentKeyVersion: UInt,
        wrappedKeyB64: String,
    ): Boolean? = try {
        uniffi.collab.verifyNoteShareWrap(
            signingPubkeyHex,
            signatureHex,
            uniffi.collab.NoteShareWrapParams(
                senderUserId = senderUserId,
                recipientUserId = recipientUserId,
                itemId = itemId,
                contentKeyVersion = contentKeyVersion,
                wrappedKeyB64 = wrappedKeyB64,
            ),
        )
    } catch (e: Exception) {
        android.util.Log.w("NoteCrypto", "verifyNoteShareWrap input parse failed", e)
        null
    }
}
