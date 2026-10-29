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

package com.pingolin.note

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.pingolin.note.service.PinnedNoteService
import com.pingolin.note.ui.web.AuthMePrefetch
import com.pingolin.note.util.CompromisedKeyTrustProvider
import com.pingolin.note.util.CrashReporter
import com.pingolin.note.util.SessionManager
import com.pingolin.note.worker.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NoteApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var crashReporter: CrashReporter

    /** Needed at process start for [applyTheme] only — see the comment there. */
    @Inject
    lateinit var sessionManager: SessionManager

    /** The API client's trust, for AuthMePrefetch's own connection (CompromisedKeys). */
    @Inject
    lateinit var compromisedKeyTrust: com.pingolin.note.util.CompromisedKeyTrustManager

    @Inject
    lateinit var connectivityMonitor: com.pingolin.note.util.ConnectivityMonitor

    /** Required so WorkManager can construct `@HiltWorker` workers like
     *  SyncWorker (which has an `@AssistedInject` constructor pulling
     *  SyncRepository from the Hilt graph). Without this factory
     *  WorkManager falls back to the default reflective constructor
     *  lookup and dies with NoSuchMethodException on every scheduled
     *  sync attempt. */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * The process's default trust, refusing paths through compromised keys, goes in before anything asks for
     * one: the editor's WebView reads the default (CompromisedKeyTrustProvider).
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CompromisedKeyTrustProvider.install(base.noBackupFilesDir, debug = BuildConfig.DEBUG)
    }

    override fun onCreate() {
        super.onCreate()

        // Whole-app background detection for the app lock. When the
        // count of STARTED activities drops to zero the app truly
        // left the foreground: clear the in-memory "lock already
        // passed" flag (see SessionManager. requiresAppLockAuth) and
        // stamp the background time for the timeout modes. A
        // per-activity onStop cannot do this — during the unlocked
        // MainActivity -> WebAppActivity handoff the outgoing
        // activity's onStop fires AFTER the new one's onStart, and
        // treating that blip as "backgrounded" re-locks an app the
        // user is looking at.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) {
                started++
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                started--
                if (started == 0 && sessionManager.isAppLockEnabled()) {
                    sessionManager.clearAppLockPassed()
                    sessionManager.recordBackgroundTime()
                }
            }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })

        // startsWith, not ==: two flavor dimensions make FLAVOR "devGms"/"devLibre".
        if (BuildConfig.FLAVOR.startsWith("dev")) {
            // Dev builds only: the in-app document becomes inspectable from
            // chrome://inspect (and the DevTools protocol over adb). Page
            // state in the retained document is otherwise unreachable from
            // logcat, including in the dev-flavor RELEASE build.
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }

        // The night mode MUST be set here and not from an Activity's
        // onCreate.
        //
        // AppCompatActivity bakes the night mode into its resources in
        // attachBaseContext, reading whatever AppCompatDelegate's
        // static holds at that instant. An Activity that sets it from
        // its own onCreate has ALREADY attached with the system's
        // night mode, so when the stored theme disagrees,
        // AppCompatDelegateImpl.updateAppConfiguration sees the uiMode
        // bit change and calls ActivityCompat.recreate() — a whole
        // second MainActivity construction, ~231ms after the first and
        // ~45ms AFTER first frame (two wm_on_create_called for one
        // token). Called from HERE the active-delegate set is still
        // empty (in appcompat 1.7.1, addActiveDelegate has exactly one
        // caller, AppCompatDelegateImpl.onCreate), so this cannot
        // recreate anything — it only sets the static every later
        // attachBaseContext reads.
        //
        // It also has to be process-wide for correctness: a process
        // can cold-start into WebAppActivity,
        // LockScreenCaptureActivity or the widget config, and each of
        // them must follow the user's theme too.
        applyTheme()

        // UniFFI Rust crypto SDK. Stays here: the first Activity's injection
        // needs CryptoManager on the main thread anyway, so deferring this only
        // moves the same dlopen a few hundred ms later onto the same thread.
        System.loadLibrary("pingolin_mobile")
        // SQLCipher's load and its pool cap live in SqlCipherNative,
        // which NoteDatabase.create() calls, not here: here it would be a
        // 5MB dlopen on the main thread of every launch, for a database
        // that nothing opens until the sync runs, off-Main and much later.
        // SqlCipherNative also keeps the pool cap attached to the load, so
        // the "must be set before the first OPEN" ordering cannot be
        // broken by a reorder.

        // Initialize crash reporting. Respects the user's stored opt-in
        // (CrashReporter is opt-in / disabled by default); must NOT force-
        // enable, which would silently revert a Settings opt-out on every
        // launch and write PREF_CRASH_REPORTING_ENABLED=true behind the
        // user's back.
        crashReporter.initialize()

        // Create notification channels early (before any notifications are posted)
        createNotificationChannels()

        // An install upgraded from a build that ran the persistent
        // capture foreground service (the widget/tile replace it) can
        // hold an orphaned ongoing notification — id 1,
        // FOREGROUND_SERVICE | NO_CLEAR — that nothing else cancels, so
        // it sticks until reboot. Cancel it and drop the dead
        // "quick_capture" channel so it stops showing in the app's
        // notification settings. No-op on fresh installs.
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(LEGACY_CAPTURE_NOTIFICATION_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.deleteNotificationChannel(LEGACY_CAPTURE_CHANNEL)
            }
        }

        // NO SyncWorker enqueue here. This app removes
        // WorkManagerInitializer from androidx.startup (so it can supply a
        // Hilt worker factory), which makes the first WorkManager.getInstance
        // in a process run the whole on-demand initialization inline on the
        // calling thread — here, the MAIN thread, during Application.onCreate.
        // The periodic work is unique+KEEP and survives process death, and it
        // is re-armed off-Main from MainActivity on every entry to the main
        // screen (the path an account switch takes after the wipe cancels it)
        // and from BootReceiver after a reboot. Nothing needs it here.

        // Start watching connectivity so we can fire an expedited sync
        // the moment Wi-Fi/cellular comes back. Without this the user
        // sometimes waits up to 15 minutes (periodic worker tick) for
        // an edit made offline to actually push.
        connectivityMonitor.start()

        // THE ONE API CALL THAT BLOCKS RENDERING, issued before the
        // WebView can.
        //
        // (The web app's document needs no prefetch: it is served from
        // the APK, instantly, so there is no round trip to hide — see
        // WebAppAssets.)
        //
        // Gated on being signed in, because a signed-out launch goes to
        // AuthActivity and would never use it.
        if (sessionManager.isLoggedIn()) {
            // The web editor's App.tsx gates the router outlet on
            // checkAuth() resolving, so the notes list cannot
            // paint until /auth/me returns. The page already fires
            // it as early as it can — before `await initCrypto()`
            // — but that is still behind the document, the entry
            // chunk and Solid's boot: as measured, it leaves
            // at ~420ms and lands at ~730-945ms, roughly 310ms of
            // a ~780ms chain, all of it serial.
            //
            // Same request, issued here instead, overlapping the
            // provider load and both Activities. Credentialed, so
            // AuthMePrefetch keys the result to this token and
            // re-checks it before serving.
            sessionManager.getAccessToken()?.let { token ->
                AuthMePrefetch.start(BuildConfig.NOTE_API_BASE_URL, token, "PingolinNoteShell", compromisedKeyTrust.socketFactory())
            }
        }
    }

    private fun applyTheme() {
        when (sessionManager.getTheme()) {
            SessionManager.THEME_LIGHT ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            SessionManager.THEME_DARK ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else ->
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Canonical definition of `note_reminders`. ReminderReceiver has a
            // create-if-absent safety net for the case where an alarm beats
            // this; it must never re-create the channel, because name and
            // description are mutable through createNotificationChannel and a
            // second definition would make the label in Android's settings flip
            // between two spellings depending on which ran last.
            val reminderChannel = NotificationChannel(
                "note_reminders",
                getString(R.string.notification_channel_reminders),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_reminders_description)
                enableVibration(true)
            }

            val syncChannel = NotificationChannel(
                "note_sync", "Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background sync status"
                setShowBadge(false)
            }

            val pinnedChannel = NotificationChannel(
                PinnedNoteService.CHANNEL_PINNED_NOTES, "Pinned Notes",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notes pinned to the notification shade and lock screen"
                // Redact on the lock screen: a pinned note carries a decrypted
                // title + preview, which must stay behind the keyguard / App
                // Lock. VISIBILITY_PRIVATE shows a placeholder until unlock.
                // (An explicit PUBLIC here would OVERRIDE the per-notification
                // VISIBILITY_PRIVATE and leak the content.)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(false)
            }

            manager.createNotificationChannels(listOf(reminderChannel, syncChannel, pinnedChannel))
        }
    }

    /**
     * The retained WebView is the only unbounded memory this process holds by
     * design — a booted SPA plus its renderer, kept so note opens are a route
     * change instead of a 2-second boot. TRIM_MEMORY_BACKGROUND is the level
     * that arrives once the process is on the LRU kill list, so nothing is on
     * screen: discard the WebView there and the next open pays one full boot,
     * which beats the process being killed outright. Deliberately NOT hooked
     * at the RUNNING_* levels — those fire while the user is looking at the
     * app, and throwing the document away mid-session would cost them a full
     * boot on their next open. discardIfUnheld is a no-op while any Activity
     * holds the WebView, which also covers a stopped-but-alive editor.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            com.pingolin.note.ui.web.RetainedWebView.discardIfUnheld()
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .setWorkerFactory(workerFactory)
            .build()

    private companion object {
        // Notification id + channel of the retired capture service, kept only
        // so an updated install can clean up its orphaned notification.
        const val LEGACY_CAPTURE_NOTIFICATION_ID = 1
        const val LEGACY_CAPTURE_CHANNEL = "quick_capture"
    }
}
