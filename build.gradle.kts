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

// Keep the Kotlin daemon alive.
//
// AGP drags kotlin-daemon-client up to 2.4.0 while the Kotlin Gradle Plugin is
// 2.3.10 — `buildEnvironment` shows "kotlin-daemon-client:2.3.10 -> 2.4.0" — and
// KGP then calls a connectAndLease overload 2.4.0 does not have:
//
//   Failed to compile with Kotlin daemon: java.lang.NoSuchMethodError:
//   'CompileServiceSession KotlinCompilerClient.connectAndLease(...)'
//   Using fallback strategy: Compile without Kotlin daemon
//
// Nothing FAILS, so it goes unnoticed: every Kotlin compilation quietly takes
// the in-process path instead. Measured, that costs nothing (30-31s either way
// on :app:compileDevGmsDebugKotlin) — this is pinned for the silence, not the
// speed. A stack trace in every build log is noise that hides real ones, and the
// fallback is a latent trap if this module grows.
buildscript {
    configurations.classpath {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-daemon-client:2.3.10")
        }
    }
}

// Top-level build file
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    id("com.android.test") version "9.3.1" apply false
    // Baseline profile. Generates the AOT-compilation hint list that
    // ships as assets/dexopt/baseline.prof, from a real launch recorded
    // on a device — see the :baselineprofile module. Without it the app
    // would ship only AGP's merge of its AAR dependencies' profiles
    // (4,027 bytes), which contains nothing about this app's own startup
    // path.
    id("androidx.baselineprofile") version "1.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("androidx.navigation.safeargs.kotlin") version "2.9.7" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    // Third-party attribution. Reads the resolved runtime classpath's POM
    // metadata and emits a licence report per variant; see the
    // `generateThirdPartyLicenses` task in app/build.gradle.kts, which turns
    // that report into the two artifacts we actually ship.
    id("com.jaredsburrows.license") version "0.9.9" apply false
}
