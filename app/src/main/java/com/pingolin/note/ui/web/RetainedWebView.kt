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

import android.content.Context
import android.content.MutableContextWrapper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One WebView for the whole process, kept alive across note opens.
 *
 * WHY THIS EXISTS — MEASURED, GALAXY A50 (frame-accurate)
 *   [WebAppActivity] is `standard` launch mode and closing a note finishes it, so a
 *   WebView built in onCreate and `destroy()`ed in onDestroy re-boots the whole SPA
 *   on every note tap. Tap to rendered content, measured by screenrecord frame diff
 *   on the same device and notes:
 *
 *       without retention   885 – 1,220 ms   (repeat opens no faster than the first)
 *       with retention      180 ms same note; 247 – 360 ms different note,
 *                           scaling with the TARGET note's size, not history
 *
 *   Repeat opens being no faster than the first (without retention) is the important
 *   number: it proves the cost is parse-and-execute, not download. Hashed assets are
 *   already served `public, max-age=31536000, immutable` and index.html `no-cache`,
 *   so there is no caching fix available here. The only way to stop paying it is to
 *   stop throwing the document away.
 *
 *   What a close shows is a BLACK frame for 82–234 ms while the detached WebView's
 *   compositor produces its next frame — not a flash of the previous note.
 *
 * THE CONTEXT PROBLEM
 *   A WebView holds its construction Context hard. Built with the Activity it would
 *   leak that Activity for the life of the process — the usual reason this pattern
 *   is avoided. It is therefore built on a [MutableContextWrapper] around the
 *   application context, re-pointed at the hosting Activity while attached and
 *   pointed back on release. The WebView still resolves the themed Activity context
 *   for dialogs, IME and window callbacks while it is on screen, and holds nothing
 *   but the application once it is not.
 *
 * WHAT THE CALLER STILL OWES
 *   Clients (`webViewClient`, `webChromeClient`) capture the Activity — they call
 *   finish() and read Activity state — so [WebAppActivity] re-assigns them on every
 *   acquire. Do not set them here; a retained client would keep a dead Activity
 *   alive and route a close() to it.
 *
 * SECURITY
 *   A retained WebView keeps a booted, authenticated document — cookies, the cached
 *   vault key in DOM storage, and whatever note was last open — resident for the
 *   life of the process. That is fine within one signed-in session and is NOT fine
 *   across a sign-out or a user switch, so [destroy] is called from the logout wipe.
 *   Screenshot protection is unaffected: FLAG_SECURE is a property of the window,
 *   which is still per-Activity.
 */
object RetainedWebView {

    private var web: WebView? = null
    private var wrapper: MutableContextWrapper? = null

    /** Origin of the document currently loaded, or null if nothing has loaded. */
    private var loadedOrigin: String? = null

    /**
     * Who currently holds the WebView.
     *
     * There is exactly one WebView and there can be more than one
     * [WebAppActivity] — a notification deep-link can start a second while one
     * is alive. Without this, the second [obtain] silently steals the view out
     * of the first's layout, and then the FIRST one's onDestroy calls
     * [release] and detaches it out from under the second, leaving a blank
     * screen. Identity is held weakly-by-reference here only for comparison; it
     * is cleared on release and never outlives the Activity it names.
     */
    private var host: Context? = null

    /** Whether the shell-marker document-start script is registered on the live WebView. */
    private var markerInstalled = false

    /** Whether the key-handoff document-start script is registered on the live WebView. */
    private var keysInstalled = false

    /** Whether the pingolinSave WebMessageListener is registered on the live WebView. */
    private var saveChannelInstalled = false

    /** See [markerInstalled] — the page->shell sync-nudge WebMessageListener. */
    private var nudgeChannelInstalled = false

    /** See [markerInstalled] — the kept-trust WebMessageListener (KeptWebTrustChannel). */
    private var trustChannelInstalled = false

    /**
     * A client that captures nothing, parked on the WebView while no Activity
     * hosts it. The real clients are anonymous objects inside WebAppActivity's
     * onCreate and hold the Activity (they call finish() and read its state);
     * leaving one installed across release() would keep the finished Activity
     * reachable from a process-lifetime object — the very leak the
     * MutableContextWrapper exists to prevent, through the client slot — and
     * would route a stray close() navigation to a dead Activity.
     */
    private val idleClient = object : WebViewClient() {
        /**
         * The parked window is exactly when the renderer is most likely to be
         * killed — it is invisible, so the OS reaps it first — and the
         * framework's default for an unhandled renderer death is to KILL THE
         * APP PROCESS. WebAppActivity's client handles this while an Activity
         * hosts the WebView; without THIS override, a renderer killed while
         * the WebView is parked takes the whole app down, and retention is
         * what creates that parked window.
         */
        override fun onRenderProcessGone(
            v: WebView,
            detail: android.webkit.RenderProcessGoneDetail,
        ): Boolean {
            android.util.Log.w(
                "RetainedWebView",
                "renderer gone while parked (crashed=${detail.didCrash()}); discarding",
            )
            discard()
            return true
        }
    }

