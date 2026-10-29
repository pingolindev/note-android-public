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

import android.util.Log
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.Security
import javax.net.ssl.ManagerFactoryParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.TrustManagerFactorySpi
import javax.net.ssl.X509TrustManager

/**
 * This process's DEFAULT trust, refusing [CompromisedKeys].
 *
 * A JCA provider at position 1 whose TrustManagerFactory is the platform's own (AndroidNSSP: Network
 * Security Config's), asked for by name with the same init, every X509TrustManager it gives wrapped in
 * [CompromisedKeyTrustManager]. Whatever asks JCA for the default trust then refuses a path through a
 * leaked emSign key. Above all the editor's WebView: its network stack builds its trust manager from
 * TrustManagerFactory.getDefaultAlgorithm() inside this process, so the page's API and wss calls and the
 * account frame are all refused, the way OkHttp's are, with no list of roots to keep in step with
 * Cloudflare. The OkHttp clients keep their own wrapper on the platform factory by name, so the two never
 * stack, and neither depends on the other.
 */
class CompromisedKeyTrustProvider private constructor() :
    Provider(NAME, 1.0, "Platform trust, refusing paths through compromised keys") {

    init {
        // The names AndroidNSSP registers (android.security.net.config.NetworkSecurityConfigProvider):
        // PKIX, and the X509 alias. The service makes the factory itself, never from its class name: a
        // factory R8 renamed would have JCA pass quietly on to AndroidNSSP, leaving the WebView without
        // the check.
        putService(
            object : Provider.Service(this, "TrustManagerFactory", "PKIX", Factory::class.java.name, listOf("X509"), null) {
                override fun newInstance(constructorParameter: Any?): Any = try {
                    Factory()
                } catch (e: GeneralSecurityException) {
                    // JCA's own way to say "not here": it then asks the next provider, and isDefault says no.
                    throw NoSuchAlgorithmException(e)
                }
            },
        )
    }

    /** The platform's factory, initialised as asked, each X509TrustManager it gives wrapped; [platform] is for tests. */
    class Factory internal constructor(private val platform: TrustManagerFactory) : TrustManagerFactorySpi() {
        constructor() : this(TrustManagerFactory.getInstance("PKIX", CompromisedKeyTrustManager.PLATFORM_PROVIDER))

        public override fun engineInit(ks: KeyStore?) = platform.init(ks)

        public override fun engineInit(spec: ManagerFactoryParameters?) = platform.init(spec)

        public override fun engineGetTrustManagers(): Array<TrustManager> = wrapped(platform.trustManagers, blocked)
    }

    companion object {
        const val NAME = "PingolinCompromisedKeys"
        private const val TAG = "CompromisedKeyTrust"

        @Volatile
        private var blocked: Set<String> = CompromisedKeys.SPKI_SHA256

        /** [managers] with each X509TrustManager refusing paths through one of [blocked]; anything else as it is. */
        internal fun wrapped(managers: Array<TrustManager>, blocked: Set<String>): Array<TrustManager> =
            managers.map { if (it is X509TrustManager) CompromisedKeyTrustManager.wrapping(it, blocked) else it }.toTypedArray()

        /**
         * Puts this first, ahead of AndroidNSSP: from Application.attachBaseContext, before anything in the
         * process asks for a trust manager. A debug build also refuses the test-only keys
         * ([CompromisedKeyTrustManager.blocked]). Not first means the default isn't ours, and
         * WebAppKeyHandoff then hands the page no key ([isDefault]).
         */
        fun install(noBackupDir: File?, debug: Boolean) {
            blocked = CompromisedKeyTrustManager.blocked(noBackupDir, debug)
            if (Security.getProvider(NAME) != null) return
            val at = Security.insertProviderAt(CompromisedKeyTrustProvider(), 1)
            if (at != 1) Log.e(TAG, "Installed at $at, not first: the default trust isn't ours")
        }

        /**
         * Whether the default trust is still this provider's, every X509 manager it gives the wrapper:
         * WebAppKeyHandoff hands over nothing otherwise.
         */
        fun isDefault(): Boolean = runCatching { isOurs(TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())) }.getOrDefault(false)

        internal fun isOurs(factory: TrustManagerFactory): Boolean {
            factory.init(null as KeyStore?)
            val managers = factory.trustManagers.filterIsInstance<X509TrustManager>()
            return factory.provider.name == NAME && managers.isNotEmpty() && managers.all { it is CompromisedKeyTrustManager }
        }
    }
}
