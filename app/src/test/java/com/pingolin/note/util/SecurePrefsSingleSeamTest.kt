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
 * Guards the single seam: `EncryptedSharedPreferences.create` and `MasterKey.Builder` may
 * appear in SecurePrefs.kt and nowhere else.
 *
 * WHY A TEST AND NOT A CONVENTION
 *   These APIs are deprecated. Spread over independent call sites, replacing them would
 *   mean one data migration per site — on session tokens, retained content keys, pinned
 *   sender keys and the SQLCipher passphrase. KEEPING them in one seam is the part that
 *   decays, because the natural way to add a new encrypted store is to copy an existing
 *   one.
 *
 *   A comment asking people not to would be ignored, so this fails the build instead. It
 *   runs as an ordinary JVM unit test — no device, no Robolectric.
 *
 * IF THIS TEST FAILS
 *   You added an encrypted store. Call `SecurePrefs.open(context, "your_file")` instead of
 *   constructing one. If you genuinely need the MasterKey itself, use
 *   `SecurePrefs.masterKey(context)`.
 */
class SecurePrefsSingleSeamTest {

    private val guarded = listOf("EncryptedSharedPreferences.create(", "MasterKey.Builder(")

    @Test
    fun `deprecated crypto-prefs APIs are only constructed in SecurePrefs`() {
        val sourceRoot = findSourceRoot()
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "SecurePrefs.kt" }
            .filter { file -> guarded.any { file.readText().contains(it) } }
            .map { it.relativeTo(sourceRoot).path }
            .sorted()
            .toList()

        assertEquals(
            "These files construct a deprecated EncryptedSharedPreferences/MasterKey " +
                "directly. Use SecurePrefs.open(context, fileName) — see SecurePrefs.kt " +
                "for why the single seam matters.",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * Walks up from the working directory rather than assuming it. Gradle runs unit
     * tests from the module dir, but that is a default, not a guarantee — and a test
     * that silently scanned an empty tree would pass forever while guarding nothing.
     */
    private fun findSourceRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java/com/pingolin/note")
            if (candidate.isDirectory) return candidate
            val direct = File(dir, "src/main/java/com/pingolin/note")
            if (direct.isDirectory) return direct
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not locate the source root from ${File("").absolutePath}. " +
                "Fix this lookup rather than deleting the test — an unfindable tree " +
                "means the guard is not guarding anything."
        )
    }
}