    /**
     * True when a live document for [origin] is already booted, so the caller
     * should route inside it rather than call loadUrl and pay the boot again.
     */
    fun isBootedOn(origin: String): Boolean = web != null && loadedOrigin == origin

    /**
     * The retained WebView, re-hosted on [host]. Created on first call.
     *
     * [configure] runs ONLY on creation — it is for settings that survive a
     * document, not for clients, which the caller must set every time.
     */
    fun obtain(host: Context, configure: (WebView) -> Unit): WebView {
        this.host = host
        val existing = web
        if (existing != null) {
            wrapper?.baseContext = host
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val w = MutableContextWrapper(host)
        val created = WebView(w)
        created.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        configure(created)
        wrapper = w
        web = created
        return created
    }

    /** Record that a document for [origin] is now loaded. */
    fun markLoaded(origin: String) {
        loadedOrigin = origin
    }

    /**
     * Record that whatever the WebView now shows is NOT our booted document —
     * an off-origin navigation (the auth portal, an external link) or a failed
     * main-frame load. Without this, isBootedOn would keep answering true
     * after the document was replaced, and the next open would probe a page
     * that has no navigate hook.
     */
    fun markUnloaded() {
        loadedOrigin = null
    }

    /**
     * Run [block] once per WebView, latching only on SUCCESS.
     *
     * For `WebViewCompat.addDocumentStartJavaScript`, which registers a script
     * against the WebView and runs it at the start of every document it
     * subsequently loads. The WebView outlives the Activity, so calling it
     * from each onCreate would stack a fresh copy per note ever opened.
     *
     * Two independent latches, and each is set only when its installer returns
     * true: the first open can legitimately run before key bootstrap has
     * finished, and latching before running would record that failed install
     * as done — every later open would inherit a WebView with no key handoff
     * and the web app would ask for the password until the process died.
     */
    fun onceMarker(block: () -> Boolean) {
        if (!markerInstalled && block()) markerInstalled = true
    }

    /** See [onceMarker]. */
    fun onceKeys(block: () -> Boolean) {
        if (!keysInstalled && block()) keysInstalled = true
    }

    /** See [onceMarker] — same rule for the save-channel WebMessageListener,
     *  which is likewise registered against the WEBVIEW, not the document. */
    fun onceSaveChannel(block: () -> Boolean) {
        if (!saveChannelInstalled && block()) saveChannelInstalled = true
    }

    /** See [onceMarker] — same rule for the sync-nudge WebMessageListener. */
    fun onceNudgeChannel(block: () -> Boolean) {
        if (!nudgeChannelInstalled && block()) nudgeChannelInstalled = true
    }

    /** See [onceMarker] — same rule for the kept-trust WebMessageListener. */
    fun onceTrustChannel(block: () -> Boolean) {
        if (!trustChannelInstalled && block()) trustChannelInstalled = true
    }

    /**
     * Ask the live document to drain its offline queue, and wait for the
     * answer. MUST be called on the main thread; suspends rather than blocks.
     *
     * The web app's pending-edit queue lives in ITS IndexedDB, which the
     * sign-out wipe is about to delete — and the native abort guard only sees
     * the native store, so without this a switch would report "clean" while
     * the default editor's unsynced edits are lost. This gives the page one
     * bounded chance to upload them first, making the logout dialog's "any
     * unsynced changes will be uploaded first" true for BOTH stores.
     *
     * evaluateJavascript cannot await a Promise (it stringifies the pending
     * Promise immediately), so the starter kicks the async drain and records
     * completion in a global, which is then polled — each poll is itself an
     * evaluateJavascript whose callback lands back on Main, hence
     * suspendCancellableCoroutine rather than any blocking wait, which would
     * deadlock the very thread the callback needs.
     *
     * Returns the count of edits STILL pending after the drain ("0" = clean),
     * "no-hook" when the deployed web app predates the hook, "error" when the
     * drain threw, or null when there is no booted document or the deadline
     * passed. Every non-"0" outcome is the caller's cue to warn — this is
     * deliberately best-effort, matching how other note apps treat sign-out
     * with unsynced changes.
     */
    suspend fun flushWebPending(timeoutMs: Long): String? {
        val w = web ?: return null
        if (loadedOrigin == null) return null
        val starter =
            "(function(){var f=window.__pingolinFlushPending;" +
                "if(!f){window.__pgFlushResult='no-hook';return 'no-hook';}" +
                "window.__pgFlushResult=undefined;" +
                "f().then(function(n){window.__pgFlushResult=String(n);})" +
                ".catch(function(){window.__pgFlushResult='error';});" +
                "return 'started';})()"
        evalOnMain(w, starter) ?: return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val r = evalOnMain(w, "String(window.__pgFlushResult)")
            if (r != null && r != "null" && r != "\"undefined\"") {
                return r.trim('"')
            }
            delay(200)
        }
        return null
    }

