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

package com.pingolin.note.domain.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.pingolin.note.collab.AttachmentBlobFormat
import com.pingolin.note.collab.NoteCrypto
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.CreateAttachmentRequest
import com.pingolin.note.data.remote.dto.PresignUploadRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The image quick-add pipeline: create a note, push it, encrypt the picked
 * file under the note's content key and register it as an attachment — all
 * BEFORE any editor opens.
 *
 * The web editor cannot take over the uri — a WebView page can't read
 * content:// — so the shell does the whole upload here and then opens a note
 * whose attachment already exists.
 *
 * Ordering matters twice:
 *  - The server's POST /api/v1/attachments verifies that the item row exists
 *    before inserting, so the freshly created dirty row is PUSHED first via
 *    [SyncRepository.pushPendingChanges] rather than left to race an
 *    enqueued sync.
 *  - The uri is opened and size-capped BEFORE the note is created, so the
 *    common local failures (revoked grant, oversized pick) leave no debris.
 *    Failures after creation delete the never-shown empty note again.
 */
@Singleton
class AttachmentUploader @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val noteApi: NoteApi,
    private val noteRepository: NoteRepository,
    private val syncRepository: SyncRepository,
    private val noteCrypto: NoteCrypto,
    private val okHttpClient: OkHttpClient,
    private val quotaManager: QuotaManager,
) {

    sealed interface Outcome {
        /** The note exists (locally + server-side) with the file attached. */
        data class Attached(val noteId: String) : Outcome

        /** Rejected before upload: over [MAX_ATTACHMENT_BYTES]. */
        data object TooLarge : Outcome

        /** The uri would not open or read (revoked grant, gone provider). */
        data object Unreadable : Outcome

        /** Server 402 — free storage cap; caller shows the Premium sheet. */
        data object QuotaExceeded : Outcome

        /** Anything else; details are in logcat, the note was cleaned up. */
        data object Failed : Outcome
    }

    suspend fun createNoteWithAttachment(uri: Uri): Outcome {
        // Reject declared oversize before opening the provider. The streaming
        // encryptor applies the same ceiling while reading, because some
        // providers omit OpenableColumns.SIZE or report it inaccurately.
        val declaredSize = withContext(Dispatchers.IO) {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                        cursor.getLong(idx)
                    } else {
                        null
                    }
                }
        }
        if (declaredSize != null && declaredSize > MAX_ATTACHMENT_BYTES) return Outcome.TooLarge

        val readable = withContext(Dispatchers.IO) {
            try {
                appContext.contentResolver.openInputStream(uri)?.use { true } ?: false
            } catch (e: Exception) {
                Log.w(TAG, "quick-add uri open failed", e)
                false
            }
        }
        if (!readable) return Outcome.Unreadable

        val mimeType = appContext.contentResolver.getType(uri) ?: "application/octet-stream"
        // OpenableColumns.DISPLAY_NAME, NOT lastPathSegment: for a documentsui
        // pick the last path segment is the DOCUMENT ID ("image:297"), which
        // then becomes the attachment's user-visible filename everywhere —
        // and its colon is invalid on the FAT-emulated storage a later
        // save-to-Downloads writes to.
        val fileName = withContext(Dispatchers.IO) {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                        cursor.getString(idx)
                    } else {
                        null
                    }
                }
        }?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "attachment"

        val entity = try {
            // has_attachments is CONTENT, not a server column (E2EE: the server
            // never rewrites encrypted content) — the uploading client must set
            // it or the card badge and the "Has attachment" filter never see
            // this note. The web editor writes it AFTER upload via a second
            // content PUT; here it is pre-set at create, which is safe because
            // every non-Attached outcome deletes the note again. It is not in
            // NoteContent.KNOWN_KEYS, so it rides `extras` verbatim.
            noteRepository.createNote(NoteContent(extras = mapOf("has_attachments" to true)))
        } catch (e: Exception) {
            // Most likely no vault key (signed out mid-flow).
            Log.e(TAG, "createNote for quick-add failed", e)
            return Outcome.Failed
        }

        val outcome = try {
            pushAndUpload(entity.id, uri, fileName, mimeType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "quick-add attach failed", e)
            Outcome.Failed
        }
        if (outcome !is Outcome.Attached) {
            // The empty note was never shown to the user — don't leave it
            // behind on a failed attach. deleteNote treats a never-synced
            // row's server 404 as done, so this is safe even when the push
            // above was the step that failed. (A cancellation skips this;
            // the user chose to leave, and the row may legitimately have
            // its attachment already.)
            try {
                noteRepository.deleteNote(entity.id)
            } catch (e: Exception) {
                Log.w(TAG, "cleanup of quick-add note ${entity.id} failed", e)
            }
        }
        return outcome
    }

    private suspend fun pushAndUpload(
        noteId: String,
        uri: Uri,
        fileName: String,
        mimeType: String,
    ): Outcome {
        // Make the note exist server-side (see the class KDoc for why this
        // must complete before createAttachment). Offline → the POST below
        // fails → the caller cleans up; attaching needs the network anyway.
        syncRepository.pushPendingChanges()

        // createNote minted + vault-wrapped a per-note content key moments
        // ago, so ensureContentKey always resolves for OUR fresh note. (The
        // open editor needed a legacy/shared-note fallback here; this path
        // cannot hit either case.)
        val contentKey = noteRepository.ensureContentKey(noteId) ?: run {
            Log.e(TAG, "no content key for freshly created note")
            return Outcome.Failed
        }

        // Encrypt straight from the provider into an app-cache
        // ciphertext file. Only one 1 MiB plaintext chunk and its
        // sealed output are live; reading the whole file and sealing
        // it in one frame would hold the file and its ciphertext
        // together and can OOM on a low-memory phone.
        val encryptedFile = withContext(Dispatchers.IO) {
            File.createTempFile("note-attachment-", ".bin", appContext.cacheDir)
        }
        val plaintextSize = try {
            withContext(Dispatchers.IO) {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(encryptedFile).use { output ->
                        AttachmentBlobFormat.encryptV2ToStream(
                            key = contentKey,
                            input = input,
                            output = output,
                            maxPlainBytes = MAX_ATTACHMENT_BYTES,
                        )
                    }
                } ?: throw IllegalStateException("attachment uri became unreadable")
            }
        } catch (_: AttachmentBlobFormat.SizeLimitExceededException) {
            encryptedFile.delete()
            return Outcome.TooLarge
        } catch (e: CancellationException) {
            encryptedFile.delete()
            throw e
        } catch (e: Exception) {
            encryptedFile.delete()
            Log.e(TAG, "attachment encryption failed", e)
            return Outcome.Failed
        }
        if (plaintextSize > MAX_ATTACHMENT_BYTES) {
            encryptedFile.delete()
            return Outcome.TooLarge
        }

        // The size the server binds the presigned PUT to is the exact ciphertext
        // OBJECT — the bytes OkHttp PUTs below, since encryptedFile.asRequestBody uses
        // the file length as the Content-Length — NOT encryptV2ToStream's plaintext
        // return. The v2 object is larger than its plaintext (a header plus a 17-byte
        // tag per chunk), so declaring the plaintext length would disagree with the
        // backend's exact-size enforcement. encryptedFile is closed and flushed by the
        // use{} above.
        val objectSize = encryptedFile.length()

        try {
            val presignResponse = noteApi.presignUpload(
                PresignUploadRequest(sizeBytes = objectSize.toInt()),
            )
            val presigned = presignResponse.body()?.data
            if (!presignResponse.isSuccessful || presigned == null) {
                Log.w(TAG, "presign failed: ${presignResponse.code()}")
                return Outcome.Failed
            }

            val uploaded = withContext(Dispatchers.IO) {
                val body = encryptedFile.asRequestBody("application/octet-stream".toMediaType())
                okHttpClient.newCall(
                    Request.Builder().url(presigned.url).put(body).build(),
                ).execute().use { it.isSuccessful }
            }
            if (!uploaded) {
                Log.w(TAG, "blob PUT failed")
                return Outcome.Failed
            }

            // The content_type field name must match what the other clients
            // write, so shared attachments decrypt cross-platform; the metadata
            // is content-key encrypted too. Built via JSONObject so a filename
            // with quotes/backslashes is escaped rather than breaking the JSON.
            val metaJson = JSONObject()
                .put("filename", fileName)
                .put("content_type", mimeType)
                .toString()
            val metaEncrypted = withContext(Dispatchers.Default) {
                noteCrypto.encryptNoteWithContentKeyV2(metaJson, contentKey, noteId)
            }

            val createResp = noteApi.createAttachment(
                CreateAttachmentRequest(
                    r2Key = presigned.objectKey,
                    itemId = noteId,
                    encryptedMeta = metaEncrypted.ciphertextB64,
                    metaNonce = metaEncrypted.nonceB64,
                    sizeBytes = objectSize.toInt(),
                ),
            )
            return when {
                createResp.isSuccessful -> {
                    // Storage usage changed — refresh so the Settings bars/badge
                    // (and the free-tier pre-flight state) catch up.
                    quotaManager.refresh()
                    Outcome.Attached(noteId)
                }
                createResp.code() == 402 -> {
                    // Free storage cap hit server-side (quota_storage_exceeded —
                    // POST /api/v1/attachments has no other quota gate).
                    quotaManager.refresh()
                    Outcome.QuotaExceeded
                }
                else -> {
                    Log.w(TAG, "createAttachment failed: ${createResp.code()}")
                    Outcome.Failed
                }
            }
        } finally {
            encryptedFile.delete()
        }
    }

    companion object {
        private const val TAG = "AttachmentUploader"

        /** 25 MiB — keeps Android uploads within the re-key worker's bounded read. */
        const val MAX_ATTACHMENT_BYTES = 26_214_400L
        const val MAX_ATTACHMENT_MB = MAX_ATTACHMENT_BYTES / (1024 * 1024)
    }
}
