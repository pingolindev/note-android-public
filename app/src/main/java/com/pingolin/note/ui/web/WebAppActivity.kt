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

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.pingolin.note.BuildConfig
import com.pingolin.note.R
import com.pingolin.note.util.CompromisedKeyTrustProvider
import com.pingolin.note.util.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONObject
import uniffi.pingolin_mobile.CryptoManager
import javax.inject.Inject

/**
 * Hosts the Note web editor in a WebView.
 *
 * Why: CodeMirror 6 virtualises — measured flat at ~16ms/keystroke from 300 to
 * 20,000 lines on a Galaxy A50 — so the editor stays responsive on large notes.
 *
 * ## The session bridge
 *
 * Native talks to the API with `Authorization: Bearer`; the web app uses
 * cookies. They are the SAME credentials — the auth server puts the very JWT
 * this app already holds into the `auth_token` cookie, and `/auth/refresh`
 * accepts the refresh token from either the `X-Refresh-Token` header (mobile)
 * or the `refresh_token` cookie (web). So the bridge is just: copy what
 * [SessionManager] already has into the WebView's cookie jar. No second login,
 * no backend change.
 *
 * `COOKIE_DOMAIN` is `.pingolindev.com` / `.pingolin.com`, so one cookie also
 * authenticates `account.` — which is what lets the E2EE client-key handoff
 * (`#ck=`) complete without the user logging in again.
 */
@AndroidEntryPoint
class WebAppActivity : AppCompatActivity() {

    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var pushRegistrar: com.pingolin.note.util.PushRegistrar
    /**
     * LAZY ON PURPOSE — these five are the launch stall.
     *
     * Hilt injects fields eagerly, on the first statement of onCreate
     * (`super.onCreate` dispatches the OnContextAvailableListener), so every
     * one of these would be CONSTRUCTED before this activity's own body runs.
     * Between them they drag in five `EncryptedSharedPreferences` opens —
     * `logoutOrchestrator` alone reaches SyncRepository, LocalDataWiper,
     * NoteDatabase, the retired-content-key store and the pending-push store —
     * and each open is two keyset unwraps through the Keystore. Measured eager
     * on an A50: 42 TEE `begin req` ops across the launch, ~223ms of them on
     * the main thread here, before a single line of onCreate ran.
     *
     * Every one has exactly ONE use site, none of them on the first frame:
     * logout is a dialog button, the rest are background warm-up. `Lazy.get()`
     * defers the construction — and therefore the Keystore work — to that site.
     * Keep them lazy; a `.get()` on the main thread here silently brings the
     * stall back.
     */
    @Inject lateinit var logoutOrchestrator: dagger.Lazy<com.pingolin.note.domain.repository.LogoutOrchestrator>
    @Inject lateinit var quotaManager: dagger.Lazy<com.pingolin.note.domain.repository.QuotaManager>
    @Inject lateinit var senderKeyPinStore: dagger.Lazy<com.pingolin.note.util.SenderKeyPinStore>
    @Inject lateinit var noteGenFloorStore: dagger.Lazy<com.pingolin.note.util.NoteGenFloorStore>
    @Inject lateinit var keptWebTrustStore: com.pingolin.note.util.KeptWebTrustStore
    @Inject lateinit var notificationPrefsSync: dagger.Lazy<com.pingolin.note.domain.repository.NotificationPrefsSync>

    @Inject lateinit var cryptoManager: CryptoManager
    @Inject lateinit var identityRecheck: dagger.Lazy<com.pingolin.note.domain.repository.IdentityRecheck>

    private lateinit var web: WebView

    private val shellServer: WebAppShellServer by lazy { shellServerFor(this) }

    /** elapsedRealtime when a full document load began; 0 when none is in flight. */
    private var loadStartedAt = 0L

    /** elapsedRealtime at onCreate, so the in-document route can be timed from the tap. */
    private var createdAt = 0L

