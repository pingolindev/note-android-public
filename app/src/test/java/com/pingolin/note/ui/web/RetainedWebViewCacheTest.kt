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

package com.pingolin.note.ui.web

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tearing the retained WebView down (a sign-out, a switch, an erasure) clears the app's HTTP
 * cache, disk files included. It holds attachment blobs served `private, max-age=3600`, and
 * WebStorage.deleteAllData never reaches it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RetainedWebViewCacheTest {

    @Test
    fun `destroying the retained WebView clears its HTTP cache, disk files included`() {
        val web = RetainedWebView.obtain(ApplicationProvider.getApplicationContext<Context>()) {}
        RetainedWebView.destroy()
        assertTrue(shadowOf(web).wasClearCacheCalled())
        assertTrue(shadowOf(web).didClearCacheIncludeDiskFiles())
    }
}
