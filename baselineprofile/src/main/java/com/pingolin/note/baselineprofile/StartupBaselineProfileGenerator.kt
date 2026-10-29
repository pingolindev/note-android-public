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

package com.pingolin.note.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records what a real cold launch of Note actually touches.
 *
 * ## The journey
 *
 * Cold start → MainActivity's session gate → WebAppActivity → the WebView
 * provider coming up → the shell's setup → a document in the WebView. That is
 * the stretch measured at ~1,150ms on a Galaxy A50 before a single byte of the
 * web app is requested, and essentially all of it is class loading and
 * interpretation that a profile turns into AOT-compiled code.
 *
 * It stops once the WebView has content. Everything past that is Chromium's own
 * work and the web app's JavaScript — ART compiles neither, so a baseline
 * profile cannot help there.
 *
 * ## Why it signs in, and why that is not gratuitous
 *
 * A signed-OUT launch stops at AuthActivity and never constructs
 * WebAppActivity, the retained WebView, the key handoff or the document
 * prefetch. On a DEV build that still happens to record the WebView provider
 * load, because NoteApplication turns on web-contents debugging for dev
 * flavours and that pulls the provider in — but PROD does not, so on the build
 * that actually ships, a signed-out recording would miss the provider load
 * entirely. Since one profile is merged into every variant, it has to be
 * recorded on the journey prod takes.
 *
 * The sign-in happens at most ONCE per generation run, not once per iteration:
 * app data survives between iterations, so only the first finds a login screen.
 * That matters — the auth server caps an account's concurrent sessions (at 10)
 * and evicts the OLDEST, so a login per iteration would quietly sign this
 * account out elsewhere and surface as unrelated flake.
 *
 * Credentials come from instrumentation arguments so nothing lands in the repo:
 *
 * ```
 * ANDROID_SERIAL=<device> ./gradlew :app:generateBaselineProfile \
 *   -Pandroid.testInstrumentationRunnerArguments.noteEmail=… \
 *   -Pandroid.testInstrumentationRunnerArguments.notePassword=…
 * ```
 */
class StartupBaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE,
        // The startup path is deterministic enough that a handful of iterations
        // is ample, and it keeps the run to a couple of minutes on an emulator.
        maxIterations = 5,
        stableIterations = 3,
    ) {
        pressHome()
        startActivityAndWait()

        signInIfAsked()

        // WebAppActivity hosts one WebView and nothing else, so its presence is
        // the signal that the session gate passed and the shell is up. Waiting
        // on it keeps the recording open through the WebView provider load —
        // the single largest class-loading burst on the path.
        val shell = device.wait(Until.hasObject(By.clazz(WEBVIEW)), SHELL_TIMEOUT_MS)
        check(shell) {
            "Never reached the web shell within ${SHELL_TIMEOUT_MS}ms. Without a " +
                "session the app stops at AuthActivity and the profile would " +
                "cover the login path instead of the one prod takes. Pass " +
                "-Pandroid.testInstrumentationRunnerArguments.noteEmail / " +
                ".notePassword, or sign the device in by hand, and re-run."
        }
    }

    /**
     * Drive the native login, but only when it is actually on screen. Returns
     * immediately on every iteration after the first, and on a device that was
     * already signed in.
     */
    private fun MacrobenchmarkScope.signInIfAsked() {
        val email = device.wait(Until.findObject(By.res(PACKAGE, "etEmail")), LOGIN_PROBE_MS)
            ?: return

        val args = InstrumentationRegistry.getArguments()
        val user = args.getString("noteEmail")
        val pass = args.getString("notePassword")
        check(!user.isNullOrBlank() && !pass.isNullOrBlank()) {
            "The device is signed out and no credentials were given. Pass " +
                "-Pandroid.testInstrumentationRunnerArguments.noteEmail and " +
                ".notePassword."
        }

        email.text = user
        device.findObject(By.res(PACKAGE, "etPassword")).text = pass
        device.findObject(By.res(PACKAGE, "btnSubmit")).click()

        // SRP plus the key unwrap is slow on an emulator, and a notification
        // permission dialog can land on top of it on API 33+.
        device.wait(Until.gone(By.res(PACKAGE, "btnSubmit")), LOGIN_TIMEOUT_MS)
        device.findObject(By.text("Allow"))?.click()
    }

    private companion object {
        const val PACKAGE = "com.pingolindev.note.dev"
        const val WEBVIEW = "android.webkit.WebView"
        const val SHELL_TIMEOUT_MS = 30_000L
        const val LOGIN_PROBE_MS = 4_000L
        const val LOGIN_TIMEOUT_MS = 60_000L
    }
}
