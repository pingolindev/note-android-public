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

/**
 * Builds the document-start script that hands this device's already-unlocked
 * E2EE keys to the web app, so opening the web editor does not ask the user for
 * their password again.
 *
 * ## Why this is not a new exposure
 *
 * The web app **already** keeps all five of these values in `sessionStorage`
 * during normal browser use — `crypto.ts persistToSessionStorage()` writes them
 * after any successful unlock, and `restoreFromSession()` reads them straight
 * back. This handoff puts the same values in the same place; it only changes
 * how they got there (from the native session rather than from a password the
 * user retyped).
 *
 * ## What it must never become
 *
 * A **push, never a pull.** No `@JavascriptInterface` is exposed, so the page
 * has nothing it can call to *ask* for key material. The script writes and
 * returns. It is injected at document start against an explicit single-origin
 * allowlist — never a wildcard — AND it checks that origin itself, first,
 * where it runs: the resume path (WebAppActivity.refreshKeyHandoffInPlace)
 * goes through evaluateJavascript, which has no allowlist and runs in
 * whatever the main frame shows, the account portal or any page a link led
 * to. Before the check was in the script, every resume handed the keys to
 * that page. `location` and its `origin` can't be redefined by a page, so the
 * check can't be faked from inside it.
 *
 * ## The value contract, verified against both sides
 *
 * | sessionStorage key | value |
 * |---|---|
 * | `pingolin_note_session_key`  | note vault key, `BLAKE2b(sharingPriv, key="pingolin-note-vault", 32)` |
 * | `pingolin_note_sharing_priv` | X25519 sharing private key |
 * | `pingolin_note_sharing_pub`  | X25519 sharing public key |
 * | `pingolin_note_sign_secret`  | Ed25519 **64-byte** secret: `seed || publicKey` |
 * | `pingolin_note_sign_public`  | Ed25519 public key |
 *
 * All base64, libsodium `base64_variants.ORIGINAL` — which is standard base64
 * WITH padding, i.e. exactly Android's `Base64.NO_WRAP`.
 *
 * ⚠️ The signing keys must be the account's **real Ed25519 seed**, not derived
 * from the sharing scalar. If they are omitted the web app falls back to
 * `installSigningKeyFromSharingKey()`, which seeds Ed25519 from the X25519
 * scalar — and "a signature made with the sharing scalar never verifies
 * against" the directory's published `signing_public_key`, so every share this
 * device sent would be dropped by its recipients.
 */
object WebAppKeyHandoff {

    const val SESSION_KEY = "pingolin_note_session_key"
    const val SESSION_SHARING_PRIV = "pingolin_note_sharing_priv"
    const val SESSION_SHARING_PUB = "pingolin_note_sharing_pub"
    const val SESSION_SIGN_SECRET = "pingolin_note_sign_secret"
    const val SESSION_SIGN_PUBLIC = "pingolin_note_sign_public"

    /**
     * The keys to hand over. Signing values are nullable: an account that was
     * never provisioned with an Ed25519 seed has none, and handing over a
     * wrong one is worse than handing over none.
     */
    data class Keys(
        val vaultKeyB64: String,
        val sharingPrivB64: String,
        val sharingPubB64: String,
        val signSecretB64: String?,
        val signPublicB64: String?,
    )

    /**
     * Valid base64 cannot contain a quote, a backslash or a newline, so rather
     * than escape these into a JS string literal we refuse them outright. An
     * unexpected character means something upstream is wrong, and building a
     * script out of it is how an injection vulnerability starts.
     */
    private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

    private fun valid(value: String?): Boolean = value != null && BASE64.matches(value)

    /**
     * An origin as WebAppActivity.originOf gives it: a scheme, `://`, a host,
     * an optional port. Nothing in it needs escaping, so, as with the keys,
     * anything else is refused rather than escaped into the script.
     */
    private val ORIGIN = Regex("^https?://[A-Za-z0-9.-]+(:[0-9]+)?$")

    /**
     * Returns the script, or null when there is nothing safe to hand over — no
     * keys, or [trustIsOurs] false — in which case the web app boots as it would
     * in any browser and asks for the password. Never returns a partial key set:
     * a vault key without its sharing key would decrypt notes but silently fail
     * to open anything shared with this user. The script does nothing at all in
     * a document of any origin but [origin] (the note origin); [notifyPage]
     * calls the page's `__pingolinKeysUpdated` after the writes.
     */
    fun buildScript(keys: Keys?, trustIsOurs: Boolean, origin: String, notifyPage: Boolean = false): String? {
        // The page reaches our hosts through the WebView's own network stack: no key goes to it unless that
        // stack's trust is still ours, refusing paths through compromised keys (CompromisedKeyTrustProvider).
        if (!trustIsOurs) return null
        if (keys == null) return null
        if (!ORIGIN.matches(origin)) return null
        if (!valid(keys.vaultKeyB64) || !valid(keys.sharingPrivB64) || !valid(keys.sharingPubB64)) {
            return null
        }

        val sets = StringBuilder()
        sets.append(setItem(SESSION_KEY, keys.vaultKeyB64))
        sets.append(setItem(SESSION_SHARING_PRIV, keys.sharingPrivB64))
        sets.append(setItem(SESSION_SHARING_PUB, keys.sharingPubB64))
        // Both signing halves or neither — a secret without its public key
        // leaves the web app unable to publish what it is signing with.
        if (valid(keys.signSecretB64) && valid(keys.signPublicB64)) {
            sets.append(setItem(SESSION_SIGN_SECRET, keys.signSecretB64!!))
            sets.append(setItem(SESSION_SIGN_PUBLIC, keys.signPublicB64!!))
        }

        // Swallow failures: sessionStorage is unavailable in some WebView
        // configurations, and a thrown exception at document start would take
        // the whole page down rather than degrade to the password prompt.
        // The page's hook, when asked for ([notifyPage]: a resume), is called
        // inside the origin check too: another origin is told nothing.
        val notify = if (notifyPage) "window.__pingolinKeysUpdated&&window.__pingolinKeysUpdated();" else ""
        return "(function(){if(location.origin!=='$origin')return;" +
            "try{var s=window.sessionStorage;$sets}catch(e){}$notify})();"
    }

    private fun setItem(key: String, value: String): String = "s.setItem('$key','$value');"
}
