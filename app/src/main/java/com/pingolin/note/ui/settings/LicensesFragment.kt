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

package com.pingolin.note.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.pingolin.note.databinding.FragmentLicensesBinding

/**
 * The "Open source licenses" screen: attribution for every third-party
 * component this app bundles.
 *
 * The notices are a build artifact. `:app:generateThirdPartyLicenses` merges
 * the POM licence report, the native-crate inventory and the hand-maintained
 * supplement into `assets/open_source_licenses.html`, which is packaged into
 * the APK. Nothing here is fetched, so the screen works with no network and
 * cannot go stale relative to the shipped binary — the asset was generated
 * from the same dependency graph the APK was built from.
 *
 * Rendering it in a WebView rather than a TextView is what keeps that
 * generated file the single source of truth: the same document is the repo's
 * THIRD_PARTY_LICENSES.md, and no parsing or re-styling happens on device.
 *
 * SQLCipher's Community Edition licence is the reason this screen has to
 * exist at all rather than being a nicety — it requires "reproduction of
 * license grants in the application interface and/or materials", in a
 * user-accessible location.
 */
class LicensesFragment : Fragment() {

    private var _binding: FragmentLicensesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLicensesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.licensesToolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.licensesWebView.apply {
            // JavaScript stays off. The asset is static markup, so there is
            // nothing to run, and a WebView with scripting disabled cannot be
            // turned into an execution surface by anything the document says.
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // The document is authored for the viewport; let it size itself
            // rather than rendering desktop-wide and zooming out.
            settings.loadWithOverviewMode = false
            settings.useWideViewPort = false
            isVerticalScrollBarEnabled = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT)

            webViewClient = object : WebViewClient() {
                // Every licence row links to an upstream project or licence
                // text. Those are real web URLs, so hand them to the browser
                // rather than navigating this WebView off its offline asset.
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        request.url.toString().toUri(),
                    )
                    // No browser installed is survivable; crashing the
                    // licences screen over a tapped link is not.
                    runCatching { startActivity(intent) }
                    return true
                }
            }

            loadUrl(ASSET_URL)
        }
    }

    override fun onDestroyView() {
        // The WebView outlives the binding otherwise and leaks the fragment's
        // context through its own view hierarchy.
        binding.licensesWebView.destroy()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Written by `:app:generateThirdPartyLicenses`; see the class doc. */
        const val ASSET_FILE = "open_source_licenses.html"
        private const val ASSET_URL = "file:///android_asset/$ASSET_FILE"
    }
}
