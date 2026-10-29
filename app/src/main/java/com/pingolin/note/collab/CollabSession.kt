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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import uniffi.collab.YjsDoc
import uniffi.collab.decryptCollabFrame
import uniffi.collab.deriveCollabKey
import uniffi.collab.deterministicSeedUpdate
import uniffi.collab.encryptCollabFrame
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Yjs-over-WebSocket collaboration client speaking the same wire protocol
 * as the web client. Talks to the note server's relay at
 * `/api/v1/collab/{noteId}` and merges remote updates into a local
 * `YjsDoc` via the note-collab-sdk (yrs) UniFFI binding.
 *
 * Every frame on the wire is XChaCha20-Poly1305 encrypted with a per-note
 * session key derived from the note's content key (same bytes on both
 * sides). The relay stays blind to document contents.
 *
 * Usage:
 *   val session = CollabSession(httpClient, baseUrl, noteId, contentKey)
 *   session.connect(initialBody)
 *   session.textFlow.collect { newText -> /* update editor */ }
 *   session.applyLocalReplace(newBody)   // local edit — debounced outside
 *   session.destroy()
 */
class CollabSession(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val noteId: String,
    /** 32-byte shared secret (per-note content key). */
    contentKey: ByteArray,
    /** Directory for the encrypted persisted CRDT state (null = no persistence). */
    stateDir: File? = null,
) {
    private val doc: YjsDoc = YjsDoc()
    private val sessionKey: ByteArray = deriveCollabKey(noteId, contentKey)
    /** Encrypted-at-rest CRDT-state file for this note; the blob is sealed under
     *  [sessionKey] (same key + framing as the wire frames) so no plaintext note
     *  content is ever written to disk. */
    private val stateFile: File? = stateDir?.let { File(it, "$noteId.yjs") }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private var connected = false
    private var closed = false
    /** Set true (inside [connect]) once the restore→snapshot→seed decision has run.
     * Guards an EXTERNAL [seedIfEmpty] call (for example when /sync/pull loads the DB
     * body) from racing that decision while connect is still fetching the server
     * snapshot with the doc observably empty — which would duplicate text. @Volatile:
     * written on the IO connect coroutine, read on Main. */
    @Volatile private var seedDecided = false
    private val retryAttempt = AtomicInteger(0)
    /** Debounced server-snapshot upload while editing, so an app-kill mid-edit
     *  (no clean destroy/disconnect) still leaves a recent snapshot for the next
     *  device to adopt. */
    private var snapshotUploadJob: Job? = null
    /** Debounced LOCAL encrypted-state persist while remote frames stream in, so a
     *  burst of SYNC_UPDATE frames coalesces into one disk write instead of
     *  re-encoding + encrypting the full CRDT state on every frame. */
    private var persistStateJob: Job? = null

    private val _textFlow = MutableStateFlow("")
    val textFlow: StateFlow<String> = _textFlow.asStateFlow()

    private val _connectedFlow = MutableStateFlow(false)
    val connectedFlow: StateFlow<Boolean> = _connectedFlow.asStateFlow()

    /** Seed the doc with the editor's current text and open the socket.
     *  `initialBody` ensures the doc has something to sync before the
     *  first remote replies come back — otherwise remote updates would
     *  "overwrite" a legitimate local snapshot. */
    fun connect(initialBody: String) {
        // Network I/O (the snapshot GET) so the whole open runs on IO. The doc,
        // _textFlow and openSocket are all thread-safe.
        scope.launch {
            // Restore the note's prior CRDT state (history + tombstones) BEFORE
            // seeding, so a relay replaying old frames can't resurrect deleted
            // content: yrs dedups any op already covered by the restored state vector.
            restorePersistedState()
            // Prefer the server's canonical encrypted snapshot over re-seeding the
            // body from plain text: re-deriving CRDT ops from text diverges from a
            // peer that reached the same text via edits, duplicating the edited
            // portion on merge. The snapshot IS that peer's op-structure.
            fetchServerSnapshot()
            val current = doc.getText()
            if (current.isEmpty()) {
                // Fresh (no prior/server state): seed the DB body DETERMINISTICALLY
                // so a concurrent seed of the same note on another device merges to
                // a single copy instead of duplicating (see seedIfEmpty).
                seedIfEmpty(initialBody)
            } else if (initialBody.isNotEmpty() && current != initialBody) {
                // Restored/snapshot state is stale vs the synced DB body (edited on
                // another device since). Reconcile as a delta over the restored
                // history so replayed stale ops stay deduped.
                doc.replaceText(initialBody)
                doc.drainLocalUpdates()
            }
            // Decision committed — an external seedIfEmpty may now run safely.
            seedDecided = true
            _textFlow.value = doc.getText()
            openSocket()
        }
    }

    /** Seed [body] into the doc IFF it is still empty. Uses a DETERMINISTIC seed
     *  (fixed client id) so that if another device seeds the same body
     *  concurrently, the two seeds are identical CRDT items and merge to a single
     *  copy rather than duplicating. No-op once the doc has any content, so it
     *  never compounds duplicates — and it's safe to call again when the DB body
     *  loads AFTER a fresh-login open connected with an empty body (which would
     *  otherwise leave the editor blank). */
    fun seedIfEmpty(body: String) {
        if (closed || body.isEmpty()) return
        if (doc.getText().isNotEmpty()) return
        val ok = runCatching {
            val seed = deterministicSeedUpdate(body)
            if (seed.isNotEmpty()) doc.applyUpdate(seed)
        }.isSuccess
        if (ok && doc.getText().isNotEmpty()) {
            _textFlow.value = doc.getText()
        }
    }

    /** Whether [connect]'s restore→snapshot→seed decision has already run.
     *  Callers MUST gate an external [seedIfEmpty] on this. */
    fun hasSeedDecided(): Boolean = seedDecided

    /** Apply a full-text replace driven by the user's editor. Produces
     *  the minimal delta against the prior doc state and ships it. */
    fun applyLocalReplace(newText: String) {
        if (closed) return
        val current = doc.getText()
        if (current == newText) return
        val ok = runCatching { doc.replaceText(newText) }.isSuccess
        if (!ok) return
        shipLocalUpdates()
        scheduleSnapshotUpload()
        _textFlow.value = newText
    }

    /** Shut down — safe to call multiple times. */
    fun destroy() {
        closed = true
        persistState()
        // Encode the final snapshot BEFORE closing the doc, then PUT it on a
        // detached coroutine that outlives scope.cancel() — so the next device to
        // open this note adopts our canonical state instead of re-seeding.
        val finalBlob = encodeSnapshotBlob()
        ws?.close(NORMAL_CLOSE, "client destroy")
        ws = null
        try { doc.close() } catch (_: Throwable) {}
        scope.cancel()
        if (finalBlob != null && finalBlob.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch { uploadSnapshot(finalBlob) }
        }
    }

    // ---------- Encrypted CRDT-state persistence ----------

    /** Restore the note's persisted CRDT state into the fresh doc. Best-effort:
     *  a missing/corrupt/stale blob just means we start fresh and sync reconciles. */
    private fun restorePersistedState() {
        val f = stateFile ?: return
        if (!f.exists()) return
        runCatching {
            val state = decryptCollabFrame(sessionKey, f.readBytes()) ?: return
            doc.applyUpdate(state)
        }
    }

    /** Encode the full CRDT state (history + tombstones) sealed under the per-note
     *  collab key — the same blob is written locally AND uploaded as the server
     *  snapshot. Null if the doc is closed/unreadable. */
    private fun encodeSnapshotBlob(): ByteArray? = runCatching {
        encryptCollabFrame(sessionKey, doc.encodeDiffSince(EMPTY_STATE_VECTOR))
    }.getOrNull()

    /** Persist the full CRDT state locally, encrypted, so the next session dedups
     *  any replayed stale ops. */
    private fun persistState() {
        val f = stateFile ?: return
        val blob = encodeSnapshotBlob() ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeBytes(blob)
        }
    }

    // ---------- Server-side snapshot (cross-device canonical CRDT state) ----------

    private fun snapshotUrl(): String = "$baseUrl/api/v1/collab/$noteId/snapshot"

    /** GET the server's stored encrypted snapshot (if any) and apply it, so we
     *  adopt a peer's canonical CRDT op-structure instead of re-deriving ops from
     *  the DB text (which duplicates edited content on merge). Best-effort +
     *  blocking; called from the IO connect coroutine. */
    private fun fetchServerSnapshot() {
        if (closed) return
        runCatching {
            val req = Request.Builder().url(snapshotUrl()).get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 200) {
                    // Cap the read so a hostile/compromised relay can't OOM us
                    // with a huge (or chunked, length-unknown) snapshot body.
                    // peekBody buffers at most MAX_SNAPSHOT_BYTES; an oversized
                    // body is truncated → decrypt fails → we start fresh (the
                    // same best-effort fallback as a missing/corrupt snapshot).
                    resp.peekBody(MAX_SNAPSHOT_BYTES).bytes().let { bytes ->
                        decryptCollabFrame(sessionKey, bytes)?.let { doc.applyUpdate(it) }
                    }
                }
            }
        }
    }

    /** PUT [blob] as the note's server snapshot so later joiners adopt it instead
     *  of re-seeding. Best-effort + blocking. */
    private fun uploadSnapshot(blob: ByteArray) {
        if (blob.isEmpty()) return
        runCatching {
            val req = Request.Builder().url(snapshotUrl())
                .put(blob.toRequestBody(OCTET_STREAM))
                .build()
            client.newCall(req).execute().use { }
        }
    }

    /** Debounce a server-snapshot upload for [SNAPSHOT_DEBOUNCE_MS] after the last
     *  local edit, coalescing rapid keystrokes into one PUT. */
    private fun scheduleSnapshotUpload() {
        if (closed) return
        snapshotUploadJob?.cancel()
        snapshotUploadJob = scope.launch {
            delay(SNAPSHOT_DEBOUNCE_MS)
            encodeSnapshotBlob()?.let { uploadSnapshot(it) }
        }
    }

    /** Debounce a LOCAL encrypted-state persist for [PERSIST_DEBOUNCE_MS] after the
     *  last remote frame. The terminal paths (destroy / handleDisconnect) still
     *  persist synchronously, so a write pending at teardown is never the only copy. */
    private fun schedulePersistState() {
        if (closed) return
        persistStateJob?.cancel()
        persistStateJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistState()
        }
    }

    // ---------- Socket lifecycle ----------

    private fun openSocket() {
        if (closed) return
        val scheme = if (baseUrl.startsWith("https")) "wss" else "ws"
        val host = baseUrl.substringAfter("://")
        val url = "$scheme://$host/api/v1/collab/$noteId"
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected = true
            retryAttempt.set(0)
            _connectedFlow.value = true
            // Sync step 1: announce our state vector so peers reply with
            // anything we're missing.
            val payload = ByteArrayOutputStream().apply {
                YProtocolCodec.writeVarUint(this, MSG_SYNC)
                YProtocolCodec.writeVarUint(this, SYNC_STEP1)
                YProtocolCodec.writeVarUint8Array(this, doc.encodeStateVector())
            }.toByteArray()
            sendFrame(webSocket, payload)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val plain = decryptCollabFrame(sessionKey, bytes.toByteArray()) ?: return
            handleFrame(webSocket, plain)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // relay never sends text frames, ignore
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleDisconnect()
        }
    }

    private fun handleDisconnect() {
        connected = false
        ws = null
        _connectedFlow.value = false
        persistState()
        // Also push our state to the server so a later joiner adopts it. No-op if
        // the scope is already cancelled (destroy handles that path detached).
        encodeSnapshotBlob()?.let { blob -> scope.launch { uploadSnapshot(blob) } }
        if (closed) return
        val attempt = retryAttempt.getAndIncrement()
        val delayMs = (1000L shl attempt.coerceAtMost(4)).coerceAtMost(30_000L)
        scope.launch {
            delay(delayMs)
            openSocket()
        }
    }

    // ---------- Sync protocol ----------

    private fun handleFrame(webSocket: WebSocket, plain: ByteArray) {
        val reader = YProtocolCodec.Reader(plain)
        val messageType = reader.readVarUint()
        when (messageType) {
            MSG_SYNC -> {
                val step = reader.readVarUint()
                val data = reader.readVarUint8Array()
                when (step) {
                    SYNC_STEP1 -> {
                        // Peer's state vector — reply with the diff they're missing.
                        val update = try { doc.encodeDiffSince(data) } catch (_: Throwable) { return }
                        val out = ByteArrayOutputStream().apply {
                            YProtocolCodec.writeVarUint(this, MSG_SYNC)
                            YProtocolCodec.writeVarUint(this, SYNC_STEP2)
                            YProtocolCodec.writeVarUint8Array(this, update)
                        }.toByteArray()
                        sendFrame(webSocket, out)
                    }
                    SYNC_STEP2, SYNC_UPDATE -> {
                        try { doc.applyUpdate(data) } catch (_: Throwable) { return }
                        val nextText = doc.getText()
                        if (_textFlow.value != nextText) _textFlow.value = nextText
                        // Capture the merged state (incl. peers' tombstones) so a
                        // later session dedups replays of these ops.
                        schedulePersistState()
                    }
                }
            }
            MSG_AWARENESS -> {
                // Awareness (cursor/presence) is not implemented on Android;
                // drop it silently so peers can continue to ship updates.
            }
        }
    }

    private fun shipLocalUpdates() {
        val updates = doc.drainLocalUpdates()
        if (updates.isEmpty()) return
        val socket = ws ?: return
        if (!connected) return
        for (u in updates) {
            val frame = ByteArrayOutputStream().apply {
                YProtocolCodec.writeVarUint(this, MSG_SYNC)
                YProtocolCodec.writeVarUint(this, SYNC_UPDATE)
                YProtocolCodec.writeVarUint8Array(this, u)
            }.toByteArray()
            sendFrame(socket, frame)
        }
    }

    private fun sendFrame(socket: WebSocket, plaintext: ByteArray) {
        val ct = try { encryptCollabFrame(sessionKey, plaintext) } catch (_: Throwable) { return }
        socket.send(ct.toByteString())
    }

    companion object {
        private const val MSG_SYNC = 0
        private const val MSG_AWARENESS = 1
        private const val SYNC_STEP1 = 0
        private const val SYNC_STEP2 = 1
        private const val SYNC_UPDATE = 2
        private const val NORMAL_CLOSE = 1000
        // Encoding of an empty yrs StateVector (varint client-count 0): passing it
        // to encodeDiffSince yields the FULL document state as an update.
        private val EMPTY_STATE_VECTOR = byteArrayOf(0)
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private const val SNAPSHOT_DEBOUNCE_MS = 5_000L
        private const val PERSIST_DEBOUNCE_MS = 5_000L
        /** Upper bound on a server snapshot read — a note's encrypted CRDT state
         *  is far smaller; anything larger is a hostile/oversized body. */
        private const val MAX_SNAPSHOT_BYTES = 8L * 1024 * 1024
    }
}