    /**
     * The page's last keep of its kept trust before a wipe deletes its storage: one bounded
     * chance to hand the app this account's pins, floors and owners as they are now. Keeps follow
     * every change, so this only saves one the page hadn't sent yet. MUST be called on the main
     * thread; suspends rather than blocks, polled like [flushWebPending].
     *
     * Returns "true" when the app kept them, "false" when it didn't, "no-hook" when the deployed
     * page predates the hook, or null when there is no booted document or the deadline passed.
     */
    suspend fun keepWebTrustNow(timeoutMs: Long): String? {
        val w = web ?: return null
        if (loadedOrigin == null) return null
        val starter =
            "(function(){var f=window.__pingolinKeepTrustNow;" +
                "if(!f){window.__pgKeepResult='no-hook';return 'no-hook';}" +
                "window.__pgKeepResult=undefined;" +
                "f().then(function(k){window.__pgKeepResult=String(k);})" +
                ".catch(function(){window.__pgKeepResult='false';});" +
                "return 'started';})()"
        // Bounded as a whole: a renderer that never calls back can't hold the wipe.
        return withTimeoutOrNull(timeoutMs) {
            if (evalOnMain(w, starter) == null) return@withTimeoutOrNull null
            var result: String? = null
            while (result == null) {
                val r = evalOnMain(w, "String(window.__pgKeepResult)")
                if (r != null && r != "null" && r != "\"undefined\"") result = r.trim('"') else delay(100)
            }
            result
        }
    }

    private suspend fun evalOnMain(w: WebView, js: String): String? =
        suspendCancellableCoroutine { cont ->
            w.evaluateJavascript(js) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }

    /**
     * Detach from the finishing Activity and point the context wrapper back at
     * the application, so nothing of that Activity survives. The WebView itself
     * stays alive with its document intact — that is the whole point.
     */
    fun release(caller: Context, appContext: Context) {
        // Only the current holder may release. A second Activity that took the
        // WebView is now responsible for it, and the first one finishing must
        // not rip it out of the second one's layout. This guard is load-bearing
        // on the ORDINARY close-then-open too: Android runs the new instance's
        // onCreate before the finishing instance's onDestroy, so release()
        // fires with a stale caller on every quick reopen.
        if (host !== caller) return
        host = null
        val w = web ?: return
        (w.parent as? ViewGroup)?.removeView(w)
        // Drop the Activity-capturing clients before parking. They are
        // anonymous objects from the finishing Activity's onCreate and hold it
        // strongly; WebAppActivity re-assigns real ones on every acquire.
        w.webViewClient = idleClient
        w.webChromeClient = null
        wrapper?.baseContext = appContext.applicationContext
    }

    /**
     * Throw the WebView away because its renderer died or its session ended.
     *
     * Unlike [destroy] this never navigates the WebView first: after a
     * renderer kill any loadUrl on it is undefined behaviour, and the whole
     * point is that there is no document left worth clearing.
     */
    fun discard() {
        val w = web
        web = null
        wrapper = null
        loadedOrigin = null
        host = null
        markerInstalled = false
        keysInstalled = false
        saveChannelInstalled = false
        nudgeChannelInstalled = false
        trustChannelInstalled = false
        if (w != null) {
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
    }

    /**
     * [discard], but only when no Activity currently hosts the WebView — the
     * memory-pressure path. TRIM_MEMORY_BACKGROUND arrives once the process is
     * on the LRU list, so nothing is on screen and nobody sees a blank frame;
     * the next note open pays one full boot instead of the fast route, which
     * is the correct trade against the process being killed outright.
     */
    fun discardIfUnheld() {
        if (host == null) discard()
    }

    /**
     * Tear it down for real. Call on sign-out, user switch, or any other point
     * where the resident document must not outlive the session that made it.
     */
    fun destroy() {
        val w = web
        web = null
        wrapper = null
        loadedOrigin = null
        host = null
        markerInstalled = false
        keysInstalled = false
        saveChannelInstalled = false
        nudgeChannelInstalled = false
        trustChannelInstalled = false
        if (w != null) {
            (w.parent as? ViewGroup)?.removeView(w)
            // The HTTP cache (encrypted attachment blobs, `private,
            // max-age=3600`) is the app's, not the page's:
            // WebStorage.deleteAllData never reaches it. Its files
            // live under cacheDir, which the wipe then deletes.
            runCatching { w.clearCache(true) }
            w.stopLoading()
            // No about:blank dance here: loadUrl is asynchronous, so a
            // navigation queued on the same loop iteration as destroy() never
            // commits, and clearHistory() is only meaningful after a load
            // finishes. destroy() itself is what actually tears the document
            // and the back-forward list down.
            w.destroy()
        }
    }
}
