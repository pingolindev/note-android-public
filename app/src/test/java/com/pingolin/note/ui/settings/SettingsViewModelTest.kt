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

import android.app.Application
import com.pingolin.note.data.local.entity.ItemEntity
import com.pingolin.note.data.remote.api.AuthApi
import com.pingolin.note.data.remote.api.NoteApi
import com.pingolin.note.data.remote.dto.ApiResponse
import com.pingolin.note.data.remote.dto.SubscriptionInfo
import com.pingolin.note.data.remote.dto.UpdateProfileRequest
import com.pingolin.note.data.remote.dto.UserResponse
import com.pingolin.note.domain.repository.LocalDataWiper
import com.pingolin.note.domain.repository.NoteRepository
import com.pingolin.note.domain.repository.QuotaManager
import com.pingolin.note.domain.repository.SyncRepository
import com.pingolin.note.util.PendingPushUnregisterStore
import com.pingolin.note.util.PushNotificationManager
import com.pingolin.note.util.SessionManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response as RetrofitResponse
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var syncRepository: SyncRepository

    @MockK
    private lateinit var authApi: AuthApi

    @MockK
    private lateinit var noteApi: NoteApi

    @MockK
    private lateinit var noteRepository: NoteRepository

    @MockK
    private lateinit var application: Application

    @MockK(relaxed = true)
    private lateinit var localDataWiper: LocalDataWiper

    @MockK(relaxed = true)
    private lateinit var pushNotificationManager: PushNotificationManager

    @MockK(relaxed = true)
    private lateinit var pendingPushUnregisterStore: PendingPushUnregisterStore

    @MockK(relaxed = true)
    private lateinit var userMetadata: com.pingolin.note.domain.repository.UserMetadataStore

    /** Only reached when exporting a backup, to name the files it cannot
     *  carry — the filenames are sealed under each note's content key. */
    @MockK
    private lateinit var noteCrypto: com.pingolin.note.collab.NoteCrypto

    @MockK
    private lateinit var retainedContentKeyStore: com.pingolin.note.util.RetainedContentKeyStore

    /** The erased account's trust, cleared at a deletion. */
    @MockK(relaxed = true)
    private lateinit var accountTrustTidy: com.pingolin.note.util.AccountTrustTidy

    // Relaxed standalone mock (not @MockK) — the VM's init{} observes
    // quotaManager.quota; a relaxed mock returns a no-op flow so construction
    // stays side-effect-free in these tests.
    private val quotaManager: QuotaManager = mockk(relaxed = true)
    private val logoutOrchestrator: com.pingolin.note.domain.repository.LogoutOrchestrator = mockk(relaxed = true)

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        // init{} observes quotaManager.quota; give it a real (no-op) StateFlow so
        // construction doesn't blow up (a relaxed-mock flow throws on collect).
        every { quotaManager.quota } returns
            kotlinx.coroutines.flow.MutableStateFlow<com.pingolin.note.data.remote.dto.QuotaDto?>(null)

        // Default session stubs so constructor-time init {} loadProfile +
        // fetchSubscriptionTier don't blow up.
        every { sessionManager.getTheme() } returns SessionManager.THEME_SYSTEM
        every { sessionManager.getDisplayName() } returns null

        // logout() snapshots the departing account's push identity up front; default
        // to "no push registered / no token" so the deferred-unregister path is a
        // no-op (no PushUnregisterWorker enqueue / WorkManager) in these JVM tests.
        every { sessionManager.getOrCreateDeviceId() } returns "test-device"
        every { sessionManager.getAccessToken() } returns null
        // Tags the deferred-unregister record with the DEPARTING account, so a
        // next account can't inherit this device's still-live push registration.
        every { sessionManager.getUserId() } returns "user-1"
        every { sessionManager.getPushType() } returns null
        every { sessionManager.getPushToken() } returns null
        coEvery { noteApi.unregisterFcmDevice(any()) } returns RetrofitResponse.success(Unit)
        coEvery { authApi.getCurrentUser() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = UserResponse(id = "u1", email = "a@b.c"))
        )
        coEvery { authApi.getSubscriptions() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = emptyList())
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    // =============================================================================
    // syncNow TESTS
    // =============================================================================

    @Test
    fun `syncNow_callsSyncRepository`() = runTest {
        coEvery { syncRepository.sync() } returns SyncRepository.SyncResult(0, 0, 0)

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )
        vm.syncNow()

        coVerify { syncRepository.sync() }
        assertFalse(vm.isSyncing.value)
    }

    @Test
    fun `syncNow_whileSyncing_skipsCall`() = runTest {
        coEvery { syncRepository.sync() } coAnswers {
            kotlinx.coroutines.delay(10_000)
            SyncRepository.SyncResult(0, 0, 0)
        }

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )
        vm.syncNow()
        vm.syncNow()

        coVerify(exactly = 1) { syncRepository.sync() }
    }

    @Test
    fun `syncNow_syncThrows_clearsSyncingFlag`() = runTest {
        coEvery { syncRepository.sync() } throws RuntimeException("boom")

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )
        vm.syncNow()

        assertFalse(vm.isSyncing.value)
    }

    // =============================================================================
    // theme persistence TESTS
    // =============================================================================

    @Test
    fun `setTheme_writesToSession_andUpdatesFlow`() = runTest {
        every { sessionManager.setTheme(any()) } just Runs

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )
        vm.setTheme(SessionManager.THEME_DARK)

        verify { sessionManager.setTheme(SessionManager.THEME_DARK) }
        assertEquals(SessionManager.THEME_DARK, vm.theme.value)
    }

    // =============================================================================
    // editor mode persistence TESTS
    // =============================================================================

    @Test
    fun `setEditorMode_writesToSession`() = runTest {
        every { sessionManager.setEditorMode(any()) } just Runs

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )
        vm.setEditorMode(SessionManager.EDITOR_MODE_MARKDOWN)

        verify { sessionManager.setEditorMode(SessionManager.EDITOR_MODE_MARKDOWN) }
    }

    @Test
    fun `getEditorMode_readsFromSession`() = runTest {
        every { sessionManager.getEditorMode() } returns SessionManager.EDITOR_MODE_PREVIEW

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )

        assertEquals(SessionManager.EDITOR_MODE_PREVIEW, vm.getEditorMode())
    }

    // =============================================================================
    // export TESTS
    // =============================================================================

    @Test
    @org.junit.Ignore("exportNotes writes via org.json.JSONArray which requires Robolectric to parse")
    fun `exportNotes_populatesExportJson`() = runTest {
        val notes = listOf(
            ItemEntity(
                id = "1", userId = "u", itemType = 1,
                encryptedContent = "e", contentNonce = "n",
                decryptedContent = "{}", title = "N1",
                version = 1, createdAt = Instant.now(), updatedAt = Instant.now(),
            ),
        )
        coEvery { noteRepository.getAllNotes() } returns notes

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )
        vm.exportNotes()

        assertNotNull(vm.exportJson.value)
    }

    @Test
    fun `clearExport_nullsExportJson`() = runTest {
        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )
        vm.clearExport()

        assertNull(vm.exportJson.value)
    }

    // =============================================================================
    // app lock TESTS
    // =============================================================================

    @Test
    fun `isAppLockEnabled_delegatesToSession`() = runTest {
        every { sessionManager.isAppLockEnabled() } returns true

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )

        assertTrue(vm.isAppLockEnabled())
    }

    @Test
    fun `setAppLockEnabled_true_onlySetsEnabled`() = runTest {
        every { sessionManager.setAppLockEnabled(any()) } just Runs
        every { sessionManager.setForceAuthOnNextLaunch(any()) } just Runs
        every { sessionManager.clearBackgroundTime() } just Runs

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )
        vm.setAppLockEnabled(true)

        verify { sessionManager.setAppLockEnabled(true) }
        // When enabling, don't bother with reset of background timers.
        verify(exactly = 0) { sessionManager.setForceAuthOnNextLaunch(any()) }
        verify(exactly = 0) { sessionManager.clearBackgroundTime() }
    }

    @Test
    fun `setAppLockEnabled_false_alsoClearsSideEffects`() = runTest {
        every { sessionManager.setAppLockEnabled(any()) } just Runs
        every { sessionManager.setForceAuthOnNextLaunch(any()) } just Runs
        every { sessionManager.clearBackgroundTime() } just Runs

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )
        vm.setAppLockEnabled(false)

        verify { sessionManager.setAppLockEnabled(false) }
        verify { sessionManager.setForceAuthOnNextLaunch(false) }
        verify { sessionManager.clearBackgroundTime() }
    }

    @Test
    fun `setAutoLockTimeout_delegatesToSession`() = runTest {
        every { sessionManager.setAutoLockTimeout(any()) } just Runs

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )
        vm.setAutoLockTimeout(60_000L)

        verify { sessionManager.setAutoLockTimeout(60_000L) }
    }

    @Test
    fun `getAutoLockTimeout_readsFromSession`() = runTest {
        every { sessionManager.getAutoLockTimeout() } returns 300_000L

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )

        assertEquals(300_000L, vm.getAutoLockTimeout())
    }

    // =============================================================================
    // logout TESTS
    // =============================================================================

    private fun stubConnectivity(online: Boolean) {
        val cm = mockk<android.net.ConnectivityManager>()
        every { application.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) } returns cm
        if (online) {
            val net = mockk<android.net.Network>()
            val caps = mockk<android.net.NetworkCapabilities>()
            every { cm.activeNetwork } returns net
            every { cm.getNetworkCapabilities(net) } returns caps
            every { caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
            every { caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
        } else {
            every { cm.activeNetwork } returns null
            every { cm.getNetworkCapabilities(any()) } returns null
        }
    }

    @Test
    fun `logout_delegatesToOrchestrator`() = runTest {
        // The orchestration itself (flush ordering, revoke, wipe, push
        // teardown) lives in LogoutOrchestrator — LogoutOrchestratorTest
        // owns those cases.
        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )
        vm.logout()
        verify { logoutOrchestrator.logout() }
    }

    // =============================================================================
    // displayName TESTS
    // =============================================================================

    @Test
    fun `saveDisplayName_successResponse_writesCache`() = runTest {
        coEvery { authApi.updateDisplayName(any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = Unit)
        )
        every { sessionManager.saveDisplayName(any()) } just Runs

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )
        vm.saveDisplayName("NewName")

        coVerify { authApi.updateDisplayName(UpdateProfileRequest(displayName = "NewName")) }
        verify { sessionManager.saveDisplayName("NewName") }
        assertEquals("NewName", vm.displayName.value)
    }

    // =============================================================================
    // subscription tier TESTS
    // =============================================================================

    @Test
    fun `subscriptionTier_paidQuota_setsPremium`() = runTest {
        // Tier is sourced from QuotaManager.quota (not authApi.getSubscriptions):
        // a paid quota → "Premium". Overrides the setup's null-quota stub.
        every { quotaManager.quota } returns kotlinx.coroutines.flow.MutableStateFlow(
            com.pingolin.note.data.remote.dto.QuotaDto(
                tier = QuotaManager.TIER_PAID,
                maxNotes = 0, currentNotes = 0, remainingNotes = 0,
                maxAttachmentBytes = 0, currentAttachmentBytes = 0, remainingAttachmentBytes = 0,
                sharingEnabled = true, remindersEnabled = true,
            )
        )

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )

        assertEquals("Premium", vm.subscriptionTier.value)
    }

    @Test
    fun `fetchSubscriptionTier_noActive_stayFree`() = runTest {
        coEvery { authApi.getSubscriptions() } returns RetrofitResponse.success(
            ApiResponse(success = true, data = emptyList())
        )

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )

        assertEquals("Free", vm.subscriptionTier.value)
    }

    // =============================================================================
    // The reauth token is used only after the server's proof checks out
    // =============================================================================

    @Test
    fun `deleteAccount_serverProofFails_refusesWithoutDeleting`() = runTest {
        // SrpClient encodes with android.util.Base64, which the JVM stubs to null.
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.decode(any<String>(), any<Int>()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        every { android.util.Base64.encodeToString(any<ByteArray>(), any<Int>()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        mockkStatic("uniffi.pingolin_mobile.Pingolin_mobileKt")
        every { uniffi.pingolin_mobile.srpVerifyServerProof(any(), any(), any(), any(), any()) } returns false
        every { sessionManager.getEmail() } returns "a@b.c"
        val b64 = { bytes: ByteArray -> java.util.Base64.getEncoder().encodeToString(bytes) }
        coEvery { authApi.reauthStep1() } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = com.pingolin.note.data.remote.dto.SrpStep1Response(
                    salt = b64(ByteArray(16) { 1 }),
                    serverPublic = b64(byteArrayOf(2)),
                    sessionId = "s1",
                    bcryptSalt = null,
                ),
            )
        )
        coEvery { authApi.reauthStep2(any()) } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = com.pingolin.note.data.remote.dto.ReauthStep2Response(reauthToken = "rt", serverProof = "AAAA"),
            )
        )

        val vm = SettingsViewModel(
            sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
            logoutOrchestrator,
            pushNotificationManager, userMetadata,
            noteCrypto, retainedContentKeyStore, accountTrustTidy,
        )
        vm.deleteAccount("pw".toCharArray())
        val settled = vm.deletionState.first { it !is AccountDeletionState.Idle && it !is AccountDeletionState.InProgress }

        assertEquals(AccountDeletionState.Failed("Couldn't check your password just now."), settled)
        coVerify(exactly = 0) { authApi.deleteAccount(any()) }
        // Checked against the A and M1 this reauth SENT, and K = PAD(S): 256 bytes.
        val sent = slot<com.pingolin.note.data.remote.dto.SrpStep2Request>()
        coVerify { authApi.reauthStep2(capture(sent)) }
        val sessionKey = slot<String>()
        verify {
            uniffi.pingolin_mobile.srpVerifyServerProof(
                sent.captured.clientPublic, sent.captured.clientProof, capture(sessionKey), "AAAA",
                uniffi.pingolin_mobile.SrpProofEncoding.PADDED,
            )
        }
        assertEquals(256, java.util.Base64.getDecoder().decode(sessionKey.captured).size)
    }

    // =============================================================================
    // A refused deletion is said in this app's words, never the server's
    // =============================================================================

    /** What a server might say. None of it is ever on screen. */
    private val said = "Invalid membership statement: at_version is not this circle's current key version. Call 0800 555 0100"

    private fun <T> refusedWith(status: Int, code: String? = null): RetrofitResponse<T> {
        val json = com.google.gson.Gson().toJson(
            mapOf("success" to false, "error" to said) + (code?.let { mapOf("error_code" to it) } ?: emptyMap())
        )
        return RetrofitResponse.error(status, okhttp3.ResponseBody.Companion.run { json.toResponseBody(null) })
    }

    /** The step-up's two steps, answered; the server's proof checks out. */
    private fun stubReauth() {
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.decode(any<String>(), any<Int>()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        every { android.util.Base64.encodeToString(any<ByteArray>(), any<Int>()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        mockkStatic("uniffi.pingolin_mobile.Pingolin_mobileKt")
        every { uniffi.pingolin_mobile.srpVerifyServerProof(any(), any(), any(), any(), any()) } returns true
        every { sessionManager.getEmail() } returns "a@b.c"
        val b64 = { bytes: ByteArray -> java.util.Base64.getEncoder().encodeToString(bytes) }
        coEvery { authApi.reauthStep1() } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = com.pingolin.note.data.remote.dto.SrpStep1Response(
                    salt = b64(ByteArray(16) { 1 }),
                    serverPublic = b64(byteArrayOf(2)),
                    sessionId = "s1",
                    bcryptSalt = null,
                ),
            )
        )
        coEvery { authApi.reauthStep2(any()) } returns RetrofitResponse.success(
            ApiResponse(
                success = true,
                data = com.pingolin.note.data.remote.dto.ReauthStep2Response(reauthToken = "rt", serverProof = "AAAA"),
            )
        )
    }

    private fun deletionVm() = SettingsViewModel(
        sessionManager, syncRepository, authApi, noteApi, noteRepository, quotaManager, application, localDataWiper,
        logoutOrchestrator,
        pushNotificationManager, userMetadata,
        noteCrypto, retainedContentKeyStore, accountTrustTidy,
    )

    private suspend fun settledDeletion(vm: SettingsViewModel): AccountDeletionState =
        vm.deletionState.first { it !is AccountDeletionState.Idle && it !is AccountDeletionState.InProgress }

    @Test
    fun `a deletion erases that account's kept trust, by the id read before the wipe`() = runTest {
        stubReauth()
        val erased = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        every { sessionManager.getUserId() } returns erased
        coEvery { authApi.deleteAccount(any()) } returns RetrofitResponse.success(
            ApiResponse(success = true, data = com.pingolin.note.data.remote.dto.DeleteAccountResponse(deleted = true)),
        )
        val preWipe = slot<suspend () -> Unit>()
        every { localDataWiper.wipe(capture(preWipe), any()) } just Runs
        val counter = mockk<android.content.SharedPreferences>(relaxed = true)
        every { application.getSharedPreferences(com.pingolin.note.ui.auth.AuthViewModel.SIGNING_KEY_PREFS, any()) } returns counter

        val vm = deletionVm()
        vm.deleteAccount("pw".toCharArray())
        assertTrue(settledDeletion(vm) is AccountDeletionState.Deleted)

        // The wipe clears the session before its pre-step runs its course: the id was read first.
        every { sessionManager.getUserId() } returns null
        preWipe.captured.invoke()
        verify(exactly = 1) { accountTrustTidy.erase(erased) }
        verify { counter.edit() }
    }

    @Test
    fun `unspent credit is asked about - its code read from the refusal, in this app's words`() = runTest {
        // The auth server answers 409 `unspent_credit_balance`. Its code is read from the
        // refusal itself: body() is never filled for a refusal, and reading it there would
        // skip the question and show a bare failure instead.
        stubReauth()
        val sent = mutableListOf<com.pingolin.note.data.remote.dto.DeleteAccountRequest>()
        coEvery { authApi.deleteAccount(capture(sent)) } returns refusedWith(409, "unspent_credit_balance")

        val vm = deletionVm()
        vm.deleteAccount("pw".toCharArray())

        assertEquals(AccountDeletionState.UnspentCredit(SettingsViewModel.UNSPENT_CREDIT_TEXT), settledDeletion(vm))
        // The credit is given up only on the person's own answer, never on a first attempt.
        assertNull(sent.single().forfeitCreditBalance)
        assertTrue(SettingsViewModel.UNSPENT_CREDIT_TEXT.startsWith("Deleting your account destroys the credit on it"))
    }

    @Test
    fun `a refused deletion says what didn't happen, never the server's sentence`() = runTest {
        val cases = listOf(
            Triple(400, null, SettingsViewModel.COULD_NOT_DELETE),
            Triple(503, "server_unavailable", com.pingolin.note.data.remote.dto.ApiErrors.TROUBLE),
            Triple(429, "rate_limited", com.pingolin.note.data.remote.dto.ApiErrors.TOO_MANY),
        )
        for ((status, code, line) in cases) {
            stubReauth()
            coEvery { authApi.deleteAccount(any()) } returns refusedWith(status, code)
            val vm = deletionVm()
            vm.deleteAccount("pw".toCharArray())
            assertEquals("$status", AccountDeletionState.Failed(line), settledDeletion(vm))
        }
        stubReauth()
        coEvery { authApi.deleteAccount(any()) } throws java.net.SocketTimeoutException("timeout")
        val vm = deletionVm()
        vm.deleteAccount("pw".toCharArray())
        assertEquals(AccountDeletionState.Failed(com.pingolin.note.util.UNREACHABLE_TEXT), settledDeletion(vm))
    }

    @Test
    fun `the password check's refusals are this app's words`() = runTest {
        stubReauth()
        coEvery { authApi.reauthStep2(any()) } returns refusedWith(401, "invalid_credentials")
        val wrong = deletionVm()
        wrong.deleteAccount("pw".toCharArray())
        assertEquals(AccountDeletionState.Failed("Invalid password"), settledDeletion(wrong))

        // The step-up's other 401 is an expired handshake, not a wrong password.
        stubReauth()
        coEvery { authApi.reauthStep2(any()) } returns refusedWith(401, "reauth_session_expired")
        val stale = deletionVm()
        stale.deleteAccount("pw".toCharArray())
        assertEquals(AccountDeletionState.Failed("Couldn't check your password just now."), settledDeletion(stale))

        stubReauth()
        coEvery { authApi.reauthStep1() } returns refusedWith(503, "server_unavailable")
        val trouble = deletionVm()
        trouble.deleteAccount("pw".toCharArray())
        assertEquals(AccountDeletionState.Failed(com.pingolin.note.data.remote.dto.ApiErrors.TROUBLE), settledDeletion(trouble))

        stubReauth()
        coEvery { authApi.reauthStep2(any()) } returns refusedWith(400)
        val other = deletionVm()
        other.deleteAccount("pw".toCharArray())
        assertEquals(AccountDeletionState.Failed("Couldn't check your password just now."), settledDeletion(other))
        coVerify(exactly = 0) { authApi.deleteAccount(any()) }
    }
}
