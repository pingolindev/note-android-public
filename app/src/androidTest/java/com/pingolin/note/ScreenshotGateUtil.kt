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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pingolin.note.util.SessionManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test — an on-device-verification convenience. MainActivity
 * applies FLAG_SECURE whenever SessionManager.isBlockScreenshotsEnabled()
 * (secure by default, and deliberately without a Settings toggle), which
 * blanks `adb screencap` while verifying UI work on real hardware. These
 * entries flip just that pref on the device's REAL session — tokens,
 * vault key, and notes are untouched — so a verification session can
 * capture visual evidence and then restore the secure default:
 *
 *   adb shell am instrument -w \
 *     -e class com.pingolin.note.ScreenshotGateUtil#disableScreenshotBlocking \
 *     com.pingolindev.note.dev.test/com.pingolin.note.HiltTestRunner
 *
 * Relaunch the app afterwards — the flag is read in onCreate. Always
 * finish with enableScreenshotBlocking.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ScreenshotGateUtil {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val workManagerInitRule = WorkManagerInitRule()

    @Inject
    lateinit var sessionManager: SessionManager

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun disableScreenshotBlocking() {
        sessionManager.setBlockScreenshots(false)
        flushApply()
    }

    @Test
    fun enableScreenshotBlocking() {
        sessionManager.setBlockScreenshots(true)
        flushApply()
    }

    /** SessionManager writes with apply() (async); am instrument kills
     *  the process the moment the test method returns, which can land
     *  before the queued disk write. Give QueuedWork time to flush. */
    private fun flushApply() {
        Thread.sleep(1_500)
    }
}
