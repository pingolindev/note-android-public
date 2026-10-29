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

package com.pingolin.note.importer

import android.content.ContentResolver
import android.net.Uri
import com.pingolin.note.collab.NoteCrypto
import com.pingolin.note.data.local.dao.ItemDao
import com.pingolin.note.data.local.entity.ItemEntity
import com.pingolin.note.domain.repository.ChecklistItem
import com.pingolin.note.domain.repository.NoteContent
import com.pingolin.note.importer.model.ImportProgress
import com.pingolin.note.importer.model.ImportSource
import com.pingolin.note.importer.model.ImportedNote
import com.pingolin.note.importer.parser.ImportParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed class ImportState {
    data object Idle : ImportState()
    data class Importing(val current: Int, val currentTitle: String?) : ImportState()
    data class Complete(val count: Int) : ImportState()
    data class Error(val message: String) : ImportState()
}

@Singleton
class ImportRepository @Inject constructor(
    private val itemDao: ItemDao,
    private val noteCrypto: NoteCrypto,
    private val sessionManager: com.pingolin.note.util.SessionManager,
    private val userMetadata: com.pingolin.note.domain.repository.UserMetadataStore,
) {
    /**
     * Import notes from a file URI.
     * Parses, encrypts, stores locally, and marks dirty for sync.
     */
    fun importNotes(
        contentResolver: ContentResolver,
        uri: Uri,
        source: ImportSource,
        /** Supplied on the second pass, once the user has answered the prompt a
         *  sealed file raised on the first. */
        passphrase: String? = null,
    ): Flow<ImportState> = flow {
        emit(ImportState.Idle)

        // Restoring a backup does the metadata FIRST. Notebooks have to exist
        // before the notes that reference them, or the parser drops each
        // note's notebookId as dangling and everything lands unfiled.
        if (source == ImportSource.PINGOLIN_BACKUP) {
            restoreMetadata(contentResolver, uri, passphrase)
        }

        val inputStream = contentResolver.openInputStream(uri)
            ?: run {
                emit(ImportState.Error("Cannot open file"))
                return@flow
            }

        val parser = if (source == ImportSource.PLAIN_TEXT && isZipMime(contentResolver, uri)) {
            ImportParserFactory.createPlainTextZipParser()
        } else {
            ImportParserFactory.createFileParser(source, passphrase)
        }

        inputStream.use { stream ->
            ingestProgress(parser.parse(stream)) { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Merge a backup's notebooks, label colours, editor pins and share
     * recipients into the user-metadata blob, in ONE write.
     *
     * One write matters: all of it lives in a single `item_type=10` row that
     * every client rewrites whole, so doing it a notebook at a time would be N
     * round-trips racing over one row — which is how duplicate metadata rows
     * come about.
     *
     * Best-effort. A backup whose metadata cannot be read still restores its
     * notes; they just arrive unfiled, which is recoverable, where failing the
     * whole restore is not.
     */
    private suspend fun restoreMetadata(
        contentResolver: ContentResolver,
        uri: Uri,
        passphrase: String?,
    ) {
        // Unseal here too. This reads the file a second time, independently of
        // the parser, so a sealed backup that skipped this step would restore
        // its notes and silently lose every notebook and label colour.
        val backup = runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                val text = stream.bufferedReader().readText()
                val body = if (com.pingolin.note.importer.parser.looksLikeSealedBackup(text)) {
                    if (passphrase.isNullOrEmpty()) return
                    uniffi.pingolin_mobile.openBackupFile(text, passphrase)
                } else {
                    text
                }
                org.json.JSONObject(body)
            }
        }.getOrNull() ?: return

        if (backup.optString("format") != BackupFormat.FORMAT) return
        if (backup.optInt("formatVersion", 0) > BackupFormat.VERSION) return

        runCatching {
            userMetadata.mutate { root -> BackupFormat.mergeMetadata(root, backup) }
        }
    }

    /**
     * Shared pipeline: consume an `ImportProgress` stream, batch-encrypt
     * notes 50 at a time, persist, and emit the corresponding
     * `ImportState`s on `output`. Exists so any remote-import source can
     * reuse the same encrypt-and-persist tail.
     */
    private suspend fun ingestProgress(
        progress: Flow<ImportProgress>,
        output: suspend (ImportState) -> Unit,
    ) {
        val batch = mutableListOf<ItemEntity>()
        var totalImported = 0
        // A RESTORE carries the notes' original ids, so every row on
        // a second run collides with the live note. Writing those
        // with REPLACE would overwrite newer local edits with the
        // backup's older content, so a batch of id-carrying notes
        // goes in insert-if-absent instead. A foreign import has no
        // ids and keeps the REPLACE path.
        var restoring = false

        suspend fun flush() {
            if (batch.isEmpty()) return
            totalImported += if (restoring) {
                itemDao.insertIfAbsentAll(batch.toList()).count { it != -1L }
            } else {
                itemDao.upsertAll(batch.toList())
                batch.size
            }
            batch.clear()
        }

        progress.collect { event ->
            when (event) {
                is ImportProgress.Note -> {
                    if (event.note.id != null) restoring = true
                    batch.add(importedNoteToEntity(event.note))
                    if (batch.size >= BATCH_SIZE) flush()
                }
                is ImportProgress.Parsing -> {
                    output(ImportState.Importing(event.current, event.currentTitle))
                }
                is ImportProgress.Done -> flush()
                is ImportProgress.Error -> {
                    output(ImportState.Error(event.message))
                }
            }
        }

        output(ImportState.Complete(totalImported))
    }

    private fun importedNoteToEntity(note: ImportedNote): ItemEntity {
        val content = NoteContent(
            title = note.title,
            body = note.body,
            noteType = note.noteType,
            color = note.color,
            isPinned = note.isPinned,
            isArchived = note.isArchived,
            labels = note.labels,
            checklistItems = note.checklistItems?.map { ChecklistItem(it.text, it.checked) },
            // Restore-only. Null on a foreign import, where the source has no
            // equivalent, so those keep NoteContent's own defaults.
            sortOrder = note.sortOrder ?: 0,
            notebookId = note.notebookId,
            editMode = note.editMode ?: "markdown",
            extras = note.extras,
        )

        val json = content.toJson()
        val vaultBytes = vaultKeyBytes()
            ?: throw IllegalStateException("Can't open your notes on this device. Please sign in again.")

        // A restore reuses the backup's id — that is what makes a repeat
        // converge rather than doubling the account, both locally
        // (insertIfAbsent) and on the server (ON CONFLICT (id) DO NOTHING).
        val id = note.id ?: UUID.randomUUID().toString()
        val contentKey = noteCrypto.generateContentKey()
        // AAD v2: bind ciphertext to this note's id (see NoteCrypto).
        val encrypted = noteCrypto.encryptNoteWithContentKeyV2(json, contentKey, id)
        val wrapped = noteCrypto.wrapWithVault(contentKey, vaultBytes)
        val now = Instant.now()

        return ItemEntity(
            id = id,
            userId = "",
            itemType = 1,
            encryptedContent = encrypted.ciphertextB64,
            contentNonce = encrypted.nonceB64,
            decryptedContent = json,
            title = note.title,
            version = 1,
            createdAt = note.createdAt?.let { Instant.ofEpochMilli(it) } ?: now,
            updatedAt = note.updatedAt?.let { Instant.ofEpochMilli(it) } ?: now,
            color = note.color,
            isPinned = note.isPinned,
            isArchived = note.isArchived,
            isDirty = true,
            contentSize = encrypted.ciphertextB64.length,
            wrappedContentKey = wrapped.wrappedKeyB64,
            wrappedContentKeyNonce = wrapped.nonceB64,
        )
    }

    private fun vaultKeyBytes(): ByteArray? {
        val b64 = sessionManager.getMasterKey() ?: return null
        return try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            if (bytes.size == 32) bytes else null
        } catch (_: Exception) { null }
    }

    private fun isZipMime(contentResolver: ContentResolver, uri: Uri): Boolean {
        val mime = contentResolver.getType(uri) ?: ""
        return mime == "application/zip" || mime == "application/x-zip-compressed"
    }

    private companion object {
        const val BATCH_SIZE = 50
    }
}
