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

package com.pingolin.note.ui.web

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * The shell side of the page's `shellSave()`: an origin-scoped
 * WebMessageListener named `pingolinSave` that writes a file the web app
 * hands over into the device's Downloads.
 *
 * Exists because the WebView's other exits are all dead ends for a `blob:`
 * URL — `<a download>` is inert (no download UI) and DownloadManager cannot
 * fetch the scheme — so without it attachment Download and the backup export
 * would silently do nothing in the app. The page decrypts and posts
 * `{filename, mimeType, dataBase64}`; this decodes and writes it.
 *
 * CAPTURES THE APPLICATION CONTEXT ONLY, deliberately. The listener is
 * registered per-WebView (a once-per-WebView latch, exactly like the
 * document-start scripts) and the WebView outlives every Activity — a
 * listener holding an Activity would be the same leak the retained-client
 * rules exist to prevent, with a dead-Activity Toast crash on top.
 *
 * `addWebMessageListener`'s origin allowlist is the security boundary: only
 * documents on OUR origin get the injected object, so the account portal and
 * any external page the WebView ever shows cannot reach this. The payload is
 * data the page could already read — its own decrypted attachment — so the
 * channel adds no new exposure; the filename is still sanitised because it
 * becomes a real filesystem/MediaStore name on OUR side of the boundary.
 */
class WebAppSaveChannel(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** @return true when the listener is registered — the caller latches on that. */
    fun register(web: WebView, origin: String): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(TAG, "WebMessageListener unsupported; in-app save stays disabled")
            return false
        }
        WebViewCompat.addWebMessageListener(web, "pingolinSave", setOf(origin)) {
                _, message, _, _, _ ->
            val payload = message.data ?: return@addWebMessageListener
            // Entry log at WARN (R8 strips i/d): "did the page's postMessage
            // actually cross the bridge" is the question every dead-save
            // debug session starts with.
            Log.w(TAG, "message received: ${payload.length} chars")
            scope.launch { runCatching { save(payload) }.onFailure {
                Log.w(TAG, "shell save failed", it)
                toast("Couldn't save the file")
            } }
        }
        // WARN, not INFO: R8 strips Log.i in release, and this line is the
        // only shell-side proof the listener exists when diagnosing a dead
        // Download (the page's fallback is deliberately silent).
        Log.w(TAG, "save channel registered for $origin")
        return true
    }

    private suspend fun save(payload: String) {
        val obj = JSONObject(payload)
        val rawName = obj.optString("filename").ifBlank { "download" }
        // The page names the file; WE put that name on the filesystem. Strip
        // separators and traversal so a hostile-looking name cannot escape
        // Downloads, and cap the length MediaStore would reject anyway.
        val filename = rawName
            // Colon included: the emulated-FAT volume rejects it,
            // so a name like "image:297" (a documentsui id that
            // ended up as a filename) makes the MediaStore insert
            // throw.
            .replace(Regex("[/\\\\: ]"), "_")
            .removePrefix(".")
            .take(120)
            .ifBlank { "download" }
        val mime = obj.optString("mimeType").ifBlank { "application/octet-stream" }
        val bytes = Base64.decode(obj.getString("dataBase64"), Base64.DEFAULT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            val uri = resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: error("MediaStore refused the insert")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("no output stream for $uri")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            // API 26–28: MediaStore.Downloads does not exist yet. Write into
            // the app's external files and hand the entry to DownloadManager
            // so it appears in the system Downloads UI.
            val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: error("no external files dir")
            val out = File(dir, filename)
            out.writeBytes(bytes)
            @Suppress("DEPRECATION")
            (appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
                .addCompletedDownload(
                    filename, filename, true, mime, out.absolutePath, bytes.size.toLong(), true,
                )
        }
        Log.w(TAG, "saved $filename (${bytes.size} B) to Downloads")
        toast("Saved $filename to Downloads")
    }

    private suspend fun toast(text: String) = withContext(Dispatchers.Main) {
        Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "WebAppSaveChannel"
    }
}
