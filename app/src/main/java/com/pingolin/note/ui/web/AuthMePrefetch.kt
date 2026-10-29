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

import android.util.Log
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Fetch `/api/v1/auth/me` while the native side is still starting up.
 *
 * ## Why this one request
 *
 * The web editor's `App.tsx` gates the router outlet on `!loading()`, and that
 * only flips in the `finally` of `await authStore.checkAuth()` — so the notes
 * list cannot render until this request returns. It is already as early as the
 * PAGE can make it (its `auth.ts` fires it before `await initCrypto()` precisely
 * so it overlaps the WASM), but the page itself cannot start before the
 * document, the 45KB entry chunk and Solid's boot. Measured on an A16 across
 * three cold launches (times relative to navigationStart):
 *
 *     doc        ->  ~150 ms
 *     index.js    224 ->  295
 *     libsodium   420 ->  504
 *     /auth/me    416 ->  724 / 945     <- ~310ms, strictly serial
 *     list renders ~780-1000
 *
 * So ~310ms of a ~780ms chain is one cookie-authenticated GET that needs no
 * crypto, no chunk and no DOM. This issues the identical request from
 * `Application.onCreate`, in parallel with the WebView provider load, the Hilt
 * graph and both Activities, and hands the result to `shouldInterceptRequest`.
 * Same request, same freshness — only WHEN it is issued changes. (The document
 * needs no such trip: it comes from the APK — see WebAppAssets.)
 *
 * ## The safety rules
 *
 * This is a CREDENTIALED, per-user response. Everything below exists to make
 * sure the response can only ever reach the request it was fetched for:
 *
 *  - keyed to a fingerprint of the access token it was fetched WITH, re-checked
 *    against the live token in [take]. An account switch, or a refresh between
 *    prefetch and use, mismatches and falls through to the network. The token is
 *    never stored here, only a SHA-256 prefix of it.
 *  - exact URL match, single use, and a 20s ceiling.
 *  - only a 200 is ever kept. A 401 is deliberately NOT replayed: `auth.ts` has
 *    a single-retry that absorbs the post-SRP cookie race, and it must run
 *    against a real response, not a stale one.
 *
 * ## ⚠️ `Vary: origin` — the one that could go wrong quietly
 *
 * Unlike `/`, this endpoint DOES carry a Vary (the server answers with `vary:
 * origin`, `access-control-allow-credentials: true`, `cache-control: no-cache`).
 * The WebView's call is a SAME-ORIGIN `fetch(..., {credentials:'include'})`, and
 * browsers send no `Origin` header on a same-origin GET; `HttpURLConnection`
 * sends none either. The two therefore sit in the same Vary bucket. **Nothing
 * here may ever set an Origin header**, and if `Vary` on this endpoint ever
 * grows a header the WebView sends and we do not, this must match it exactly or
 * stop intercepting.
 *
 * ## Failure is always "what happens without this"
 *
 * Every uncertain path returns null and the WebView makes its own request: no
 * prefetch, a different URL, a different token, a non-200, a non-JSON body, a
 * redirect, a body over [MAX_BYTES], or a result older than [MAX_AGE_MS].
 */
object AuthMePrefetch {

    private const val TAG = "AuthMePrefetch"

    /** The path the web app asks for — `API_URL` is `/api/v1` in `auth.ts`. */
    private const val PATH = "api/v1/auth/me"

    /**
     * How long a prefetched response may be served for. The endpoint is
     * `no-cache`, so this only ever covers the gap between
     * Application.onCreate and the WebView's own request inside ONE launch.
     */
    private val MAX_AGE_MS = TimeUnit.SECONDS.toMillis(20)

    /** A sane ceiling for a session document; dev's is well under 1KB. */
    private const val MAX_BYTES = 64 * 1024

    /** How long [take] will wait on a prefetch that is still in flight. */
    private val WAIT_MS = TimeUnit.SECONDS.toMillis(3)

    private class Fetched(
        val url: String,
        val atMs: Long,
        val tokenFp: String,
        val body: ByteArray,
        val mime: String,
        val encoding: String,
    )

    private val started = AtomicBoolean(false)
    private val done = CountDownLatch(1)
    private val result = AtomicReference<Fetched?>(null)

    @Volatile private var target: String? = null

    /** The URL this would serve, for the caller's own match check. */
    fun urlFor(baseUrl: String): String = baseUrl.trimEnd('/') + "/" + PATH

    /**
     * Begin fetching `/auth/me` for [accessToken] on a background thread.
     * One-shot per process; cheap, and a wasted one costs less than the round
     * trip it removes.
     */
    fun start(baseUrl: String, accessToken: String, userAgent: String, tls: SSLSocketFactory) {
        if (accessToken.isBlank()) return
        if (!started.compareAndSet(false, true)) return
        val url = urlFor(baseUrl)
        target = url
        Thread({ run(url, accessToken, userAgent, tls) }, "authme-prefetch").apply {
            isDaemon = true
            start()
        }
    }

