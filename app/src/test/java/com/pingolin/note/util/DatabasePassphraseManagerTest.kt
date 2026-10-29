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

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for DatabasePassphraseManager.
 *
 * Note: Full integration tests require Android Keystore and EncryptedSharedPreferences
 * which are only available in instrumentation tests. These tests verify the object
 * structure and constants.
 */
class DatabasePassphraseManagerTest {

    // =============================================================================
    // OBJECT STRUCTURE TESTS
    // =============================================================================

    @Test
    fun `databasePassphraseManager_isSingleton`() {
        // Verify it's an object (singleton)
        val instance1 = DatabasePassphraseManager
        val instance2 = DatabasePassphraseManager

        assertSame(instance1, instance2)
    }

    @Test
    fun `databasePassphraseManager_hasGetOrCreatePassphrase`() {
        // Verify method exists via reflection
        val method = DatabasePassphraseManager::class.java.getDeclaredMethod(
            "getOrCreatePassphrase",
            android.content.Context::class.java
        )
        assertNotNull(method)
    }
}
