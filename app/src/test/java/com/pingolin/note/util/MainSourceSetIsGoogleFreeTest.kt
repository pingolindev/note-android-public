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

package com.pingolin.note.util

import org.junit.Assert.assertEquals
import java.io.File
import org.junit.Test

/**
 * Guards the flavour split: nothing in `src/main` may reference Firebase, Google
 * Play services or the Play Core/Integrity libraries. Those artifacts are scoped
 * to `gmsImplementation` in app/build.gradle.kts, so anything in `src/main` that
 * names them does not exist on the libre variant's classpath.
 *
 * WHY A TEST AND NOT A CONVENTION
 *   The libre flavour's whole value is the absence of these artifacts, and the
 *   natural way to use FCM is to call `FirebaseMessaging.getInstance()` at the
 *   point you need it — which compiles fine, because the person adding it is
 *   almost certainly building a gms variant. The break then surfaces only when
 *   someone assembles libre, which may be days later, as an unresolved reference
 *   in a file that looks nothing like the change that caused it.
 *
 *   This runs in BOTH flavours (it lives in `src/test`, not `src/testLibre`), so
 *   the gms build that introduced the import is the one that fails.
 *
 * IF THIS TEST FAILS
 *   Put the Google-specific code in `src/gms`, and give `src/libre` a same-named
 *   counterpart that reports the capability as unavailable. There are two worked
 *   examples: `PlayIntegrityManager` (same class in both source sets) and
 *   [FcmTokenProvider] (interface in `src/main`, an implementation in each). Do
 *   NOT relax the list below.
 *
 * NOT guarded, deliberately: `com.google.android.material` (Material Components)
 * and `com.google.dagger` / Hilt are Apache-2.0 libraries with no Google
 * services behind them. They ship in the libre APK and that is fine.
 */
class MainSourceSetIsGoogleFreeTest {

    private val guarded = listOf(
        "com.google.firebase",
        "com.google.android.gms",
        "com.google.android.play",
    )

    @Test
    fun `src-main references no Firebase, Play services or Play Integrity`() {
        val sourceRoot = findMainSourceSet()
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
            .filter { file -> guarded.any { file.readText().contains(it) } }
            .map { it.relativeTo(sourceRoot).path }
            .sorted()
            .toList()

        assertEquals(
            "These files in src/main reference Google artifacts that only the gms " +
                "flavour resolves, so the libre variant cannot compile them. Move the " +
                "code to src/gms and add a src/libre counterpart.",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The gms source set is where these references are SUPPOSED to be. Asserting
     * it is non-empty keeps the test above honest: if src/gms were ever emptied
     * or renamed, the guard would still pass while guarding nothing.
     */
    @Test
    fun `src-gms is where the Google references actually live`() {
        // findMainSourceSet() returns <root>/app/src/main, so one level up is
        // the source-set root that holds main/, gms/ and libre/.
        val gms = File(findMainSourceSet().parentFile, "gms")
        val referencing = gms.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java", "xml") }
            .filter { file -> guarded.any { file.readText().contains(it) } }
            .map { it.relativeTo(gms).path }
            .sorted()
            .toList()

        assertEquals(
            "Expected the Firebase service, its manifest entry, the Play Integrity " +
                "manager and the FCM token provider to be the Google surface of this " +
                "app. Found: $referencing",
            listOf(
                "AndroidManifest.xml",
                "java/com/pingolin/note/service/NoteFirebaseMessagingService.kt",
                "java/com/pingolin/note/util/FirebaseFcmTokenProvider.kt",
                "java/com/pingolin/note/util/PlayIntegrityManager.kt",
            ),
            referencing,
        )
    }

    /**
     * Walks up from the working directory rather than assuming it — the same
     * reasoning as [SecurePrefsSingleSeamTest.findSourceRoot]: Gradle running
     * unit tests from the module dir is a default, not a guarantee, and a test
     * that silently scanned an empty tree would pass forever.
     */
    private fun findMainSourceSet(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main")
            if (File(candidate, "java/com/pingolin/note").isDirectory) return candidate
            val direct = File(dir, "src/main")
            if (File(direct, "java/com/pingolin/note").isDirectory) return direct
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not locate src/main from ${File("").absolutePath}. Fix this lookup " +
                "rather than deleting the test — an unfindable tree means the guard is " +
                "not guarding anything."
        )
    }
}