    private fun run(url: String, accessToken: String, userAgent: String, tls: SSLSocketFactory) {
        var outcome: Fetched? = null
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                // It carries the session: the same trust as the API client, so no path through a
                // compromised key (CompromisedKeys) — never the platform default alone.
                (this as? HttpsURLConnection)?.sslSocketFactory = tls
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                // What `fetch()` sends. Vary is origin-only, so this cannot
                // change the bytes — matching the browser is free, so do it.
                setRequestProperty("Accept", "*/*")
                // The cookie WebAppActivity.seedSessionCookies plants for the
                // WebView, so both callers authenticate identically. The
                // refresh_token cookie is Path=/api/v1/auth/refresh and is not
                // sent to this endpoint by either of us.
                setRequestProperty("Cookie", "auth_token=$accessToken")
                // NO Origin header — see the Vary note in the class docs.
                //
                // A redirect means the session is not where we think it is (an
                // expiry bounce to the portal, a captive portal); serving
                // whatever it lands on as this user's identity would be far
                // worse than fetching nothing. The non-200 check discards it.
                instanceFollowRedirects = false
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            try {
                val code = conn.responseCode
                val type = conn.contentType.orEmpty()
                // An https:// target MUST have come back over real TLS. This
                // response becomes the app's idea of who is signed in, so it may
                // never have been fetched in clear when the URL said otherwise.
                val secure = conn is HttpsURLConnection
                if (code == HttpURLConnection.HTTP_OK &&
                    type.startsWith("application/json", ignoreCase = true) &&
                    (secure || !url.startsWith("https"))
                ) {
                    val body = conn.inputStream.use { it.readAtMost(MAX_BYTES) }
                    if (body != null) {
                        outcome = Fetched(
                            url = url,
                            atMs = System.currentTimeMillis(),
                            tokenFp = fingerprint(accessToken),
                            body = body,
                            mime = type.substringBefore(';').trim(),
                            encoding = charsetOf(type),
                        )
                    }
                } else if (code != HttpURLConnection.HTTP_OK) {
                    // Not an error worth shouting about — a lapsed session is
                    // ordinary — but it must not be silent either, because
                    // "the prefetch never helps" looks identical to "the
                    // prefetch is not wired up".
                    Log.w(TAG, "not caching /auth/me: HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "prefetch failed (${e.javaClass.simpleName}); the WebView will fetch it")
        }
        // ALWAYS, including every failure path: a take() blocked on this latch
        // must not sit out its timeout waiting for a fetch that already gave up.
        result.set(outcome)
        done.countDown()
    }

    /**
     * The prefetched `/auth/me` for [url], or null to let the WebView fetch it.
     *
     * [liveToken] is the access token the WebView will authenticate with right
     * now; a response fetched under any other token is discarded rather than
     * served. Runs on the WebView's IO thread, so blocking is allowed — waiting
     * on an in-flight prefetch beats returning null and starting a second
     * request on a fresh connection.
     */
    fun take(url: String, liveToken: String?): WebResourceResponse? {
        if (!started.get() || target != url) return null
        if (liveToken.isNullOrBlank()) return null
        try {
            if (!done.await(WAIT_MS, TimeUnit.MILLISECONDS)) return null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        // SINGLE USE. A retained WebView calls /auth/me again on later checks
        // (the `online` listener re-verifies); only the first may come from here.
        val hit = result.getAndSet(null) ?: return null
        if (!mayServe(hit.url, url, hit.tokenFp, liveToken, hit.atMs, System.currentTimeMillis())) {
            Log.w(TAG, "discarding the prefetch: it is not the answer to this request")
            return null
        }
        Log.w(TAG, "served /auth/me from the prefetch (${hit.body.size}B)")
        return WebResourceResponse(
            hit.mime,
            hit.encoding,
            200,
            "OK",
            // Mirrors what the origin sent. `no-cache` matters: without it the
            // WebView may keep this synthetic 200 and answer a LATER /auth/me
            // from it, which would outlive the single-use rule above.
            mapOf(
                "Cache-Control" to "no-cache",
                "X-Content-Type-Options" to "nosniff",
            ),
            ByteArrayInputStream(hit.body),
        )
    }

    /**
     * May the prefetched response answer this request? PURE, so the rule that
     * keeps one user's session off another user's request is testable without a
     * network, a WebView or a device — see `AuthMePrefetchPolicyTest`.
     *
     * Every clause is a refusal; the only way through is a request for the same
     * URL, under the same token, inside the freshness window.
     */
    internal fun mayServe(
        storedUrl: String,
        requestUrl: String,
        storedTokenFp: String,
        liveToken: String?,
        fetchedAtMs: Long,
        nowMs: Long,
    ): Boolean {
        if (liveToken.isNullOrBlank()) return false
        if (storedUrl != requestUrl) return false
        // A clock that moved BACKWARDS makes age arithmetic meaningless, and
        // `now - fetched` would be negative and sail under the ceiling. Refuse
        // rather than serve on an age we cannot reason about.
        if (nowMs < fetchedAtMs) return false
        if (nowMs - fetchedAtMs > MAX_AGE_MS) return false
        // THE IDENTITY CHECK. A different token means a different user, or a
        // refresh landed in between; either way this body is not the answer to
        // THIS request.
        return storedTokenFp == fingerprint(liveToken)
    }

    /** SHA-256 prefix of a token — enough to compare, not the token itself. */
    internal fun fingerprint(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }

    /** Read at most [limit] bytes; null when the body is bigger than that. */
    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            if (out.size() + n > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun charsetOf(contentType: String): String =
        contentType.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?: "utf-8"
}
