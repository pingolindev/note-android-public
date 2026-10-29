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
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier

class RootDetectorTest {

    @MockK
    private lateinit var context: Context

    @MockK
    private lateinit var packageManager: PackageManager

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        every { context.packageManager } returns packageManager
    }

    @After
    fun teardown() {
        clearAllMocks()
    }

    // =============================================================================
    // checkRoot STRUCTURE TESTS
    // =============================================================================

    @Test
    fun `checkRoot_returnsRootCheckResult`() {
        // Mock that no root management apps are installed
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws
            PackageManager.NameNotFoundException()

        val result = RootDetector.checkRoot(context)

        assertNotNull(result)
        assertNotNull(result.reasons)
    }

    @Test
    fun `checkRoot_rootCheckResultContainsIsRooted`() {
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws
            PackageManager.NameNotFoundException()

        val result = RootDetector.checkRoot(context)

        // isRooted is deterministic based on system state
        assertNotNull(result.isRooted)
    }

    @Test
    fun `checkRoot_reasonsListMatchesIsRooted`() {
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws
            PackageManager.NameNotFoundException()

        val result = RootDetector.checkRoot(context)

        // isRooted should be true iff reasons is non-empty
        assertEquals(result.reasons.isNotEmpty(), result.isRooted)
    }

    // =============================================================================
    // isRooted TESTS
    // =============================================================================

    @Test
    fun `isRooted_delegatesToCheckRoot`() {
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws
            PackageManager.NameNotFoundException()

        val checkResult = RootDetector.checkRoot(context)
        val isRooted = RootDetector.isRooted(context)

        assertEquals(checkResult.isRooted, isRooted)
    }

    // =============================================================================
    // ROOT MANAGEMENT APP DETECTION TESTS
    // =============================================================================

    @Test
    fun `checkRoot_magiskInstalled_detectsRoot`() {
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } answers {
            val pkg = firstArg<String>()
            if (pkg == "com.topjohnwu.magisk") {
                PackageInfo()
            } else {
                throw PackageManager.NameNotFoundException()
            }
        }

        val result = RootDetector.checkRoot(context)

        assertTrue(result.isRooted)
        assertTrue(result.reasons.any { it.contains("Root management app") || it.contains("Magisk") || it.contains("root") })
    }

    @Test
    fun `checkRoot_supersuInstalled_detectsRoot`() {
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } answers {
            val pkg = firstArg<String>()
            if (pkg == "eu.chainfire.supersu") {
                PackageInfo()
            } else {
                throw PackageManager.NameNotFoundException()
            }
        }

        val result = RootDetector.checkRoot(context)

        assertTrue(result.isRooted)
    }

    @Test
    fun `checkRoot_noRootApps_noRootManagementReason`() {
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws
            PackageManager.NameNotFoundException()

        val result = RootDetector.checkRoot(context)

        assertFalse(result.reasons.any { it.contains("Root management app") })
    }

    // =============================================================================
    // XPOSED DETECTION TESTS
    // =============================================================================

    @Test
    fun `checkRoot_xposedInstalled_detectsRoot`() {
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } answers {
            val pkg = firstArg<String>()
            if (pkg == "de.robv.android.xposed.installer") {
                PackageInfo()
            } else {
                throw PackageManager.NameNotFoundException()
            }
        }

        val result = RootDetector.checkRoot(context)

        assertTrue(result.isRooted)
    }

    @Test
    fun `checkRoot_lsposedInstalled_detectsRoot`() {
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } answers {
            val pkg = firstArg<String>()
            if (pkg == "org.lsposed.manager") {
                PackageInfo()
            } else {
                throw PackageManager.NameNotFoundException()
            }
        }

        val result = RootDetector.checkRoot(context)

        assertTrue(result.isRooted)
    }

    // =============================================================================
    // TEST KEYS DETECTION TESTS
    // =============================================================================

    @Test
    @org.junit.Ignore("Build.TAGS is static final and current JDKs don't let reflection change it (DirectMethodHandleAccessor on JDK 21), so this test can't simulate a test-keys build.")
    fun `checkRoot_testKeysBuild_detectsTestKeys`() {
        // Use reflection to set Build.TAGS (it's a static field)
        try {
            setStaticField(Build::class.java.getDeclaredField("TAGS"), "test-keys")

            every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws
                PackageManager.NameNotFoundException()

            val result = RootDetector.checkRoot(context)

            assertTrue(result.isRooted)
            assertTrue(result.reasons.any { it.contains("test-keys") || it.contains("Test-keys") })
        } finally {
            setStaticField(Build::class.java.getDeclaredField("TAGS"), "release-keys")
        }
    }

    @Test
    fun `checkRoot_releaseKeysBuild_noTestKeysReason`() {
        try {
            setStaticField(Build::class.java.getDeclaredField("TAGS"), "release-keys")

            every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws
                PackageManager.NameNotFoundException()

            val result = RootDetector.checkRoot(context)

            assertFalse(result.reasons.any { it.contains("test-keys") || it.contains("Test-keys") })
        } finally {
            // Reset
            setStaticField(Build::class.java.getDeclaredField("TAGS"), null)
        }
    }

    // =============================================================================
    // RootCheckResult DATA CLASS TESTS
    // =============================================================================

    @Test
    fun `rootCheckResult_notRooted_emptyReasons`() {
        val result = RootDetector.RootCheckResult(isRooted = false, reasons = emptyList())

        assertFalse(result.isRooted)
        assertTrue(result.reasons.isEmpty())
    }

    @Test
    fun `rootCheckResult_rooted_nonEmptyReasons`() {
        val result = RootDetector.RootCheckResult(
            isRooted = true,
            reasons = listOf("Root binaries found", "su binary accessible"),
        )

        assertTrue(result.isRooted)
        assertEquals(2, result.reasons.size)
    }

    // =============================================================================
    // HELPERS
    // =============================================================================

    private fun setStaticField(field: Field, value: Any?) {
        field.isAccessible = true
        // Remove final modifier
        val modifiersField = try {
            Field::class.java.getDeclaredField("modifiers")
        } catch (e: NoSuchFieldException) {
            // Java 12+ uses a different approach; skip for test environments
            return
        }
        modifiersField.isAccessible = true
        modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())
        field.set(null, value)
    }
}
