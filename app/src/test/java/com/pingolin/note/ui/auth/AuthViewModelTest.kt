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

package com.pingolin.note.ui.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.pingolin.note.data.remote.api.AuthApi
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.domain.repository.LocalDataWiper
import com.pingolin.note.domain.repository.SyncRepository
import com.pingolin.note.util.AttestationManager
import com.pingolin.note.util.AuthRateLimiter
import com.pingolin.note.util.NoteGenFloorStore
import com.pingolin.note.util.PendingPushUnregisterStore
import com.pingolin.note.util.PushNotificationManager
import com.pingolin.note.util.SenderKeyPinStore
import com.pingolin.note.util.SessionManager
import com.pingolin.note.worker.SyncWorker
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.pingolin_mobile.CryptoManager

/**
 * Account-switch flush: when a DIFFERENT account logs in on the
 * device, AuthViewModel flushes the OUTGOING account's unsynced edits before the
 * fail-closed wipe destroys them — but only when online. See
 * [AuthViewModel.flushOutgoingBeforeAccountSwitch].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @MockK(relaxed = true) private lateinit var appContext: Context
    @MockK(relaxed = true) private lateinit var authApi: AuthApi
    @MockK(relaxed = true) private lateinit var noteApi: NoteApi
    @MockK(relaxed = true) private lateinit var sessionManager: SessionManager
    @MockK(relaxed = true) private lateinit var pushNotificationManager: PushNotificationManager
    @MockK(relaxed = true) private lateinit var cryptoManager: CryptoManager
    @MockK(relaxed = true) private lateinit var rateLimiter: AuthRateLimiter
    @MockK(relaxed = true) private lateinit var attestationManager: AttestationManager
    @MockK(relaxed = true) private lateinit var localDataWiper: LocalDataWiper
    @MockK(relaxed = true) private lateinit var syncRepository: SyncRepository
    @MockK(relaxed = true) private lateinit var senderKeyPinStore: SenderKeyPinStore
    @MockK(relaxed = true) private lateinit var noteGenFloorStore: NoteGenFloorStore
    @MockK(relaxed = true) private lateinit var pendingPushUnregisterStore: PendingPushUnregisterStore
    @MockK(relaxed = true) private lateinit var accountTrustTidy: com.pingolin.note.util.AccountTrustTidy

    private lateinit var vm: AuthViewModel

    /** The account the replies name: a UUID, as the auth server always
     * sends.
     */
    private val U1 = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)
        // cancelAllSync talks to WorkManager (uninitialized in a JVM test); stub it out.
        mockkObject(SyncWorker.Companion)
        every { SyncWorker.cancelAllSync(any()) } just Runs

        vm = AuthViewModel(
            appContext, authApi, noteApi, sessionManager, pushNotificationManager,
            cryptoManager, rateLimiter, attestationManager,
            localDataWiper, syncRepository, senderKeyPinStore, noteGenFloorStore,
            pendingPushUnregisterStore, accountTrustTidy,
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    private fun stubConnectivity(online: Boolean) {
        val cm = mockk<ConnectivityManager>()
        every { appContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        if (online) {
            val net = mockk<Network>()
            val caps = mockk<NetworkCapabilities>()
            every { cm.activeNetwork } returns net
            every { cm.getNetworkCapabilities(net) } returns caps
            every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
            every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
        } else {
            every { cm.activeNetwork } returns null
            every { cm.getNetworkCapabilities(any()) } returns null
        }
    }

    @Test
    fun `accountSwitchFlush_online_pushesOutgoingPendingChanges`() = runTest {
        stubConnectivity(online = true)
        coEvery { syncRepository.pushPendingChanges() } returns 2

        vm.flushOutgoingBeforeAccountSwitch()

        // Online: the departing account's unsynced edits are flushed before the wipe.
        coVerify(exactly = 1) { syncRepository.pushPendingChanges() }
        // Background sync is cancelled first so the flush is the sole pusher.
        verify { SyncWorker.cancelAllSync(appContext) }
    }

    @Test
    fun `accountSwitchFlush_offline_skipsPush`() = runTest {
        stubConnectivity(online = false)

        vm.flushOutgoingBeforeAccountSwitch()

        // Offline: no network to flush to — fall straight through to the wipe.
        coVerify(exactly = 0) { syncRepository.pushPendingChanges() }
    }

    @Test
    fun `accountSwitchFlush_online_flushFailure_isSwallowed`() = runTest {
        stubConnectivity(online = true)
        coEvery { syncRepository.pushPendingChanges() } throws RuntimeException("network")

        // Best-effort: a flush failure must not propagate (the wipe still has to run).
        vm.flushOutgoingBeforeAccountSwitch()

        coVerify(exactly = 1) { syncRepository.pushPendingChanges() }
    }

    // --- No keys, no session
    // --------------------------------------------------------

    private fun step2(): com.pingolin.note.data.remote.dto.SrpStep2Response =
        com.pingolin.note.data.remote.dto.SrpStep2Response(
            serverProof = "",
            user = com.pingolin.note.data.remote.dto.UserResponse(id = U1, email = "a@b.c"),
            accessToken = "access",
            refreshToken = "refresh",
            // No key blobs in the response: the bootstrap falls back to what is
            // persisted — which, for these tests, the session manager decides.
            kdfParams = null,
            encryptedKeys = null,
        )

    @Test
    fun `signIn_withoutVaultOrSharingKey_signsNothingIn_insteadOfReportingSuccess`() = runTest {
        every { sessionManager.getUserId() } returns U1
        every { sessionManager.getMasterKey() } returns null
        every { sessionManager.getSharingPrivateKey() } returns "c2hhcmluZw=="

        vm.bootstrapSession("a@b.c", "pw", step2())

        assertEquals(AuthUiState.KeysUnavailable, vm.uiState.value)
        // Refused at the gate: no token was ever saved, so there is nothing to roll
        // back.
        verify(exactly = 0) { sessionManager.saveTokens(any(), any()) }

        // The sharing key alone missing is just as fatal.
        every { sessionManager.getMasterKey() } returns "dmF1bHQ="
        every { sessionManager.getSharingPrivateKey() } returns null
        vm.bootstrapSession("a@b.c", "pw", step2())
        assertEquals(AuthUiState.KeysUnavailable, vm.uiState.value)
    }

    @Test
    fun `signIn_withBothKeys_succeeds_andKeepsTheSession`() = runTest {
        every { sessionManager.getUserId() } returns U1
        every { sessionManager.getMasterKey() } returns "dmF1bHQ="
        every { sessionManager.getSharingPrivateKey() } returns "c2hhcmluZw=="

        vm.bootstrapSession("a@b.c", "pw", step2())

        assertEquals(AuthUiState.Success, vm.uiState.value)
        verify(exactly = 0) { sessionManager.clearAuthOnly() }
    }

    // --- The account id is canonical, and one that isn't a UUID signs nobody in ---

    private fun step2Naming(user: com.pingolin.note.data.remote.dto.UserResponse?) = step2().copy(user = user)

    private fun keysPresent() {
        every { sessionManager.getMasterKey() } returns "dmF1bHQ="
        every { sessionManager.getSharingPrivateKey() } returns "c2hhcmluZw=="
    }

    @Test
    fun `a reply naming no account, or one that isn't a UUID, signs nobody in and changes nothing`() = runTest {
        stubConnectivity(online = true)
        keysPresent()
        every { sessionManager.getUserId() } returns "00000000-0000-4000-8000-0000000000ff"
        for (user in listOf(
            null,
            com.pingolin.note.data.remote.dto.UserResponse(id = "u1", email = "a@b.c"),
            com.pingolin.note.data.remote.dto.UserResponse(id = " $U1", email = "a@b.c"),
            com.pingolin.note.data.remote.dto.UserResponse(id = "URN:UUID:$U1", email = "a@b.c"),
        )) {
            vm.bootstrapSession("a@b.c", "pw", step2Naming(user))
            assertEquals("$user", AuthUiState.Error(AuthViewModel.COULD_NOT_SIGN_IN), vm.uiState.value)
        }
        verify(exactly = 0) { sessionManager.saveTokens(any(), any()) }
        verify(exactly = 0) { sessionManager.saveUserId(any()) }
        coVerify(exactly = 0) { localDataWiper.wipeBlocking() }
        coVerify(exactly = 0) { syncRepository.pushPendingChanges() }
    }

    @Test
    fun `the account id is saved in its canonical form`() = runTest {
        keysPresent()
        for (spelling in listOf(U1.uppercase(), U1.replace("-", ""), "{$U1}", "urn:uuid:$U1")) {
            every { sessionManager.getUserId() } returns U1
            vm.bootstrapSession("a@b.c", "pw", step2Naming(com.pingolin.note.data.remote.dto.UserResponse(id = spelling, email = "a@b.c")))
            assertEquals(spelling, AuthUiState.Success, vm.uiState.value)
        }
        verify(exactly = 4) { sessionManager.saveUserId(U1) }
        verify(exactly = 4) { senderKeyPinStore.switchUser(U1) }
        verify(exactly = 4) { noteGenFloorStore.switchUser(U1) }
        // The same account under another spelling is not another account.
        coVerify(exactly = 0) { localDataWiper.wipeBlocking() }
    }

    @Test
    fun `a saved id that isn't a UUID counts as another account`() = runTest {
        stubConnectivity(online = true)
        keysPresent()
        keysOpen()
        every { sessionManager.getUserId() } returns null
        every { sessionManager.savedAccountIdInvalid() } returns true
        coEvery { syncRepository.hasUnsyncedChanges() } returns false

        vm.bootstrapSession("a@b.c", "pw", step2WithKeys())

        coVerify(exactly = 1) { localDataWiper.wipeBlocking() }
        assertEquals(AuthUiState.Success, vm.uiState.value)
    }

    @Test
    fun `a two-step sign-in whose reply names no UUID account signs nobody in`() = runTest {
        for (id in listOf("u1", "", "{${U1.replace("-", "")}}")) {
            challenged()
            codeStepAnswers(
                retrofit2.Response.success(
                    com.pingolin.note.data.remote.dto.ApiResponse(
                        success = true,
                        data = com.pingolin.note.data.remote.dto.TwoFactorVerifyResponse(
                            accessToken = "access", refreshToken = "refresh",
                            user = com.pingolin.note.data.remote.dto.UserResponse(id = id, email = "a@b.c"),
                        ),
                    ),
                ),
            )
            keysPresent()
            assertEquals(id, AuthUiState.Error(AuthViewModel.COULD_NOT_SIGN_IN), afterCode("123456"))
        }
        verify(exactly = 0) { sessionManager.saveTokens(any(), any()) }
        verify(exactly = 0) { sessionManager.saveUserId(any()) }
    }

    @Test
    fun `a deferred push delete armed under another spelling of this account is disarmed`() = runTest {
        keysPresent()
        every { sessionManager.getUserId() } returns U1
        every { pendingPushUnregisterStore.get() } returns
            PendingPushUnregisterStore.Pending(deviceId = "d", token = "t", lane = "fcm", userId = U1.uppercase())
        vm.bootstrapSession("a@b.c", "pw", step2())

        verify(exactly = 1) { pendingPushUnregisterStore.clear() }
    }

    // --- Prove, then wipe, then save
    // --------------------------------------------------

    /** Another account, whose rows and keys this device holds. */
    private val U0 = "00000000-0000-4000-8000-0000000000ff"

    private fun blob(name: String) = com.pingolin.note.data.remote.dto.EncryptedBlobResponse(nonce = "n-$name", ciphertext = "c-$name", keyId = name)

    /** A reply carrying this account's key blobs. */
    private fun step2WithKeys() = step2().copy(
        kdfParams = com.pingolin.note.data.remote.dto.KdfParamsResponse(salt = "salt", memoryKib = 8, iterations = 1, parallelism = 1),
        encryptedKeys = com.pingolin.note.data.remote.dto.EncryptedKeysResponse(
            masterKey = blob("master"), sharingPrivateKey = blob("sharing"), signingPrivateKey = blob("signing"),
        ),
    )

    private val sharingKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })

    /** The password opens the blobs: a master key, and a 32-byte sharing key and seed. */
    private fun keysOpen() {
        every { cryptoManager.decryptWithPassword(any(), any(), any()) } returns "bWFzdGVy"
        every { cryptoManager.decryptWithKey(any(), any()) } returns sharingKey
    }

    /** The password opens nothing. */
    private fun keysDoNotOpen() {
        every { cryptoManager.decryptWithPassword(any(), any(), any()) } throws RuntimeException("decryption failed")
    }

    private fun holdingAnotherAccount() {
        stubConnectivity(online = true)
        every { sessionManager.getUserId() } returns U0
        keysPresent()
        coEvery { syncRepository.hasUnsyncedChanges() } returns false
    }

    @Test
    fun `an account switch whose keys don't open wipes nothing, flushes nothing and saves nothing`() = runTest {
        holdingAnotherAccount()
        keysDoNotOpen()

        vm.bootstrapSession("a@b.c", "pw", step2WithKeys())

        assertEquals(AuthUiState.KeysUnavailable, vm.uiState.value)
        coVerify(exactly = 0) { localDataWiper.wipeBlocking() }
        coVerify(exactly = 0) { syncRepository.pushPendingChanges() }
        verify(exactly = 0) { sessionManager.clearPushSettings() }
        coVerify(exactly = 0) { noteApi.unregisterFcmDevice(any()) }
        verify(exactly = 0) { sessionManager.saveTokens(any(), any()) }
        verify(exactly = 0) { sessionManager.saveUserId(any()) }
        verify(exactly = 0) { sessionManager.saveMasterKey(any()) }
        verify(exactly = 0) { sessionManager.saveSharingPrivateKey(any()) }
        verify(exactly = 0) { cryptoManager.importPrivateKey(any()) }
        verify(exactly = 0) { senderKeyPinStore.switchUser(any()) }
        verify(exactly = 0) { sessionManager.clearAuthOnly() }

        // A reply with no key blobs is no different for another account.
        vm.bootstrapSession("a@b.c", "pw", step2())
        assertEquals(AuthUiState.KeysUnavailable, vm.uiState.value)
        coVerify(exactly = 0) { localDataWiper.wipeBlocking() }
        verify(exactly = 0) { sessionManager.saveTokens(any(), any()) }
    }

    @Test
    fun `an account switch whose keys open wipes first, then saves the session and the keys`() = runTest {
        holdingAnotherAccount()
        keysOpen()

        vm.bootstrapSession("a@b.c", "pw", step2WithKeys())

        assertEquals(AuthUiState.Success, vm.uiState.value)
        coVerifyOrder {
            cryptoManager.decryptWithPassword(any(), "pw", any())
            localDataWiper.wipeBlocking()
            sessionManager.saveTokens("access", "refresh")
            sessionManager.saveUserId(U1)
            cryptoManager.importPrivateKey(sharingKey)
            sessionManager.saveSharingPrivateKey(sharingKey)
            sessionManager.saveMasterKey(any())
            cryptoManager.persistKeys()
        }
    }

    @Test
    fun `a switch the residual-dirt guard stops saves nothing of the incoming account`() = runTest {
        holdingAnotherAccount()
        keysOpen()
        // Online, so only the post-flush guard asks: it finds edits left over.
        coEvery { syncRepository.hasUnsyncedChanges() } returns true

        vm.bootstrapSession("a@b.c", "pw", step2WithKeys())

        assertTrue(vm.uiState.value is AuthUiState.Error)
        coVerify(exactly = 0) { localDataWiper.wipeBlocking() }
        verify(exactly = 0) { sessionManager.saveTokens(any(), any()) }
        verify(exactly = 0) { cryptoManager.importPrivateKey(any()) }
    }

    @Test
    fun `a switch whose keys open but won't save rolls the session back`() = runTest {
        holdingAnotherAccount()
        keysOpen()
        every { cryptoManager.importPrivateKey(any()) } throws RuntimeException("keystore said no")
        // The wipe took the previous account's keys; nothing new was saved.
        every { sessionManager.getMasterKey() } returns null

        vm.bootstrapSession("a@b.c", "pw", step2WithKeys())

        assertEquals(AuthUiState.KeysUnavailable, vm.uiState.value)
        verify { sessionManager.clearAuthOnly() }
    }

    @Test
    fun `the same account whose keys don't open this time keeps the ones it holds`() = runTest {
        every { sessionManager.getUserId() } returns U1
        keysPresent()
        keysDoNotOpen()

        vm.bootstrapSession("a@b.c", "pw", step2WithKeys())

        assertEquals(AuthUiState.Success, vm.uiState.value)
        coVerify(exactly = 0) { localDataWiper.wipeBlocking() }
        verify(exactly = 0) { sessionManager.saveMasterKey(any()) }
        verify(exactly = 0) { sessionManager.saveSharingPrivateKey(any()) }
        verify { cryptoManager.loadPersistedKeys() }
        verify { sessionManager.saveTokens("access", "refresh") }
    }

    @Test
    fun `no saved id while rows are held is an account switch`() = runTest {
        stubConnectivity(online = true)
        every { sessionManager.getUserId() } returns null
        coEvery { syncRepository.hasAnyLocalRows() } returns true
        coEvery { syncRepository.hasUnsyncedChanges() } returns false
        keysPresent()

        // Whose rows those are, nothing says: this account's keys must open here…
        keysDoNotOpen()
        vm.bootstrapSession("a@b.c", "pw", step2WithKeys())
        assertEquals(AuthUiState.KeysUnavailable, vm.uiState.value)
        coVerify(exactly = 0) { localDataWiper.wipeBlocking() }
        verify(exactly = 0) { sessionManager.saveTokens(any(), any()) }

        // …and once they do, the rows go before anything of this account is saved.
        keysOpen()
        vm.bootstrapSession("a@b.c", "pw", step2WithKeys())
        assertEquals(AuthUiState.Success, vm.uiState.value)
        coVerifyOrder {
            localDataWiper.wipeBlocking()
            sessionManager.saveTokens("access", "refresh")
        }
    }

    @Test
    fun `a first sign-in whose keys don't open saves nothing, and one whose keys open wipes nothing`() = runTest {
        every { sessionManager.getUserId() } returns null
        coEvery { syncRepository.hasAnyLocalRows() } returns false

        keysDoNotOpen()
        vm.bootstrapSession("a@b.c", "pw", step2WithKeys())
        assertEquals(AuthUiState.KeysUnavailable, vm.uiState.value)
        verify(exactly = 0) { sessionManager.saveTokens(any(), any()) }

        keysOpen()
        keysPresent()
        vm.bootstrapSession("a@b.c", "pw", step2WithKeys())
        assertEquals(AuthUiState.Success, vm.uiState.value)
        coVerify(exactly = 0) { localDataWiper.wipeBlocking() }
        verify { sessionManager.saveSharingPrivateKey(sharingKey) }
    }

    @Test
    fun `the keys open in memory to a 32-byte sharing key and its vault key, and the wipe zeroes them`() {
        keysOpen()
        val opened = vm.openKeys("pw", step2WithKeys())!!
        assertEquals(sharingKey, opened.sharingPrivateKey)
        assertTrue(opened.sharingPrivateKeyBytes.all { it == 3.toByte() })
        assertEquals(
            java.util.Base64.getEncoder().encodeToString(
                com.pingolin.note.util.Blake2b.hash(ByteArray(32) { 3 }, "pingolin-note-vault".toByteArray(), 32),
            ),
            opened.vaultKey,
        )
        assertTrue(opened.signingSeedBytes!!.all { it == 3.toByte() })
        opened.wipe()
        assertTrue(opened.sharingPrivateKeyBytes.all { it == 0.toByte() })
        assertTrue(opened.signingSeedBytes!!.all { it == 0.toByte() })

        // A sharing key that isn't 32 bytes, or no sharing blob at all, opens nothing.
        every { cryptoManager.decryptWithKey(any(), any()) } returns java.util.Base64.getEncoder().encodeToString(ByteArray(16))
        assertEquals(null, vm.openKeys("pw", step2WithKeys()))
        keysOpen()
        val noSharing = step2WithKeys().let { it.copy(encryptedKeys = it.encryptedKeys!!.copy(sharingPrivateKey = null)) }
        assertEquals(null, vm.openKeys("pw", noSharing))
        // Nothing was imported or saved by any of it.
        verify(exactly = 0) { cryptoManager.importPrivateKey(any()) }
        verify(exactly = 0) { sessionManager.saveSharingPrivateKey(any()) }
    }

    @Test
    fun `a sign-in that goes through tidies other accounts by the typed e-mail, and a refused one doesn't`() = runTest {
        holdingAnotherAccount()
        keysDoNotOpen()
        vm.bootstrapSession("Typed@X.y", "pw", step2WithKeys())
        assertEquals(AuthUiState.KeysUnavailable, vm.uiState.value)
        verify(exactly = 0) { accountTrustTidy.afterSignIn(any(), any()) }

        keysOpen()
        vm.bootstrapSession("Typed@X.y", "pw", step2WithKeys())
        assertEquals(AuthUiState.Success, vm.uiState.value)
        verify(exactly = 1) { accountTrustTidy.afterSignIn(U1, "Typed@X.y") }
    }

    /** The order, from the source: open → gate → wipe → save the session → save the keys,
     * and the opened keys' bytes zeroed on every path. */
    @Test
    fun `the sign-in opens, gates, wipes and saves in that order, and always zeroes what it opened`() {
        val source = java.io.File("src/main/java/com/pingolin/note/ui/auth/AuthViewModel.kt").readText()
        val start = source.indexOf("    internal suspend fun bootstrapSession(")
        val body = source.substring(start, source.indexOf("\n    }\n", start))
        fun at(needle: String): Int = body.indexOf(needle).also { assertTrue(needle, it >= 0) }
        val open = at("val opened = withContext(Dispatchers.Default) { openKeys(password, step2) }")
        val gate = at("if (opened == null && !(sameAccount && holdsKeys())) {")
        val wipe = at("localDataWiper.wipeBlocking()")
        val tokens = at("sessionManager.saveTokens(accessToken, step2.refreshToken ?: \"\")")
        val keys = at("runCatching { persistOpenedKeys(opened, step2.signingPublicKey) }")
        assertTrue(open < gate && gate < wipe && wipe < tokens && tokens < keys)
        assertTrue(body.contains("        } finally {\n            // Whatever path this took, the opened keys' byte copies are zeroed:\n            // the saved copies are the Keystore-backed ones.\n            opened?.wipe()\n        }"))
        // Nothing before the gate saves, imports or wipes anything.
        val beforeGate = body.substring(0, gate)
        for (write in listOf(
            "sessionManager.saveTokens", "sessionManager.saveEmail", "sessionManager.saveUserId",
            "sessionManager.saveMasterKey", "sessionManager.saveSharingPrivateKey", "sessionManager.saveSigningPrivateKey",
            "cryptoManager.import", "persistKeys", "wipeBlocking", "switchUser",
        )) {
            assertFalse(write, beforeGate.contains(write))
        }
    }

    // --- A passkey-only second step
    // -----------------------------------------------

    @Test
    fun `a challenge is answerable here only when it offers TOTP`() {
        assertTrue(AuthViewModel.canAnswerTwoFactor(listOf("totp")))
        assertTrue(AuthViewModel.canAnswerTwoFactor(listOf("totp", "passkey")))
        assertTrue(AuthViewModel.canAnswerTwoFactor(listOf("passkey", "TOTP")))
        assertFalse(AuthViewModel.canAnswerTwoFactor(listOf("passkey")))
        assertFalse(AuthViewModel.canAnswerTwoFactor(emptyList()))
        // An older server that lists no methods keeps the code box.
        assertTrue(AuthViewModel.canAnswerTwoFactor(null))
    }

    @Test
    fun `login_passkeyOnlyChallenge_explainsInsteadOfAskingForACode`() = runTest {
        // SrpClient encodes with android.util.Base64, which the JVM stubs to null.
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.decode(any<String>(), any<Int>()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        every { android.util.Base64.encodeToString(any<ByteArray>(), any<Int>()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        every { rateLimiter.checkAttempt(any()) } returns com.pingolin.note.util.RateLimitResult.Allowed
        // The server's proof checks out here; the refusal has its own
        // tests.
        mockkStatic("uniffi.pingolin_mobile.Pingolin_mobileKt")
        every { uniffi.pingolin_mobile.srpVerifyServerProof(any(), any(), any(), any(), any()) } returns true
        val b64 = { bytes: ByteArray -> java.util.Base64.getEncoder().encodeToString(bytes) }
        coEvery { authApi.srpStep1(any()) } returns retrofit2.Response.success(
            com.pingolin.note.data.remote.dto.ApiResponse(
                success = true,
                data = com.pingolin.note.data.remote.dto.SrpStep1Response(
                    salt = b64(ByteArray(16) { 1 }),
                    serverPublic = b64(byteArrayOf(2)),
                    sessionId = "s1",
                    bcryptSalt = null,
                ),
            ),
        )
        coEvery { authApi.srpStep2(any()) } returns retrofit2.Response.success(
            com.pingolin.note.data.remote.dto.ApiResponse(
                success = true,
                data = com.pingolin.note.data.remote.dto.SrpStep2Response(
                    serverProof = "AAAA",
                    user = null,
                    accessToken = null,
                    refreshToken = null,
                    kdfParams = null,
                    encryptedKeys = null,
                    requires2fa = true,
                    twoFactorToken = "tf-token",
                    availableMethods = listOf("passkey"),
                ),
            ),
        )

        vm.login("a@b.c", "pw")
        // The SRP maths runs on Dispatchers.Default: wait for the outcome
        // rather than read the Loading state the call returns with.
        val settled = vm.uiState.first { it != AuthUiState.Loading && it != AuthUiState.Idle }

        assertEquals(AuthUiState.TwoFactorPasskeyOnly, settled)
        // No challenge is held open: a code typed now has nothing to complete.
        vm.verifyTwoFactor("123456")
        coVerify(exactly = 0) { authApi.verify2fa(any()) }
    }

    // --- Sign-in never replaces or creates a signing key; a legacy shape is counted ---

    private val sharing = ByteArray(32) { (it + 1).toByte() }
    private val genuineSeed = ByteArray(32) { (100 + it).toByte() }

    @Test
    fun `the signing key action is decided from the opened keys`() {
        // The seed IS the sharing key: the legacy shape, only logged and counted.
        assertEquals(SigningKeyAction.LOG_LEGACY, AuthViewModel.signingKeyAction(sharing.copyOf(), sharing))
        // Everything else is kept as it is — a genuine seed, or nothing opened.
        assertEquals(SigningKeyAction.KEEP, AuthViewModel.signingKeyAction(genuineSeed, sharing))
        assertEquals(SigningKeyAction.KEEP, AuthViewModel.signingKeyAction(null, sharing))
        // There is no action that replaces a key.
        assertEquals(setOf(SigningKeyAction.LOG_LEGACY, SigningKeyAction.KEEP), SigningKeyAction.values().toSet())

        assertTrue(AuthViewModel.constantTimeEquals(sharing, sharing.copyOf()))
        assertFalse(AuthViewModel.constantTimeEquals(sharing, genuineSeed))
        assertFalse(AuthViewModel.constantTimeEquals(sharing, sharing.copyOf(31)))
    }

    @Test
    fun `the server's disagreement never rotates a genuine key`() {
        every { cryptoManager.signingPublicKeyHex() } returns "aa".repeat(32)
        // The server reports another key (hex and base64 forms), or none at all.
        vm.checkSigningKey("bb".repeat(32), genuineSeed, true, sharing)
        vm.checkSigningKey("BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=", genuineSeed, true, sharing)
        vm.checkSigningKey(null, genuineSeed, true, sharing)

        verify(exactly = 0) { cryptoManager.generateSigningKeypair() }
        verify(exactly = 0) { cryptoManager.importSigningPrivateKey(any()) }
    }

    @Test
    fun `a legacy-shaped key never rotates, it is counted`() {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        val editor = mockk<android.content.SharedPreferences.Editor>(relaxed = true)
        every { appContext.getSharedPreferences(AuthViewModel.SIGNING_KEY_PREFS, any()) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { prefs.getInt(AuthViewModel.LEGACY_SIGNING_KEY_SEEN, 0) } returnsMany listOf(0, 1)

        // Whatever the server reports, and twice over.
        vm.checkSigningKey("c29tZXRoaW5n", sharing.copyOf(), true, sharing)
        vm.checkSigningKey(null, sharing.copyOf(), true, sharing)

        verify(exactly = 0) { cryptoManager.generateSigningKeypair() }
        verify(exactly = 0) { cryptoManager.importSigningPrivateKey(any()) }
        verify { editor.putInt(AuthViewModel.LEGACY_SIGNING_KEY_SEEN, 1) }
        verify { editor.putInt(AuthViewModel.LEGACY_SIGNING_KEY_SEEN, 2) }
    }

    @Test
    fun `a missing blob never mints`() {
        // Withheld, unopenable, with or without a key on record: nothing is minted.
        vm.checkSigningKey("ZnJlc2hQdWI=", null, false, sharing)
        vm.checkSigningKey("ZnJlc2hQdWI=", null, true, sharing)
        vm.checkSigningKey(null, null, true, sharing)
        vm.checkSigningKey(null, null, false, sharing)

        verify(exactly = 0) { cryptoManager.generateSigningKeypair() }
        verify(exactly = 0) { cryptoManager.importSigningPrivateKey(any()) }
    }

    // --- Nothing in a step-2 reply is used unless the server's proof checks out ---

    private fun stubHandshake(step2: com.pingolin.note.data.remote.dto.SrpStep2Response, proofOk: Boolean) {
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.decode(any<String>(), any<Int>()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        every { android.util.Base64.encodeToString(any<ByteArray>(), any<Int>()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        mockkStatic("uniffi.pingolin_mobile.Pingolin_mobileKt")
        every { uniffi.pingolin_mobile.srpVerifyServerProof(any(), any(), any(), any(), any()) } returns proofOk
        every { rateLimiter.checkAttempt(any()) } returns com.pingolin.note.util.RateLimitResult.Allowed
        val b64 = { bytes: ByteArray -> java.util.Base64.getEncoder().encodeToString(bytes) }
        coEvery { authApi.srpStep1(any()) } returns retrofit2.Response.success(
            com.pingolin.note.data.remote.dto.ApiResponse(
                success = true,
                data = com.pingolin.note.data.remote.dto.SrpStep1Response(
                    salt = b64(ByteArray(16) { 1 }),
                    serverPublic = b64(byteArrayOf(2)),
                    sessionId = "s1",
                    bcryptSalt = null,
                ),
            ),
        )
        coEvery { authApi.srpStep2(any()) } returns retrofit2.Response.success(
            com.pingolin.note.data.remote.dto.ApiResponse(success = true, data = step2),
        )
    }

    private fun sessionReply() = com.pingolin.note.data.remote.dto.SrpStep2Response(
        serverProof = "AAAA",
        user = com.pingolin.note.data.remote.dto.UserResponse(id = U1, email = "a@b.c"),
        accessToken = "access",
        refreshToken = "refresh",
        kdfParams = null,
        encryptedKeys = null,
    )

    @Test
    fun `login_serverProofFails_refusesBeforeUsingTheReply`() = runTest {
        stubHandshake(sessionReply(), proofOk = false)

        vm.login("a@b.c", "pw")
        val settled = vm.uiState.first { it != AuthUiState.Loading && it != AuthUiState.Idle }

        assertEquals(AuthUiState.Error("Couldn't sign in. Please try again."), settled)
        verify(exactly = 0) { sessionManager.saveTokens(any(), any()) }
        verify { rateLimiter.recordFailedAttempt("a@b.c") }
        // Checked against the A and M1 this attempt SENT, and K = PAD(S): 256 bytes.
        val sent = slot<com.pingolin.note.data.remote.dto.SrpStep2Request>()
        coVerify { authApi.srpStep2(capture(sent)) }
        val sessionKey = slot<String>()
        verify {
            uniffi.pingolin_mobile.srpVerifyServerProof(
                sent.captured.clientPublic, sent.captured.clientProof, capture(sessionKey), "AAAA",
                uniffi.pingolin_mobile.SrpProofEncoding.PADDED,
            )
        }
        assertEquals(256, java.util.Base64.getDecoder().decode(sessionKey.captured).size)
    }

    @Test
    fun `login_serverProofFails_inTheTwoFactorBranch_startsNoChallenge`() = runTest {
        stubHandshake(
            com.pingolin.note.data.remote.dto.SrpStep2Response(
                serverProof = "AAAA", user = null, accessToken = null, refreshToken = null,
                kdfParams = null, encryptedKeys = null,
                requires2fa = true, twoFactorToken = "tf-token", availableMethods = listOf("totp"),
            ),
            proofOk = false,
        )

        vm.login("a@b.c", "pw")
        val settled = vm.uiState.first { it != AuthUiState.Loading && it != AuthUiState.Idle }

        assertEquals(AuthUiState.Error("Couldn't sign in. Please try again."), settled)
        vm.verifyTwoFactor("123456")
        coVerify(exactly = 0) { authApi.verify2fa(any()) }
    }

    /** The password step's own reply is refused the same way when it names no UUID account. */
    @Test
    fun `login_aReplyNamingNoUUIDAccount_signsNobodyIn`() = runTest {
        stubHandshake(sessionReply().copy(user = com.pingolin.note.data.remote.dto.UserResponse(id = "u1", email = "a@b.c")), proofOk = true)
        every { sessionManager.getMasterKey() } returns "dmF1bHQ="
        every { sessionManager.getSharingPrivateKey() } returns "c2hhcmluZw=="

        vm.login("a@b.c", "pw")
        val settled = vm.uiState.first { it != AuthUiState.Loading && it != AuthUiState.Idle }

        assertEquals(AuthUiState.Error(AuthViewModel.COULD_NOT_SIGN_IN), settled)
        verify(exactly = 0) { sessionManager.saveTokens(any(), any()) }
        verify(exactly = 0) { sessionManager.saveUserId(any()) }
    }

    @Test
    fun `login_serverProofChecksOut_continues`() = runTest {
        stubHandshake(sessionReply(), proofOk = true)
        every { sessionManager.getUserId() } returns U1
        every { sessionManager.getMasterKey() } returns "dmF1bHQ="
        every { sessionManager.getSharingPrivateKey() } returns "c2hhcmluZw=="

        vm.login("a@b.c", "pw")
        val settled = vm.uiState.first { it != AuthUiState.Loading && it != AuthUiState.Idle }

        assertEquals(AuthUiState.Success, settled)
        verify { sessionManager.saveTokens("access", "refresh") }
    }

    // --- The code step, in this app's words; a backup code; an unverified address ---

    /** What a server might say. None of it is ever on screen. */
    private val said = "Invalid membership statement: at_version is not this circle's current key version. Call 0800 555 0100"

    private fun <T> refusedWith(status: Int, code: String? = null): retrofit2.Response<T> =
        retrofit2.Response.error(
            status,
            com.google.gson.Gson().toJson(
                mapOf("success" to false, "error" to said) + (code?.let { mapOf("error_code" to it) } ?: emptyMap())
            ).toResponseBody(null),
        )

    /** A sign-in that stopped at the code step, with a fresh attempt. */
    private suspend fun challenged() {
        stubHandshake(
            com.pingolin.note.data.remote.dto.SrpStep2Response(
                serverProof = "AAAA", user = null, accessToken = null, refreshToken = null,
                kdfParams = null, encryptedKeys = null,
                requires2fa = true, twoFactorToken = "tf-token", availableMethods = listOf("totp"),
            ),
            proofOk = true,
        )
        vm.login("a@b.c", "pw")
        assertEquals(AuthUiState.TwoFactorRequired(), vm.uiState.first { it != AuthUiState.Loading && it != AuthUiState.Idle })
    }

    private suspend fun afterCode(code: String): AuthUiState {
        vm.verifyTwoFactor(code)
        return vm.uiState.first { it != AuthUiState.Loading }
    }

    private val codesSent = mutableListOf<com.pingolin.note.data.remote.dto.TwoFactorVerifyRequest>()

    private fun codeStepAnswers(vararg answers: retrofit2.Response<com.pingolin.note.data.remote.dto.ApiResponse<com.pingolin.note.data.remote.dto.TwoFactorVerifyResponse>>) {
        coEvery { authApi.verify2fa(capture(codesSent)) } returnsMany answers.toList()
    }

    @Test
    fun `a refused code is said in this app's words, and only a wrong or replayed one keeps the attempt`() = runTest {
        data class Case(val status: Int, val code: String?, val line: String, val attemptStillOpen: Boolean)
        // The auth server's replies on /login/2fa.
        val cases = listOf(
            Case(401, null, AuthViewModel.TWO_STEP_WRONG_CODE, true),
            Case(400, "totp_replay", AuthViewModel.TWO_STEP_CODE_JUST_USED, true),
            Case(429, "rate_limited", AuthViewModel.TWO_STEP_TOO_MANY, false),
            Case(400, null, AuthViewModel.TWO_STEP_ENDED, false),
            Case(403, "email_not_verified", AuthViewModel.EMAIL_NOT_VERIFIED, false),
            Case(403, "account_suspended", AuthViewModel.TWO_STEP_ENDED, false),
            Case(503, "rate_limiter_unavailable", com.pingolin.note.data.remote.dto.ApiErrors.TROUBLE, false),
        )
        for (c in cases) {
            challenged()
            codesSent.clear()
            codeStepAnswers(refusedWith(c.status, c.code), refusedWith(401))
            val state = afterCode("000000")
            // An attempt that ends lets go of its token AND the stashed password.
            assertEquals("${c.status}", c.attemptStillOpen, vm.holdsTwoFactorAttempt)
            if (c.attemptStillOpen) {
                assertEquals("${c.status}", AuthUiState.TwoFactorRequired(retryMessage = c.line), state)
                // The same attempt takes the next code.
                afterCode("111111")
                assertEquals("${c.status}", listOf("tf-token", "tf-token"), codesSent.map { it.twoFactorToken })
            } else {
                assertEquals("${c.status}", AuthUiState.Error(c.line), state)
                // The attempt is gone: a code now has nothing to answer.
                assertEquals(
                    AuthUiState.Error("That sign-in attempt timed out. Enter your password again."),
                    afterCode("111111"),
                )
                assertEquals("${c.status}", 1, codesSent.size)
            }
            assertFalse("the server's words reached the screen: $state", state.toString().contains("0800"))
        }
    }

    @Test
    fun `a wrong code, then the right one on the same attempt, signs in`() = runTest {
        challenged()
        codeStepAnswers(
            refusedWith(401),
            retrofit2.Response.success(
                com.pingolin.note.data.remote.dto.ApiResponse(
                    success = true,
                    data = com.pingolin.note.data.remote.dto.TwoFactorVerifyResponse(
                        accessToken = "access", refreshToken = "refresh",
                        user = com.pingolin.note.data.remote.dto.UserResponse(id = U1, email = "a@b.c"),
                    ),
                ),
            ),
        )
        assertEquals(AuthUiState.TwoFactorRequired(AuthViewModel.TWO_STEP_WRONG_CODE), afterCode("000000"))

        every { sessionManager.getUserId() } returns U1
        every { sessionManager.getMasterKey() } returns "dmF1bHQ="
        every { sessionManager.getSharingPrivateKey() } returns "c2hhcmluZw=="
        assertEquals(AuthUiState.Success, afterCode("123456"))
        assertEquals(listOf("tf-token", "tf-token"), codesSent.map { it.twoFactorToken })
        verify { sessionManager.saveTokens("access", "refresh") }
    }

    @Test
    fun `a code the server took ends the attempt, even when the sign-in after it fails`() = runTest {
        challenged()
        codeStepAnswers(
            retrofit2.Response.success(
                com.pingolin.note.data.remote.dto.ApiResponse(
                    success = true,
                    data = com.pingolin.note.data.remote.dto.TwoFactorVerifyResponse(
                        accessToken = "access", refreshToken = "refresh",
                        user = com.pingolin.note.data.remote.dto.UserResponse(id = U1, email = "a@b.c"),
                    ),
                ),
            ),
            refusedWith(401),
        )
        every { sessionManager.getUserId() } returns U1
        keysPresent()
        every { sessionManager.saveTokens(any(), any()) } throws IllegalStateException("keystore said no")

        assertEquals(AuthUiState.Error(AuthViewModel.COULD_NOT_SIGN_IN), afterCode("123456"))
        assertFalse(vm.holdsTwoFactorAttempt)
        // The server spent the attempt: a code now has nothing to answer.
        assertEquals(
            AuthUiState.Error("That sign-in attempt timed out. Enter your password again."),
            afterCode("654321"),
        )
        assertEquals(1, codesSent.size)
    }

    @Test
    fun `a code the server never answered keeps the attempt`() = runTest {
        challenged()
        coEvery { authApi.verify2fa(capture(codesSent)) } throws java.net.SocketTimeoutException("timeout") andThen refusedWith(401)

        assertEquals(AuthUiState.TwoFactorRequired(com.pingolin.note.util.UNREACHABLE_TEXT), afterCode("000000"))
        assertTrue(vm.holdsTwoFactorAttempt)
        afterCode("111111")
        assertEquals(listOf("tf-token", "tf-token"), codesSent.map { it.twoFactorToken })
    }

    @Test
    fun `a code step that fails any other way ends the attempt`() = runTest {
        challenged()
        coEvery { authApi.verify2fa(any()) } throws com.google.gson.JsonSyntaxException("Expected BEGIN_OBJECT: $said")

        assertEquals(AuthUiState.Error(AuthViewModel.TWO_STEP_ENDED), afterCode("000000"))
        assertFalse(vm.holdsTwoFactorAttempt)
    }

    @Test
    fun `a backup code is sent whole, as the portal sends it`() = runTest {
        challenged()
        codeStepAnswers(refusedWith(401))

        afterCode(" abcd-efgh 2345 ")

        assertEquals("abcdefgh2345", codesSent.single().code)
        assertEquals("totp", codesSent.single().method)
        // Nothing cut short, the case left alone, an authenticator code unchanged.
        assertEquals("ABCDEFGH2345XY", AuthViewModel.codeToSend("ABCD EFGH 2345 XY"))
        assertEquals("123456", AuthViewModel.codeToSend("123 456"))
    }

    @Test
    fun `only a digits-only entry shorter than six is refused before it is sent`() {
        assertTrue(AuthViewModel.tooShortToTry(""))
        assertTrue(AuthViewModel.tooShortToTry("12345"))
        assertTrue(AuthViewModel.tooShortToTry("123 45"))
        assertFalse(AuthViewModel.tooShortToTry("123456"))
        assertFalse(AuthViewModel.tooShortToTry("ABCD-EFGH-2345"))
        assertFalse(AuthViewModel.tooShortToTry("abc"))
    }

    @Test
    fun `an address never verified is told so at sign-in, and it is not a failed try`() = runTest {
        stubHandshake(sessionReply(), proofOk = true)
        coEvery { authApi.srpStep2(any()) } returns refusedWith(403, "email_not_verified")

        vm.login("a@b.c", "pw")
        val settled = vm.uiState.first { it != AuthUiState.Loading && it != AuthUiState.Idle }

        assertEquals(
            AuthUiState.Error("Please verify your email address first. Open the link we sent you, then sign in again."),
            settled,
        )
        verify(exactly = 0) { rateLimiter.recordFailedAttempt(any()) }
    }

    @Test
    fun `a refused sign-in is the credentials, too many tries or trouble, never the server's sentence`() = runTest {
        val cases = listOf(
            Triple(403, null, "Invalid email or password"),
            Triple(400, null, "Invalid email or password"),
            Triple(429, "rate_limited", com.pingolin.note.data.remote.dto.ApiErrors.TOO_MANY),
            Triple(503, "server_unavailable", com.pingolin.note.data.remote.dto.ApiErrors.TROUBLE),
        )
        for ((status, code, line) in cases) {
            stubHandshake(sessionReply(), proofOk = true)
            coEvery { authApi.srpStep2(any()) } returns refusedWith(status, code)
            vm.login("a@b.c", "pw")
            val settled = vm.uiState.first { it != AuthUiState.Loading && it != AuthUiState.Idle }
            assertEquals("$status", AuthUiState.Error(line), settled)
        }
    }

    @Test
    fun `a refused first step, or a failure on the way, is said in this app's words`() = runTest {
        val cases = listOf(
            Triple(503, "server_unavailable", com.pingolin.note.data.remote.dto.ApiErrors.TROUBLE),
            Triple(429, "rate_limited", com.pingolin.note.data.remote.dto.ApiErrors.TOO_MANY),
            Triple(400, null, AuthViewModel.COULD_NOT_SIGN_IN),
        )
        for ((status, code, line) in cases) {
            stubHandshake(sessionReply(), proofOk = true)
            coEvery { authApi.srpStep1(any()) } returns refusedWith(status, code)
            vm.login("a@b.c", "pw")
            assertEquals("$status", AuthUiState.Error(line), vm.uiState.first { it is AuthUiState.Error })
        }
        stubHandshake(sessionReply(), proofOk = true)
        coEvery { authApi.srpStep1(any()) } throws java.net.UnknownHostException("Unable to resolve host \"api-auth.pingolindev.com\"")
        vm.login("a@b.c", "pw")
        assertEquals(AuthUiState.Error(com.pingolin.note.util.UNREACHABLE_TEXT), vm.uiState.first { it is AuthUiState.Error })

        stubHandshake(sessionReply(), proofOk = true)
        coEvery { authApi.srpStep2(any()) } throws uniffi.pingolin_mobile.MobileException.InvalidInput("bad server public value: $said")
        vm.login("a@b.c", "pw")
        assertEquals(AuthUiState.Error(AuthViewModel.COULD_NOT_SIGN_IN), vm.uiState.first { it is AuthUiState.Error })
    }

    @Test
    fun `the email checks say what didn't happen`() = runTest {
        coEvery { authApi.verifyEmail(any()) } returns refusedWith(400) andThen refusedWith(429, "rate_limited")
        vm.verifyEmail("a-verification-token-of-length")
        assertEquals(AuthUiState.Error(AuthViewModel.COULD_NOT_VERIFY_EMAIL), vm.uiState.first { it is AuthUiState.Error })
        vm.verifyEmail("a-verification-token-of-length")
        assertEquals(AuthUiState.Error(com.pingolin.note.data.remote.dto.ApiErrors.TOO_MANY), vm.uiState.value)
        coEvery { authApi.verifyEmail(any()) } throws java.net.SocketTimeoutException("timeout")
        vm.verifyEmail("a-verification-token-of-length")
        assertEquals(AuthUiState.Error(com.pingolin.note.util.UNREACHABLE_TEXT), vm.uiState.value)

        every { sessionManager.getEmail() } returns "a@b.c"
        coEvery { authApi.resendVerification() } returns refusedWith(400)
        vm.resendVerificationEmail()
        assertEquals(AuthUiState.Error("Couldn't resend verification email"), vm.uiState.first { it is AuthUiState.Error })
        coEvery { authApi.resendVerification() } throws IllegalStateException("Expected BEGIN_OBJECT: $said")
        vm.resendVerificationEmail()
        assertEquals(AuthUiState.Error("Couldn't resend verification email"), vm.uiState.value)
    }

    @Test
    fun `a missing proof or an SDK refusal is a failure`() {
        mockkStatic("uniffi.pingolin_mobile.Pingolin_mobileKt")
        every { uniffi.pingolin_mobile.srpVerifyServerProof(any(), any(), any(), any(), any()) } throws
            uniffi.pingolin_mobile.MobileException.InvalidInput("bad A")
        assertFalse(AuthViewModel.serverProofMatches("A", "M1", "K", null))
        assertFalse(AuthViewModel.serverProofMatches("A", "M1", "K", ""))
        assertFalse(AuthViewModel.serverProofMatches("A", "M1", "K", "AAAA"))
        every { uniffi.pingolin_mobile.srpVerifyServerProof(any(), any(), any(), any(), any()) } returns true
        assertTrue(AuthViewModel.serverProofMatches("A", "M1", "K", "AAAA"))
    }
}
