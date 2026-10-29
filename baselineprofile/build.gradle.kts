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

plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

/**
 * Generates the app's baseline profile — the list of classes and methods ART
 * should compile ahead of time instead of interpreting on first run.
 *
 * Without this module the app ships only `assets/dexopt/baseline.prof` as AGP
 * produces it: 4,027 bytes, entirely the merge of its AAR dependencies' own
 * profiles, with nothing in it about this app's startup path. Measured on a
 * Galaxy A50, time from process start to MainActivity.onCreate returning is
 * ~1,000ms, most of it class loading and interpretation on exactly that path.
 *
 * This module produces NO shipped artifact. It is a `com.android.test` project:
 * it builds an instrumentation APK, runs it against :app on a device, records
 * what a real launch touches, and writes the result back into :app's sources.
 */
android {
    namespace = "com.pingolin.note.baselineprofile"
    compileSdk = 36

    defaultConfig {
        // 28 is the floor for profile generation; the app's own minSdk is 26 and
        // is unaffected — devices below 28 simply ignore the shipped profile.
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // :app has two flavour dimensions and this module has none, so AGP
        // cannot infer which variant to instrument. Pin it to the dev/gms line —
        // that is what development devices run, and a profile is a list of code
        // paths rather than a build-specific artifact, so one is correct for
        // every flavour.
        missingDimensionStrategy("environment", "dev")
        missingDimensionStrategy("distribution", "gms")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// PRODUCER side. Where the recording runs; what is done with the result is
// configured on the consumer (:app).
baselineProfile {
    // An attached device rather than a Gradle-managed one: this needs a
    // SIGNED-IN device to reach the web shell at all, and a freshly created
    // managed device has no session. See the generator's own comment.
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.espresso:espresso-core:3.7.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
