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
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Custom test runner for Hilt instrumented tests.
 *
 * Swaps the production NoteApplication for HiltTestApplication so Hilt test
 * modules (via @TestInstallIn / @UninstallModules) can replace production
 * dependencies with test doubles.
 *
 * Registered as testInstrumentationRunner in app/build.gradle.kts.
 */
class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        cl: ClassLoader?,
        name: String?,
        context: Context?,
    ): Application {
        // Swapping in HiltTestApplication also swaps OUT
        // NoteApplication .onCreate, which is the only place this is
        // loaded. Without it, anything touching the Rust SDK dies with
        // an UnsatisfiedLinkError that looks like a failure in the code
        // under test rather than a missing test-harness step. Load it
        // here, exactly as NoteApplication does.
        System.loadLibrary("pingolin_mobile")
        // SQLCipher is deliberately NOT mirrored here. Its load and its
        // pool cap live in SqlCipherNative.ensure(), which
        // NoteDatabase.create() calls and instrumented tests go through
        // like everything else — so they get the real configuration by
        // construction instead of from a hand-maintained copy that
        // could drift from the app's.
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