    /**
     * The web page's <input type=file> callback, held while the system picker
     * is up. MUST always be answered — with null on cancel — or the WebView
     * never delivers another file chooser to this document: attachment add
     * and import would silently stop working, with no error.
     */
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val cb = pendingFileChooser
        pendingFileChooser = null
        if (cb == null) return@registerForActivityResult
        val data = result.data
        val clip = data?.clipData
        val uris: Array<Uri>? = when {
            result.resultCode != RESULT_OK || data == null -> null
            clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        cb.onReceiveValue(uris)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAt = SystemClock.elapsedRealtime()

        // Screenshot protection — secure by default, and this is the Activity
        // that actually shows the note BODY: without the flag the note content
        // would be capturable by screenshot and live in the recents thumbnail.
        //
        // Every other note-bearing screen sets it too (MainActivity,
        // AuthActivity, LockScreenCaptureActivity, SingleNoteConfigActivity).
        if (sessionManager.isBlockScreenshotsEnabled()) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

        // Never open the notes without this device's keys. The page
        // cannot read a single note without the vault key, nothing
        // shared with this account opens without the sharing key, and
        // a keyless page could mint a throwaway vault key and re-key
        // notes under it. A session like that comes from a sign-in
        // whose key bootstrap failed, so the one remedy is to sign in
        // again — which derives the keys. Dropping the tokens first
        // stops the router sending the next launch straight back here.
        if (sessionManager.getMasterKey().isNullOrEmpty() || sessionManager.getSharingPrivateKey().isNullOrEmpty()) {
            Log.w(TAG, "signed in without the vault or sharing key; asking for a sign-in instead of opening keyless")
            sessionManager.clearAuthOnly()
            startActivity(
                Intent(this, com.pingolin.note.ui.auth.AuthActivity::class.java)
                    .putExtra(
                        com.pingolin.note.ui.auth.AuthActivity.EXTRA_REAUTH_REASON,
                        com.pingolin.note.ui.auth.AuthActivity.REAUTH_KEYS,
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            finish()
            return
        }

        // Seed BEFORE the WebView is created and loads anything, or the first
        // /api/v1/auth/me goes out unauthenticated and the app bounces to the
        // login portal.
        seedSessionCookies()

        // Before any load: a service worker left on our origin must hit the
        // bundled copy too, not the network (see WebAppShellServer).
        WebAppShellServer.hookServiceWorkers(shellServer)

        // Borrowed, not built. See RetainedWebView for the measurements — a
        // WebView per tap costs 3,335ms to usable content and a second open of
        // the same note 3,201ms, because destroying the WebView throws away the
        // parsed JS and the instantiated WASM along with the document.
        //
        // `configure` runs only on first creation. Clients are set below on
        // EVERY acquire, because they capture this Activity.
        web = RetainedWebView.obtain(this) { w ->
            w.settings.javaScriptEnabled = true
            w.settings.domStorageEnabled = true
            // The vault key is cached in sessionStorage/localStorage per origin;
            // without DOM storage every launch would re-federate from the portal.

            // Announce the shell in the User-Agent as well as through
            // the document-start marker, because the marker is not
            // always available: `installNativeShellMarker` returns
            // false when DOCUMENT_START_SCRIPT is unsupported, and on
            // those devices the web app could not tell it is inside
            // the app at all. It would then fall back to its browser
            // CTA — an external web-checkout link for digital goods,
            // rendered inside the app, which is the exact steering
            // Play prohibits.
            //
            // The UA needs no feature support and is set before the
            // first load, so it holds on precisely the devices the
            // marker cannot reach.
            w.settings.userAgentString = "${w.settings.userAgentString} $SHELL_UA_TOKEN"
            // A WebView's default background is WHITE. Between the WebView being
            // attached and the page painting its own background there is nothing
            // else on screen, so on the dark theme that gap is a full-screen
            // white flash waiting for a slow first paint. The launch window's
            // own background hides that gap, but a timing change could surface
            // it. @color/surface is the same value the page paints (#1a1a2e
            // night, #fafafa day), so the seam is invisible either way.
            w.setBackgroundColor(ContextCompat.getColor(w.context, R.color.surface))
        }
        // OUTSIDE the configure block on purpose: that block runs once, on the
        // FIRST creation of the retained WebView, and the whole point of this
        // is to track a setting the user can change while the app is installed.
        applyTextZoom()
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    // WARN + unconditional: R8 strips
                    // Log.d/i in release, and a page
                    // exception (a failed fetch, an
                    // unhandled rejection) would otherwise
                    // be completely invisible from logcat —
                    // a dead in-page button diagnoses as
                    // pure silence.
                    Log.w(TAG, "web console ERROR: ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                } else if (BuildConfig.DEBUG) {
                    Log.d(TAG, "web: ${m.message()}")
                }
                return true
            }

            override fun onShowFileChooser(
                v: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                // A second chooser before the first resolves: answer the OLD
                // callback (null) rather than dropping it — an unanswered
                // callback wedges every future file input in the document.
                pendingFileChooser?.onReceiveValue(null)
                pendingFileChooser = filePathCallback
                val intent = runCatching { fileChooserParams.createIntent() }.getOrNull()
                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    // No document picker on this device. Contract: having
                    // taken the callback, answer it — null — and still return
                    // true so the WebView does not double-handle.
                    Log.w(TAG, "no activity to pick files", e)
                    pendingFileChooser = null
                    filePathCallback.onReceiveValue(null)
                    true
                }
            }
        }
        // A WebViewClient is REQUIRED, not optional: the client-key handoff is a
        // cross-origin navigation (note -> account portal -> back with `#ck=`),
        // and the default WebView hands that to an external browser instead of
        // following it in place — which strands the app on "Redirecting...".
        web.webViewClient = object : WebViewClient() {
            /**
             * `pingolin://close` — the web app asking to be dismissed.
             *
             * The web app cannot finish an Activity, so it says so with a
             * navigation and we intercept it.
             *
             * A custom scheme rather than addJavascriptInterface: that exposes
             * a Java object to every frame and origin the WebView ever loads,
             * including the account portal we hand off to. This exposes one
             * verb, and only to a navigation we recognise.
             */
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                if (req.url.scheme == CLOSE_SCHEME) {
                    // Home mode: the page navigates itself to the grid before
                    // firing the close scheme, so the close is already done —
                    // finishing here would drop out of the app entirely.
                    if (!isTaskRoot) finish()
                    return true
                }
                if (req.url.scheme == SIGNOUT_SCHEME) {
                    // The page's "Sign out" in the shell.
                    // Confirm with the SAME warning native
                    // Settings shows, then run the SAME
                    // orchestration (flush -> push teardown
                    // -> revoke -> wipe, on an
                    // application-scoped coroutine) —
                    // without this scheme the page's
                    // sign-out would strand the shell on
                    // the in-WebView account portal with
                    // the native session still alive.
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@WebAppActivity)
                        .setTitle("Logout")
                        .setMessage(
                            "Log out? Any unsynced changes will be uploaded first. " +
                                "If you're offline, unsynced notes may be lost.",
                        )
                        .setPositiveButton("Logout") { _, _ ->
                            logoutOrchestrator.get().logout()
                            startActivity(
                                Intent(this@WebAppActivity, com.pingolin.note.ui.auth.AuthActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                            )
                            finish()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return true
                }
                if (req.url.scheme == UPGRADE_SCHEME) {
                    com.pingolin.note.ui.dialogs.PremiumBottomSheet.show(supportFragmentManager)
                    return true
                }
                if (req.url.scheme == SETTINGS_SCHEME) {
                    val section = req.url.getQueryParameter("section")
                    // A named device group opens as a SHEET over this page —
                    // the notes stay behind it and it is dismissed, not
                    // navigated away from. Starting another Activity for one
                    // toggle read as leaving the app for a second menu that
                    // also called itself Settings.
                    if (com.pingolin.note.ui.dialogs.DeviceSettingsSheet.handles(section)) {
                        com.pingolin.note.ui.dialogs.DeviceSettingsSheet.show(
                            supportFragmentManager,
                            section!!,
                        )
                        return true
                    }
                    // The page's Settings panel asked for the NATIVE settings
                    // (app lock, block screenshots, push mode — none of which
                    // can live inside the page).
                    //
                    // `?section=` targets one section, so the panel's Device
                    // rows land where the person tapped instead of at the top
                    // of a long screen. A HINT only: an unknown or absent
                    // value opens the screen normally, and the scheme still
                    // cannot do anything but OPEN — every write stays behind
                    // the native prompts.
                    startActivity(
                        Intent(this@WebAppActivity, com.pingolin.note.ui.MainActivity::class.java)
                            .putExtra(com.pingolin.note.ui.MainActivity.EXTRA_OPEN_SETTINGS, true)
                            .putExtra(
                                com.pingolin.note.ui.MainActivity.EXTRA_SETTINGS_SECTION,
                                section,
                            ),
                    )
                    return true
                }
                return false
            }

            /**
             * The web app's own code comes from the APK — online or not — and
             * only API traffic reaches the network. The page is handed this
             * device's E2EE keys at document start, so it must be the code this
             * binary shipped with, not whatever the server returns at the time
             * (see [WebAppAssets] for the policy and its cost). Fetching the
             * deployed build online would hand the keys to code no store review
             * had seen.
             */
            override fun shouldInterceptRequest(
                v: WebView,
                req: WebResourceRequest,
            ): WebResourceResponse? {
                shellServer.intercept(req)?.let { return it }
                // What is left is network-bound: our API, and other origins.
                // The page's /auth/me — the one request the notes list cannot
                // render without — may already be in hand: NoteApplication
                // starts it alongside the launch, and this is where it lands.
                // The URL match inside take() is exact, so no other request can
                // be answered from here.
                if (req.method.equals("GET", ignoreCase = true) && !req.isForMainFrame) {
                    AuthMePrefetch.take(
                        req.url.toString(),
                        sessionManager.getAccessToken(),
                    )?.let { return it }
                }
                return null
            }

            /**
             * A document is now live on our origin, so the next open can route
             * inside it instead of loading again.
             *
             * Marked HERE and not next to loadUrl: a load that failed (no
             * network, a 502 from the ingress) would otherwise be recorded as
             * booted, and the next open would call a navigate hook that does not
             * exist on an error page and silently do nothing.
             *
             * The timing line is the one number to watch for this path.
             */
            override fun onPageFinished(v: WebView, url: String) {
                super.onPageFinished(v, url)
                val origin = originOf(BuildConfig.NOTE_API_BASE_URL)
                // Both directions matter. An off-origin finish — the auth
                // portal after an expiry bounce, an external link — replaces
                // our document, and isBootedOn must stop saying otherwise or
                // the next open probes a page with no navigate hook. (That
                // probe does self-heal via the no-hook fallback, but only
                // after a wasted round trip and a WARN that blames the web
                // app for something the shell did.)
                if (url.startsWith(origin)) {
                    RetainedWebView.markLoaded(origin)
                    kickOffFirstSync()
                    // Injection probe (WARN — R8 strips INFO): "object" means
                    // addWebMessageListener injected pingolinSave into THIS
                    // document; "undefined" means the in-app save silently
                    // falls back to the inert anchor path — exactly the state
                    // this line exists to make visible from logcat.
                    v.evaluateJavascript("typeof window.pingolinSave") {
                        Log.w(TAG, "pingolinSave in document: $it")
                    }
                } else {
                    RetainedWebView.markUnloaded()
                }
                val started = loadStartedAt
                if (started != 0L) {
                    // WARN for the same reason as above: INFO does not survive R8.
                    Log.w(TAG, "full document load: ${SystemClock.elapsedRealtime() - started}ms")
                    loadStartedAt = 0L
                }
            }

            /**
             * The renderer process died. Without this override the framework's
             * default is to KILL THE APP PROCESS — and the retained WebView
             * holds a renderer for the life of the process, so an OS kill of
             * the sandboxed renderer while the user is elsewhere in the app
             * would take the whole app down under them. Worse, the stale
             * holder state would otherwise claim the document is still booted:
             * evaluateJavascript on a dead renderer never invokes its
             * callback, so the fallback inside that callback can never fire.
             *
             * discard() rather than destroy(): destroy()'s teardown assumes a
             * live renderer; after a kill the only safe operations are detach
             * and destroy. recreate() re-enters onCreate, which re-obtains a
             * fresh WebView and re-navigates from the current intent.
             */
            override fun onRenderProcessGone(
                v: WebView,
                detail: android.webkit.RenderProcessGoneDetail,
            ): Boolean {
                Log.w(TAG, "renderer gone (crashed=${detail.didCrash()}); rebuilding the shell")
                RetainedWebView.discard()
                if (!isFinishing) recreate()
                return true
            }

            override fun onReceivedError(
                v: WebView,
                req: WebResourceRequest,
                err: WebResourceError,
            ) {
                if (req.isForMainFrame) {
                    Log.w(TAG, "load failed: ${req.url} -> ${err.description}")
                    // A failed main-frame load means whatever is showing is not
                    // our booted document; the next open must not route into it.
                    RetainedWebView.markUnloaded()
                }
            }
        }

