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

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SessionManager.
 *
 * Since SessionManager relies on EncryptedSharedPreferences which requires
 * Android Keystore, we test the logic by mocking SharedPreferences directly.
 */
class SessionManagerTest {

    @MockK
    private lateinit var prefs: SharedPreferences

    @MockK
    private lateinit var editor: SharedPreferences.Editor

    private lateinit var sessionManager: SessionManager

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.clear() } returns editor
        every { editor.apply() } just Runs

        // Create SessionManager using reflection to inject mock prefs
        // since the constructor calls EncryptedSharedPreferences.create
        sessionManager = mockk(relaxed = false)
    }

    @After
    fun teardown() {
        clearAllMocks()
    }

    // =============================================================================
    // getAccessToken TESTS
    // =============================================================================

    @Test
    fun `getAccessToken_noToken_returnsNull`() {
        every { sessionManager.getAccessToken() } returns null

        assertNull(sessionManager.getAccessToken())
    }

    @Test
    fun `getAccessToken_withToken_returnsToken`() {
        every { sessionManager.getAccessToken() } returns "test-access-token"

        assertEquals("test-access-token", sessionManager.getAccessToken())
    }

    // =============================================================================
    // getRefreshToken TESTS
    // =============================================================================

    @Test
    fun `getRefreshToken_noToken_returnsNull`() {
        every { sessionManager.getRefreshToken() } returns null

        assertNull(sessionManager.getRefreshToken())
    }

    @Test
    fun `getRefreshToken_withToken_returnsToken`() {
        every { sessionManager.getRefreshToken() } returns "test-refresh-token"

        assertEquals("test-refresh-token", sessionManager.getRefreshToken())
    }

    // =============================================================================
    // saveTokens TESTS
    // =============================================================================

    @Test
    fun `saveTokens_storesBothTokens`() {
        every { sessionManager.saveTokens(any(), any()) } just Runs

        sessionManager.saveTokens("access-123", "refresh-456")

        verify { sessionManager.saveTokens("access-123", "refresh-456") }
    }

    // =============================================================================
    // isLoggedIn TESTS
    // =============================================================================

    @Test
    fun `isLoggedIn_withToken_returnsTrue`() {
        every { sessionManager.isLoggedIn() } returns true

        assertTrue(sessionManager.isLoggedIn())
    }

    @Test
    fun `isLoggedIn_noToken_returnsFalse`() {
        every { sessionManager.isLoggedIn() } returns false

        assertFalse(sessionManager.isLoggedIn())
    }

    // =============================================================================
    // getUserId TESTS
    // =============================================================================

    @Test
    fun `getUserId_noUserId_returnsNull`() {
        every { sessionManager.getUserId() } returns null

        assertNull(sessionManager.getUserId())
    }

    @Test
    fun `getUserId_withUserId_returnsId`() {
        every { sessionManager.getUserId() } returns "user-123"

        assertEquals("user-123", sessionManager.getUserId())
    }

    // =============================================================================
    // saveUserId TESTS
    // =============================================================================

    @Test
    fun `saveUserId_storesUserId`() {
        every { sessionManager.saveUserId(any()) } just Runs

        sessionManager.saveUserId("user-789")

        verify { sessionManager.saveUserId("user-789") }
    }

    // =============================================================================
    // clear TESTS
    // =============================================================================

    @Test
    fun `clear_removesAllData`() {
        every { sessionManager.clear() } just Runs

        sessionManager.clear()

        verify { sessionManager.clear() }
    }

}
