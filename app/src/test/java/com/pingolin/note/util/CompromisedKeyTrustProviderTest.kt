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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.Provider
import java.security.cert.X509Certificate
import javax.net.ssl.ManagerFactoryParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.TrustManagerFactorySpi
import javax.net.ssl.X509TrustManager

/**
 * The process's default trust refuses paths through compromised keys: a provider at position 1 whose
 * TrustManagerFactory is AndroidNSSP's, by name, its X509 managers wrapped. The editor's WebView takes
 * the default, so its calls are refused as OkHttp's are; while the default isn't ours, WebAppKeyHandoff
 * hands the page no key (WebAppKeyHandoffTest).
 *
 * Nothing here installs the provider in the test JVM: AndroidNSSP is Android's alone.
 */
class CompromisedKeyTrustProviderTest {

    private val provider = CompromisedKeyTrustProvider::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    @Test
    fun `it registers the factory under AndroidNSSP's own names, so the default algorithm finds it first`() {
        assertEquals(CompromisedKeyTrustProvider.NAME, provider.name)
        val service = provider.getService("TrustManagerFactory", "PKIX")!!
        assertEquals(CompromisedKeyTrustProvider.Factory::class.java.name, service.className)
        // The alias is the same service: X509, as Android's own provider has it.
        assertSame(service, provider.getService("TrustManagerFactory", "X509"))
        // It makes the factory itself, never from a class name R8 may have renamed.
        assertNotEquals(Provider.Service::class.java, service.javaClass)
        assertEquals(service.javaClass, service.javaClass.getMethod("newInstance", Any::class.java).declaringClass)
    }

    /** An X509 trust manager standing in for the platform's, and one that isn't X509. */
    private class Plain : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private object NotX509 : TrustManager

    /** The platform's factory, recording how it's initialised. */
    private class Recording(private val managers: Array<TrustManager>) : TrustManagerFactorySpi() {
        val inits = mutableListOf<Any?>()
        override fun engineInit(ks: KeyStore?) { inits += ks }
        override fun engineInit(spec: ManagerFactoryParameters?) { inits += spec }
        override fun engineGetTrustManagers(): Array<TrustManager> = managers
    }

    private fun platformFactory(spi: Recording) = object : TrustManagerFactory(spi, object : Provider("Platform", 1.0, "test") {}, "PKIX") {}

    @Test
    fun `its factory is the platform's, initialised as asked, each X509 manager it gives wrapped`() {
        val plain = Plain()
        val spi = Recording(arrayOf(plain, NotX509))
        val factory = CompromisedKeyTrustProvider.Factory(platformFactory(spi))
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        val spec = object : ManagerFactoryParameters {}

        factory.engineInit(null as KeyStore?)
        factory.engineInit(keyStore)
        factory.engineInit(spec)
        val managers = factory.engineGetTrustManagers()

        assertEquals(listOf(null, keyStore, spec), spi.inits)
        assertEquals(2, managers.size)
        assertTrue(managers[0] is CompromisedKeyTrustManager)
        assertSame(NotX509, managers[1])
    }

    @Test
    fun `what JCA makes asks for AndroidNSSP by name, never the default, which would be itself`() {
        // The test JVM has no AndroidNSSP: asked by name, it isn't found, rather than falling back to anything else.
        val missing = assertThrows(NoSuchProviderException::class.java) { CompromisedKeyTrustProvider.Factory() }
        assertTrue(missing.message!!.contains(CompromisedKeyTrustManager.PLATFORM_PROVIDER))
        // Through JCA it is the "not here" JCA expects, so the next provider is asked, and isDefault says no.
        val viaJca = assertThrows(NoSuchAlgorithmException::class.java) { provider.getService("TrustManagerFactory", "PKIX")!!.newInstance(null) }
        assertTrue(viaJca.cause is NoSuchProviderException)
    }

    private val main = File("src/main/java/com/pingolin/note")

    @Test
    fun `it goes in first from attachBaseContext, with the test-only keys in debug builds only`() {
        val app = File(main, "NoteApplication.kt").readText()
        val attach = app.substring(app.indexOf("override fun attachBaseContext(base: Context) {"))
            .substringBefore("\n    }\n")
        assertTrue(attach.contains("super.attachBaseContext(base)"))
        assertTrue(attach.contains("CompromisedKeyTrustProvider.install(base.noBackupFilesDir, debug = BuildConfig.DEBUG)"))
        val provider = File(main, "util/CompromisedKeyTrustProvider.kt").readText()
        assertTrue(provider.contains("val at = Security.insertProviderAt(CompromisedKeyTrustProvider(), 1)"))
        assertTrue(provider.contains("blocked = CompromisedKeyTrustManager.blocked(noBackupDir, debug)"))
    }

    private fun named(name: String) = object : Provider(name, 1.0, "test") {}

    @Test
    fun `the default is ours only when it is this provider's, every X509 manager it gives the wrapper`() {
        val wrapper = CompromisedKeyTrustManager(Plain(), { chain, _, _ -> chain.toList() }, emptySet())
        fun factory(provider: Provider, vararg managers: TrustManager) =
            object : TrustManagerFactory(Recording(arrayOf(*managers)), provider, "PKIX") {}
        val ours = named(CompromisedKeyTrustProvider.NAME)

        assertTrue(CompromisedKeyTrustProvider.isOurs(factory(ours, wrapper)))
        assertTrue(CompromisedKeyTrustProvider.isOurs(factory(ours, wrapper, NotX509)))
        assertFalse(CompromisedKeyTrustProvider.isOurs(factory(ours, Plain())))
        assertFalse(CompromisedKeyTrustProvider.isOurs(factory(ours, wrapper, Plain())))
        assertFalse(CompromisedKeyTrustProvider.isOurs(factory(ours)))
        assertFalse(CompromisedKeyTrustProvider.isOurs(factory(named(CompromisedKeyTrustManager.PLATFORM_PROVIDER), wrapper)))
    }

    @Test
    fun `whether the default is ours is asked of the default factory itself`() {
        val provider = File(main, "util/CompromisedKeyTrustProvider.kt").readText()
        assertTrue(provider.contains("isOurs(TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()))"))
        // In this JVM nothing installed it: the default isn't ours, so no key would go to a page.
        assertFalse(CompromisedKeyTrustProvider.isDefault())
    }

    /** A provider put in front of ours would answer for the default instead. */
    @Test
    fun `nothing else in the app puts a provider in, so nothing goes in front of it`() {
        val inserting = listOf("main", "gms", "libre").map { File("src/$it/java") }.filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.extension == "kt" || f.extension == "java" }.toList() }
            .filter { Regex("""insertProviderAt|addProvider|ProviderInstaller""").containsMatchIn(it.readText()) }
            .map { it.name }
        assertEquals(listOf("CompromisedKeyTrustProvider.kt"), inserting)
    }
}