        // Once per WebView, not once per Activity — these register
        // document-start scripts ON the WebView, which outlives every
        // Activity; from each onCreate they would stack a duplicate
        // per note ever opened. Latched separately, and only on
        // success: the first open can run before key bootstrap
        // finishes, and latching that failure would leave every later
        // open keyless — the web app would ask for the password until
        // the process restarted.
        // Push registration + the POST_NOTIFICATIONS ask live here,
        // not in MainActivity: that is a router and often finishes
        // within a frame — too short-lived to host a permission
        // dialog, and its lifecycleScope would die under the token
        // POST.
        maybeRequestNotificationPermission()

        // The launch-time NATIVE sync triggers live here: the page
        // syncs itself, but WIDGETS, the reminder reconciler and the
        // pinned-note notification read Room, and without this the
        // Room mirror would only refresh on the 15-min periodic. Store
        // warm-up first — the TOFU pin + rollback-floor stores must be
        // on this account's namespace before the first sync reads
        // them. Off Main: the first WorkManager.getInstance in the
        // process runs the whole on-demand init inline on the calling
        // thread.
        //
        // The store warm-up runs INSIDE the background launch below
        // with the rest of it. Each of these is an
        // EncryptedSharedPreferences open, and that is two keyset
        // unwraps through the Keystore apiece — measured as 6 TEE
        // `begin req` ops and ~45ms each on an A50. Three of them on
        // the main thread would make up most of a 223ms stall between
        // MainActivity's first frame and this activity's own setup.
        // They are only read by the sync that the same block enqueues,
        // so nothing needs them sooner.
        val uid = sessionManager.getUserId()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            uid?.let {
                senderKeyPinStore.get().switchUser(it)
                noteGenFloorStore.get().switchUser(it)
            }
            // Only the PERIODIC one here; the immediate sync runs from
            // onPageFinished — see `kickOffFirstSync`.
            com.pingolin.note.worker.SyncWorker.enqueuePeriodicSync(applicationContext)
            // .get() is the construction, and QuotaManager pulls a store of its
            // own; refresh() is off-Main internally, but the injection that
            // builds it is not.
            quotaManager.get().refresh()
        }

        RetainedWebView.onceMarker { installNativeShellMarker() }
        RetainedWebView.onceKeys { installKeyHandoff() }
        // The save channel captures the APPLICATION context only — the
        // listener lives on the WebView, which outlives this Activity.
        RetainedWebView.onceSaveChannel {
            WebAppSaveChannel(applicationContext)
                .register(web, originOf(BuildConfig.NOTE_API_BASE_URL))
        }
        // The page's kept trust: its pins, floors and owners, which
        // the sign-out wipe deletes with the rest of its storage,
        // kept by the app and handed back at the same account's next
        // sign-in.
        RetainedWebView.onceTrustChannel {
            KeptWebTrustChannel.install(web, originOf(BuildConfig.NOTE_API_BASE_URL), keptWebTrustStore) {
                sessionManager.getUserId()
            }
        }
        // Page -> shell sync nudge. Every edit happens in the page, which
        // writes the SERVER — the native Room mirror (widgets, the reminder
        // reconciler, the pinned-note notification) would only catch up on
        // the 15-minute periodic. The page posts one message after a
        // successful mutation; the shell folds it into an immediate sync
        // (unique+KEEP, so bursts collapse), rate-limited here because the
        // listener outlives every Activity and the page saves per debounce
        // window, not per session.
        RetainedWebView.onceNudgeChannel {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                return@onceNudgeChannel false
            }
            val appContext = applicationContext
            WebViewCompat.addWebMessageListener(
                web, "pingolinNudge", setOf(originOf(BuildConfig.NOTE_API_BASE_URL)),
            ) { _, _, _, _, _ ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastNudgeAt > NUDGE_MIN_INTERVAL_MS) {
                    lastNudgeAt = now
                    com.pingolin.note.worker.SyncWorker.enqueueImmediateSync(appContext)
                }
            }
            true
        }

        // Keep the web app out from under the status bar and the gesture
        // nav.
        //
        // From targetSdk 35 the system draws edge to edge and stops
        // insetting content, and a WebView knows nothing about window
        // insets — so the web app's own header renders underneath the
        // clock and the status icons. Insetting is what the page cannot
        // do for itself: it has no way to learn the bar heights from
        // inside.
        //
        // ⚠️ The inset is applied to a CONTAINER, not to the WebView.
        // Padding the WebView directly can change nothing: the web
        // header stays under the status bar, even after a rotation that
        // forces a fresh inset dispatch. Padding a parent moves the
        // WebView by layout instead, which does not depend on the
        // WebView honouring its own padding.
        //
        // ⚠️ This is INVISIBLE below SDK 35: there the system still
        // insets content itself, so the web app looks correct with no
        // listener at all. Verify edge-to-edge changes on an SDK 35+
        // device.
        val root = FrameLayout(this).apply {
            addView(
                web,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            Log.i(TAG, "insets: top=${bars.top} bottom=${bars.bottom} l=${bars.left} r=${bars.right}")
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // The listener is registered after the view is attached, so ask for a
        // dispatch rather than waiting for one that may already have happened.
        ViewCompat.requestApplyInsets(root)

        // Back should walk the web app's history first; only leave when there is
        // nothing left to go back to.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Web history is only for the CROSS-ORIGIN excursions — the
                // account-portal key handoff is the one this exists for. On
                // our own origin, in-document routing pushes one history entry
                // per note ever opened in this process (the retained WebView's
                // back-forward list is never cleared while it lives), so a
                // plain canGoBack() walk would show the previous NOTE instead
                // of the list.
                val origin = originOf(BuildConfig.NOTE_API_BASE_URL)
                val ours = web.url?.startsWith(origin) == true
                if (!ours && web.canGoBack()) {
                    web.goBack()
                    return
                }
                if (ours) {
                    // The PAGE decides. A URL check cannot: card taps open the
                    // note modal state-locally and location stays "/", so the
                    // path looks like home even with a note open.
                    // __pingolinBack closes whatever is open (modal, panels, a
                    // non-home route) and returns true, or returns false when
                    // there is nothing left — only then does the shell leave. A
                    // page without the hook evaluates to false, so back leaves
                    // the shell.
                    web.evaluateJavascript(
                        "window.__pingolinBack ? window.__pingolinBack() : false",
                    ) { result ->
                        if (result != "true" && !isFinishing) finish()
                    }
                    return
                }
                finish()
            }
        })

        if (savedInstanceState == null) navigateTo(urlFor(intent))

        // Hand each "identities" nudge to the page, which opens its
        // own earlier identities (this shell has no bridge op for
        // them) under its own limits. Only while visible: a page that
        // comes back re-checks on its own visibility change.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                identityRecheck.get().pageEvents.collect {
                    web.evaluateJavascript(
                        com.pingolin.note.domain.repository.IdentityRecheck.pageEventScript(
                            originOf(BuildConfig.NOTE_API_BASE_URL),
                        ),
                        null,
                    )
                }
            }
        }
    }

    /**
     * Re-seed on every resume: the native side refreshes tokens on its own
     * schedule, and a stale `auth_token` here would cost the web app a
     * needless refresh round trip (or a bounce to login if the refresh cookie
     * had also aged out).
     */
    override fun onResume() {
        super.onResume()
        // Belt and braces. A fontScale change is not in this Activity's
        // configChanges, so today it recreates and onCreate covers it — but that
        // list is one edit away from swallowing it, and a resume is also the
        // moment the user comes back from Android's own display settings.
        applyTextZoom()
        seedSessionCookies()
        refreshKeyHandoffInPlace()
        refreshDeviceStateInPlace()
        // Mirror the server's notification row into the local gates the
        // push receivers and the reminder alarm read. The categories
        // live in the page, so without this a category turned off on
        // the web (or on another device) would never reach this phone's
        // gates — and a local reminder alarm never asks the server at
        // all.
        // Dispatchers.Default, not the default Main: `.get()` is where
        // the NotificationPrefsSync graph — and its
        // EncryptedSharedPreferences open — is actually built, and this
        // runs on every resume.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            notificationPrefsSync.get().refresh()
        }
    }

    /**
     * Re-push after a device-settings SHEET closes.
     *
     * The sheet lives in this Activity, so dismissing it fires no onResume,
     * which is where the snapshot is otherwise refreshed. Without this the
     * panel's Device rows keep the values they had before the person changed
     * one, two inches above the control they just used.
     */
    fun refreshDeviceSettingsSnapshot() = refreshDeviceStateInPlace()

    /**
     * Re-push the device snapshot into the LIVE document.
     *
     * Coming back from the native settings screen IS a resume, and it is the
     * one moment the values are guaranteed stale: the person went there to
     * change them. The document-start copy only runs on a page load, so
     * without this the Device rows keep showing what the settings were
     * before the trip. The script fires `pingolin-device-changed` so an open
     * panel re-reads rather than waiting for a reopen.
     */
    private fun refreshDeviceStateInPlace() {
        if (!::sessionManager.isInitialized) return
        val script = WebAppDeviceState.buildScript(
            WebAppDeviceState.from(sessionManager),
            originOf(BuildConfig.NOTE_API_BASE_URL),
            onLoad = false,
        )
        web.evaluateJavascript(script, null)
    }

    /**
     * Re-run the key handoff against the LIVE document.
     *
     * The document-start injection only executes on a page load, so a
     * retained document that survives a native re-login keeps the PREVIOUS
     * session's keys in sessionStorage — and keeps SIGNING with them: every
     * share is then refused as unverifiable ("This browser can't sign the
     * share"), across app restarts, unless each resume re-seeds the key
     * material as well as the cookies.
     *
     * The script is the same idempotent sessionStorage write the
     * document-start path uses (single-origin guard included), followed by
     * the page's `__pingolinKeysUpdated` hook so the running app reloads its
     * in-memory keys too. Missing keys skip silently — the next resume
     * retries, same posture as [installKeyHandoff].
     */
    private fun refreshKeyHandoffInPlace() {
        val script = WebAppKeyHandoff.buildScript(collectKeys(), CompromisedKeyTrustProvider.isDefault()) ?: return
        web.evaluateJavascript(
            script + "window.__pingolinKeysUpdated && window.__pingolinKeysUpdated();",
            null,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // restoreState() restores the back-forward list and RELOADS the
        // current entry. Against a retained WebView that already holds the
        // live document — every configuration change the manifest does not
        // swallow lands here: dark-mode flips, font-size changes, split
        // screen — that reload throws away exactly what retention keeps,
        // including in-editor state. Restore only when the document is
        // actually gone; otherwise just make sure we are showing what the
        // intent asked for.
        if (!RetainedWebView.isBootedOn(originOf(BuildConfig.NOTE_API_BASE_URL))) {
            web.restoreState(savedInstanceState)
        } else {
            navigateTo(urlFor(intent))
        }
    }

    /**
     * A push tap that arrives while this activity is already open lands here
     * rather than in onCreate. Without navigating, tapping a reminder would do
     * nothing visible whenever the web editor happened to be foregrounded.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_URL) || intent.hasExtra(EXTRA_NOTE_ID)) navigateTo(urlFor(intent))
    }

    /**
     * Make the page follow the system font size.
     *
     * A WebView does NOT do this on its own: `WebSettings.textZoom` defaults to
     * 100 and stays there whatever the user sets in Android's display settings,
     * so the page renders identically at every system font scale while native
     * views grow with it.
     *
     * That matters more here than in most shells, because on Android the WebView
     * IS the app — list, cards, editor and settings panel. A low-vision user who
     * turns text size up would get a larger everything except the one app they
     * are trying to read, and there is no in-app text-size control to fall back
     * on.
     *
     * Clamped, but generously: 85 is Android's own smallest step, and 200 covers
     * the non-linear accessibility sizes on recent releases. Capping lower would
     * be quietly overriding an accessibility preference — the same failure in
     * the other direction.
     */
    private fun applyTextZoom() {
        val zoom = (resources.configuration.fontScale * 100f).toInt().coerceIn(85, 200)
        if (web.settings.textZoom != zoom) web.settings.textZoom = zoom
    }

    /**
     * Go to [url], re-using the retained document when we can.
     *
     * `loadUrl` tears the document down and rebuilds it: fetch, parse ~1MB of
     * JS, instantiate the WASM, re-auth, then fetch the note. Measured at
     * ~2,870ms on an A50 AFTER the shell's first frame, and it does not get
     * cheaper on repeat opens because none of that work survives.
     *
     * When the retained WebView already holds a booted document on our own
     * origin, we hand the path to the web app's router instead and it becomes a
     * client-side route change — the note fetch and decrypt, and nothing else.
     *
     * FALLS BACK on purpose: `__pingolinNavigate` is defined by the web app, so
     * a page without it returns undefined here. That is a normal outcome and not
     * an error — we just pay the full-load cost.
     */
    private fun navigateTo(url: String) {
        val origin = originOf(BuildConfig.NOTE_API_BASE_URL)
        // Fragment-bearing URLs take the FULL LOAD on purpose. They
        // are the create paths — newNoteUrl puts the shared title and
        // body in the fragment — and the web app reads that fragment
        // from window.location at component-setup time, which on an
        // in-document route still shows the PREVIOUS URL (the router
        // writes history after the transition renders). Handing the
        // hook a fragment URL would therefore open an empty composer
        // and leave the user's shared text sitting in the WebView's
        // back-forward list — which web.saveState() then parcels to
        // system_server. A full load runs the setup path that reads
        // and immediately strips the fragment. Note opens carry no
        // fragment and keep the fast path.
        if (!RetainedWebView.isBootedOn(origin) || !url.startsWith(origin) || url.contains('#')) {
            loadStartedAt = SystemClock.elapsedRealtime()
            web.loadUrl(url)
            return
        }
        val path = url.removePrefix(origin).ifEmpty { "/" }
        // JSON-encode so a note id can never break out of the string literal.
        val js = "(function(){var n=window.__pingolinNavigate;" +
            "if(!n){return 'no-hook';}n(${JSONObject.quote(path)});return 'ok';})()"
        web.evaluateJavascript(js) { result ->
            if (result == null || result.contains("no-hook")) {
                // WARN: this is the line that tells you the fast path is NOT being
                // taken, which is the whole question when this looks slow. INFO
                // would be stripped from release and the fallback would be silent.
                Log.w(TAG, "web app has no navigate hook; falling back to a full load")
                loadStartedAt = SystemClock.elapsedRealtime()
                web.loadUrl(url)
            } else {
                // WARN, not INFO. proguard-rules.pro strips v/d/i from release via
                // -assumenosideeffects, so an INFO line here prints NOTHING on the
                // build users actually run -- and this number only matters on that
                // build. A silent log reads exactly like the code never running.
                Log.w(
                    TAG,
                    "routed in-document in " +
                        "${SystemClock.elapsedRealtime() - createdAt}ms since onCreate",
                )
            }
        }
    }

    override fun onDestroy() {
        // Hand the WebView back instead of destroying it — this is what makes
        // the next note open a route change rather than a cold boot. release()
        // detaches it and points its context wrapper back at the application, so
        // this Activity is not retained. Sign-out calls RetainedWebView.destroy()
        // from the wipe; see LocalDataWiper.
        // The retained WebView outlives this Activity: a file callback left
        // unanswered here would wedge the DOCUMENT's file inputs for the rest
        // of the process, not just this screen.
        pendingFileChooser?.onReceiveValue(null)
        pendingFileChooser = null
        RetainedWebView.release(this, applicationContext)
        super.onDestroy()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Granted or not, register — the service only posts notifications
        // when the permission is there.
        pushRegistrar.ensure()
    }

    private fun maybeRequestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted) {
                pushRegistrar.ensure()
            } else {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            pushRegistrar.ensure()
        }
    }

    /**
     * Tell the page it is running inside the app.
     *
     * Separate from [installKeyHandoff] on purpose: that one returns early
     * whenever the key material is missing, and whether the shell can be closed
     * has nothing to do with whether we could hand over a vault key. Folding
     * this into it would make "close returns to the list" fail exactly on the
     * devices where the password prompt appears.
     *
     * @return true when the script is registered — the caller latches on that.
     */
    private fun installNativeShellMarker(): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // The web app falls back to navigating to the grid.
            // Returning false retries next create, which costs one
            // cheap feature check.
            Log.i(TAG, "document-start scripts unsupported; web app cannot dismiss the shell")
            return false
        }
        val origin = originOf(BuildConfig.NOTE_API_BASE_URL)
        WebViewCompat.addDocumentStartJavaScript(
            web,
            "window.__pingolinNativeShell = '$CLOSE_SCHEME';" +
                "window.__pingolinShellSettings = '$SETTINGS_SCHEME';" +
                "window.__pingolinShellSignOut = '$SIGNOUT_SCHEME';" +
                "window.__pingolinShellUpgrade = '$UPGRADE_SCHEME';" +
                // Device-settings snapshot for the panel's Device section.
                // Read-only (see WebAppDeviceState); the page renders it and
                // deep-links back here to change anything.
                WebAppDeviceState.buildScript(WebAppDeviceState.from(sessionManager), origin, onLoad = true),
            setOf(origin),
        )
        return true
    }

    /**
     * Hand this device's already-unlocked E2EE keys to the web app so it does
     * not ask for the password again.
     *
     * The cookie bridge above authenticates but can never carry the vault key,
     * so without this the first open lands on the account portal's
     * "Enter your password to continue" — a prompt the user has, from their
     * point of view, already answered by being signed in.
     *
     * Injected at **document start**, against a single-origin allowlist. Both
     * matter: later than document start and the app has already booted keyless
     * and navigated away; wider than one origin and the key material would be
     * handed to the account portal (and anything else the WebView loads) too.
     *
     * @return true when the script is registered — the caller latches on that.
     */
    private fun installKeyHandoff(): Boolean {
        // Null when the vault or sharing key is not available yet.
        // Returning false leaves the latch unset, so the NEXT open
        // retries with whatever keys exist by then — latching this
        // failure would leave the retained WebView permanently
        // keyless.
        val script = WebAppKeyHandoff.buildScript(collectKeys(), CompromisedKeyTrustProvider.isDefault()) ?: return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Degrade to the password prompt rather than injecting late and
            // racing the app's own boot — a half-applied key handoff is worse
            // than none, because it lands after the redirect has started.
            Log.i(TAG, "document-start scripts unsupported; web app will ask for the password")
            return false
        }
        WebViewCompat.addDocumentStartJavaScript(web, script, setOf(originOf(BuildConfig.NOTE_API_BASE_URL)))
        return true
    }

    /**
     * Reads the persisted key material and re-derives the two public halves
     * through the SDK rather than reimplementing scalarmult/Ed25519 in Kotlin —
     * duplicated crypto across platforms is how an X25519/Ed25519 seed mismatch
     * arises, which this code has to be careful about.
     *
     * Imports before reading because nothing re-populates the SDK's key slots
     * after a process restart; call sites elsewhere decode from
     * [SessionManager] themselves. Importing what is already persisted is
     * idempotent.
     */
    private fun collectKeys(): WebAppKeyHandoff.Keys? {
        val vaultKey = sessionManager.getMasterKey() ?: return null
        val sharingPriv = sessionManager.getSharingPrivateKey() ?: return null

        val sharingPub = try {
            cryptoManager.importPrivateKey(sharingPriv)
            cryptoManager.exportPublicKey()
        } catch (e: Exception) {
            Log.w(TAG, "could not derive the sharing public key; skipping key handoff")
            return null
        }

        // Signing is optional: accounts that were never provisioned with an
        // Ed25519 seed have none, and the web app publishes nothing rather than
        // signing with the wrong identity.
        var signSecret: String? = null
        var signPublic: String? = null
        val seed = sessionManager.getSigningPrivateKey()
        if (seed != null) {
            try {
                val seedBytes = android.util.Base64.decode(seed, android.util.Base64.NO_WRAP)
                // Nominally a 32-byte seed; a legacy save may hold the full
                // 64-byte secret (seed||pub). Appending pub to THAT would
                // produce a 96-byte key the web refuses to sign with.
                // Normalize: hand over the 64-byte secret either way.
                val seed32B64 = android.util.Base64.encodeToString(
                    seedBytes.copyOfRange(0, 32), android.util.Base64.NO_WRAP)
                cryptoManager.importSigningPrivateKey(seed32B64)
                val pubBytes = hexToBytes(cryptoManager.signingPublicKeyHex())
                // libsodium lays an Ed25519 secret key out as seed || publicKey,
                // which is what the web app's `signingSecretKey` expects.
                signSecret = android.util.Base64.encodeToString(
                    seedBytes.copyOfRange(0, 32) + pubBytes, android.util.Base64.NO_WRAP)
                signPublic = android.util.Base64.encodeToString(pubBytes, android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.w(TAG, "no usable signing key; the web app will not sign shares from this device")
            }
        }

        return WebAppKeyHandoff.Keys(vaultKey, sharingPriv, sharingPub, signSecret, signPublic)
    }

    /**
     * Enqueue the launch-time native sync, once per process, AFTER the page has
     * loaded rather than during onCreate.
     *
     * The sync is the first thing in a launch to open the Room database, and
     * opening it costs SQLCipher's full 256,000-round PBKDF2 — measured ~509ms
     * of CPU per connection on an A50. That is off the main thread, so it
     * blocks nothing directly, but run during the web app's own boot it burns a
     * core on a device that has few to spare. Starting it once the document is
     * up moves that work out of the contention window.
     *
     * Nothing waits on it: the PAGE syncs itself over the network. This exists
     * for the Room mirror that widgets, the reminder reconciler and the
     * pinned-note notification read, none of which is on screen at launch.
     *
     * onPageFinished rather than a timer, and it fires on a FAILED load too, so
     * this cannot be starved by a page that never comes up. Once per process
     * because the retained WebView can load a document more than once (an
     * expiry bounce through the account portal comes back through here) and the
     * mirror does not need re-syncing each time.
     */
    private fun kickOffFirstSync() {
        if (!firstSyncStarted.compareAndSet(false, true)) return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            com.pingolin.note.worker.SyncWorker.enqueueImmediateSync(applicationContext)
        }
    }

    /**
     * App lock for this Activity. onStop records the background time (the
     * timeout modes measure from it) and onStart re-prompts when the lock
     * requires it; without both, backgrounding from an OPEN NOTE and returning
     * would show the note again with no authentication — the one screen the lock
     * most needs to cover.
     *
     * This finishes back to MainActivity rather than raising its own prompt: the
     * prompt already exists there, and dismissing this Activity means the note
     * is not sitting decrypted behind it while the user authenticates.
     */
    override fun onStop() {
        super.onStop()
        if (sessionManager.isAppLockEnabled()) sessionManager.recordBackgroundTime()
    }

    override fun onStart() {
        super.onStart()
        // Back in the foreground with notes still locked → re-check
        // the earlier identities (at most once per window;
        // IdentityRecheck).
        if (!sessionManager.requiresAppLockAuth()) identityRecheck.get().onForeground()
        if (sessionManager.requiresAppLockAuth()) {
            // Finishing hides the note; discarding is what the lock actually
            // promises. The retained document keeps the decrypted body mounted
            // for the life of the process, so a locked app would otherwise
            // still be holding the note text (and its session) behind the
            // prompt. One full boot on the next open is the price of the lock
            // meaning what it says.
            RetainedWebView.discard()
            // This activity is the task root: a bare finish() would drop the
            // user at the LAUNCHER after a screen-off. Route to the prompt
            // instead — MainActivity gates, and its unlock forwards straight
            // back to the web home.
            startActivity(Intent(this, com.pingolin.note.ui.MainActivity::class.java))
            finish()
        }
    }

    private fun seedSessionCookies() {
        val access = sessionManager.getAccessToken()
        val refresh = sessionManager.getRefreshToken()
        if (access.isNullOrBlank()) {
            Log.w(TAG, "no access token to seed — the web app will bounce to login")
            return
        }

        val base = BuildConfig.NOTE_API_BASE_URL
        val domain = cookieDomainFor(base)
        val cm = CookieManager.getInstance()

        // Attributes mirror the cookies the auth server sets, so
        // these REPLACE the server's cookies rather than sitting
        // alongside them. The page only needs the browser cookie jar
        // to attach them to requests; JavaScript must never be able
        // to read either bearer credential.
        cm.setCookie(base, authCookieHeader(access, domain))
        if (!refresh.isNullOrBlank()) {
            cm.setCookie(base, refreshCookieHeader(refresh, domain))
        }
        cm.flush()
    }

    companion object {
        private const val TAG = "WebAppActivity"

        internal fun authCookieHeader(accessToken: String, domain: String): String =
            "auth_token=$accessToken; Domain=$domain; Path=/; Secure; HttpOnly; " +
                "SameSite=Lax; Max-Age=$AUTH_MAX_AGE"

        internal fun refreshCookieHeader(refreshToken: String, domain: String): String =
            "refresh_token=$refreshToken; Domain=$domain; Path=/api/v1/auth/refresh; " +
                "Secure; HttpOnly; SameSite=Lax; Max-Age=$REFRESH_MAX_AGE"

        /** Process-scoped, so an Activity recreation does not re-enqueue the
         *  launch sync. See [kickOffFirstSync]. */
        private val firstSyncStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Marks this WebView as the native shell for the web app.
         * Must match NATIVE_SHELL_UA_TOKEN in the Note web editor. */
        const val SHELL_UA_TOKEN = "PingolinNativeShell"

        /** Scheme the web app navigates to when it wants the shell dismissed. */
        private const val CLOSE_SCHEME = "pingolin-close"

        /** Page -> shell: open the NATIVE settings screen. */
        private const val SETTINGS_SCHEME = "pingolin-settings"

        /** Page -> shell: run the NATIVE sign-out orchestration. */
        private const val SIGNOUT_SCHEME = "pingolin-signout"

        /**
         * The page's Upgrade CTA in shell mode. The web billing page must not
         * be reachable from inside the Play build — Google Play prohibits
         * steering to an external purchase method without Play Billing — so
         * the shell answers with the read-only Premium explainer
         * ([PremiumBottomSheet]; the iOS shell presents its native StoreKit
         * sheet off the same marker).
         */
        private const val UPGRADE_SCHEME = "pingolin-upgrade"

        /** Sync-nudge rate limit; the listener is process-wide (see the
         *  onceNudgeChannel install), so the stamp is too. */
        private const val NUDGE_MIN_INTERVAL_MS = 5_000L

        @Volatile
        private var lastNudgeAt = 0L

        /** Matches the order of magnitude of the auth server's access-token lifetime; a stale one
         * self-heals via /auth/refresh.
         */
        private const val AUTH_MAX_AGE = 24 * 60 * 60

        /** Matches MAX_SESSION_TIMEOUT / the server's refresh cookie. */
        private const val REFRESH_MAX_AGE = 30 * 24 * 60 * 60

        /**
         * `https://note.pingolindev.com/` -> `.pingolindev.com`, matching the
         * deployed `COOKIE_DOMAIN` so the cookie also covers `account.` for the
         * client-key handoff.
         */
        fun cookieDomainFor(baseUrl: String): String {
            // The port must not survive into the Domain attribute — a cookie
            // whose domain carries one is rejected outright, which reads as
            // "the session just didn't work" with nothing logged.
            val host = baseUrl.substringAfter("://").substringBefore('/').substringBefore(':')
            val registrable = host.substringAfter('.')
            return if (registrable.contains('.')) ".$registrable" else host
        }

        /**
         * `https://note.pingolindev.com/` -> `https://note.pingolindev.com`.
         * The allowlist for a document-start script takes ORIGINS, and an
         * entry with a trailing path never matches — which would silently
         * leave the web app keyless.
         */
        fun originOf(baseUrl: String): String {
            val scheme = baseUrl.substringBefore("://")
            val hostAndPort = baseUrl.substringAfter("://").substringBefore('/')
            return "$scheme://$hostAndPort"
        }

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
            }

        /** Intent extra: open this note directly instead of the notes grid. */
        const val EXTRA_NOTE_ID = "web_note_id"

        /**
         * Serves the web app from the APK for the page AND for any service
         * worker on our origin. Process-wide: the retained WebView outlives
         * every Activity, and so does the ServiceWorkerController hook.
         */
        @Volatile
        private var sharedShellServer: WebAppShellServer? = null

        private fun shellServerFor(context: Context): WebAppShellServer =
            sharedShellServer ?: synchronized(this) {
                sharedShellServer ?: WebAppShellServer(
                    context.applicationContext.assets,
                    BuildConfig.NOTE_API_BASE_URL,
                ).also { sharedShellServer = it }
            }

        /**
         * A pre-built URL wins over a note id: the create paths carry the whole
         * `/new#…` URL because their parameters do not fit an id.
         */
        fun urlFor(intent: Intent): String =
            intent.getStringExtra(EXTRA_URL) ?: startUrl(intent.getStringExtra(EXTRA_NOTE_ID))

        /**
         * `/note/:id` in the web app, or the grid when there is no target.
         *
         * The id is a server-side item UUID and carries no plaintext, so it is
         * safe in a URL — the same reasoning the reminder notification already
         * relies on to deep-link without leaking note content.
         */
        fun startUrl(noteId: String?): String {
            val base = BuildConfig.NOTE_API_BASE_URL.trimEnd('/')
            return if (noteId.isNullOrBlank()) "$base/" else "$base/note/$noteId"
        }

        fun intent(context: Context, noteId: String? = null): Intent =
            Intent(context, WebAppActivity::class.java).apply {
                if (!noteId.isNullOrBlank()) putExtra(EXTRA_NOTE_ID, noteId)
            }

        /** Intent extra: a ready-built `/new#…` URL to open instead of the grid. */
        const val EXTRA_URL = "web_url"

        /**
         * `/new` with the composer prefilled.
         *
         * ⚠️ The parameters go in the **fragment**, never the query string.
         * Share-into-app passes the user's own text, and a query string is sent
         * to the server in the GET request line — that would put note plaintext
         * in the server's logs and break the E2EE guarantee outright. Fragments
         * are never transmitted, which is the same reason the client-key
         * handoff uses `#ck=`. The web app strips it immediately on read.
         */
        fun newNoteUrl(noteType: String?, title: String? = null, body: String? = null): String {
            val base = BuildConfig.NOTE_API_BASE_URL.trimEnd('/')
            // URLEncoder, not android.net.Uri: this stays a pure JVM function so
            // it is unit-testable, and its form encoding is exactly what the web
            // side's URLSearchParams decodes (including `+` as a space).
            fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8.name())
            val parts = mutableListOf<String>()
            if (noteType == "checklist") parts += "type=checklist"
            if (!title.isNullOrEmpty()) parts += "title=" + enc(title)
            if (!body.isNullOrEmpty()) parts += "body=" + enc(body)
            return if (parts.isEmpty()) "$base/new" else "$base/new#" + parts.joinToString("&")
        }

        fun newNoteIntent(
            context: Context,
            noteType: String? = null,
            title: String? = null,
            body: String? = null,
        ): Intent = Intent(context, WebAppActivity::class.java)
            .putExtra(EXTRA_URL, newNoteUrl(noteType, title, body))
    }
}
