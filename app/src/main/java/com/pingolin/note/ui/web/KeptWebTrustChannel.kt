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

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.pingolin.note.util.AccountIds
import com.pingolin.note.util.KeptWebTrustStore
import org.json.JSONObject

/**
 * `window.pingolinTrust`: the page's kept trust, answered for the account this device is signed in
 * as.
 *
 * The page (the web editor's keptTrust.ts) sends two requests, each a JSON string with a numeric
 * `id` that its reply carries back:
 *  - `{ op: 'keptTrust', v: 1, account }` → its copy, 'none', or 'unreadable' (a store that can't
 *    be read is never 'none');
 *  - `{ op: 'keepTrust', v: 1, account, pins, floors, owners }` → 'kept' or 'refused'.
 * Only the note origin's main frame is answered. A message that isn't this contract gets no answer
 * at all; one for another account than the signed-in one, or with none signed in, is refused.
 *
 * A WebMessageListener, not a JavascriptInterface: it exists only on the allow-listed origin.
 * Answered on the main thread in arrival order, so two keeps in flight land in the order sent.
 */
object KeptWebTrustChannel {

    const val NAME = "pingolinTrust"

    private val BLOBS = listOf("pins", "floors", "owners")

    /** The reply to one message, or null for no reply at all. [sourceOrigin] and [noteOrigin] are
     *  `scheme://host[:port]`. */
    fun decide(
        message: String?,
        sourceOrigin: String,
        isMainFrame: Boolean,
        noteOrigin: String,
        signedInAs: String?,
        store: KeptWebTrustStore,
    ): String? {
        if (!isMainFrame || sourceOrigin != noteOrigin || message == null) return null
        val m = try {
            JSONObject(message)
        } catch (e: Exception) {
            return null
        }
        val id = m.opt("id") as? Number ?: return null
        if ((m.opt("v") as? Number)?.toDouble() != 1.0) return null
        fun reply(state: String, fields: JSONObject.() -> Unit = {}): String =
            JSONObject().put("v", 1).put("state", state).apply(fields).put("id", id).toString()

        val me = AccountIds.canonical(signedInAs)
        if (me == null || AccountIds.canonical(m.opt("account") as? String) != me) return reply("refused")
        return when (m.opt("op")) {
            "keptTrust" -> when (val kept = store.get(me)) {
                is KeptWebTrustStore.Kept.Copy -> reply("copy") {
                    put("account", me)
                    put("pins", kept.pins ?: JSONObject.NULL)
                    put("floors", kept.floors ?: JSONObject.NULL)
                    put("owners", kept.owners ?: JSONObject.NULL)
                }
                KeptWebTrustStore.Kept.None -> reply("none") { put("account", me) }
                KeptWebTrustStore.Kept.Unreadable -> reply("unreadable")
            }
            "keepTrust" -> {
                val blobs = BLOBS.map { name ->
                    when {
                        m.isNull(name) -> null
                        m.opt(name) is String -> m.getString(name)
                        else -> return reply("refused")
                    }
                }
                if (store.keep(me, blobs[0], blobs[1], blobs[2])) reply("kept") else reply("refused")
            }
            else -> reply("refused")
        }
    }

    /** Register the listener on [web] (once per WebView: RetainedWebView.onceTrustChannel). False
     *  without WebMessageListener support: the page then finds no channel and keeps as before. */
    fun install(web: WebView, noteOrigin: String, store: KeptWebTrustStore, signedInAs: () -> String?): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        WebViewCompat.addWebMessageListener(web, NAME, setOf(noteOrigin)) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            val origin = "${sourceOrigin.scheme}://${sourceOrigin.authority}"
            val answer = decide(message.data, origin, isMainFrame, noteOrigin, signedInAs(), store)
                ?: return@addWebMessageListener
            replyProxy.postMessage(answer)
        }
        return true
    }
}
