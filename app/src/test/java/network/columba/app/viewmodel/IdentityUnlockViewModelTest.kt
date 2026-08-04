package network.columba.app.viewmodel

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.RnsCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityUnlockViewModelTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val keyData = ByteArray(64) { index -> index.toByte() }
    private val activeHash = "bab3608daf86147268c8ef9bf62c0e08"
    private val destinationHash = "73908a7266dd03521b47f2473f38481b"

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var uri: Uri
    private lateinit var identityRepository: IdentityRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var rnsCore: RnsCore

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        context = mockk()
        contentResolver = mockk()
        uri = mockk()
        identityRepository = mockk()
        settingsRepository = mockk()
        rnsCore = mockk()
        every { context.contentResolver } returns contentResolver
        coEvery { settingsRepository.setNeedsIdentityUnlock(any()) } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `matching identity rewraps existing row and clears recovery flag`() = runBlocking {
        val active = activeIdentity()
        stubActive(active)
        stubFile(keyData)
        coEvery { rnsCore.importIdentityFile(keyData, active.displayName) } returns successResult(activeHash)
        coEvery { identityRepository.rewrapKeyWithDeviceKey(activeHash, keyData) } returns Result.success(Unit)
        val viewModel = createViewModel()

        viewModel.importIdentityFile(uri)

        assertEquals(IdentityUnlockUiState.Restored, viewModel.awaitTerminalState())
        coVerify(exactly = 1) { identityRepository.rewrapKeyWithDeviceKey(activeHash, keyData) }
        coVerify(exactly = 1) { settingsRepository.setNeedsIdentityUnlock(false) }
        verifyNoDestructiveIdentityCalls()
    }

    @Test
    fun `matching identity rewrap failure leaves recovery flag set`() = runBlocking {
        val active = activeIdentity()
        stubActive(active)
        stubFile(keyData)
        coEvery { rnsCore.importIdentityFile(keyData, active.displayName) } returns successResult(activeHash)
        coEvery { identityRepository.rewrapKeyWithDeviceKey(activeHash, keyData) } returns
            Result.failure(IllegalStateException("keystore unavailable"))
        val viewModel = createViewModel()

        viewModel.importIdentityFile(uri)

        val state = viewModel.awaitTerminalState()
        assertTrue(state is IdentityUnlockUiState.Error)
        assertEquals("Couldn't save identity key: keystore unavailable", (state as IdentityUnlockUiState.Error).message)
        coVerify(exactly = 0) { settingsRepository.setNeedsIdentityUnlock(false) }
        verifyNoDestructiveIdentityCalls()
    }

    @Test
    fun `mismatched identity is rejected without any persistence mutation`() = runBlocking {
        val active = activeIdentity()
        val importedHash = "11111111111111111111111111111111"
        stubActive(active)
        stubFile(keyData)
        coEvery { rnsCore.importIdentityFile(keyData, active.displayName) } returns successResult(importedHash)
        val viewModel = createViewModel()

        viewModel.importIdentityFile(uri)

        assertEquals(
            IdentityUnlockUiState.HashMismatch(importedHash, activeHash),
            viewModel.awaitTerminalState(),
        )
        coVerify(exactly = 0) { identityRepository.rewrapKeyWithDeviceKey(any(), any()) }
        coVerify(exactly = 0) { settingsRepository.setNeedsIdentityUnlock(any()) }
        verifyNoDestructiveIdentityCalls()

        viewModel.cancelHashMismatch()
        assertEquals(IdentityUnlockUiState.Idle, viewModel.uiState.value)
        verifyNoDestructiveIdentityCalls()
    }

    @Test
    fun `short identity file is rejected before backend or persistence`() = runBlocking {
        stubActive(activeIdentity())
        stubFile(ByteArray(63))
        val viewModel = createViewModel()

        viewModel.importIdentityFile(uri)

        val state = viewModel.awaitTerminalState()
        assertTrue(state is IdentityUnlockUiState.Error)
        assertEquals(
            "Invalid identity file: expected 64 bytes, got 63 bytes",
            (state as IdentityUnlockUiState.Error).message,
        )
        coVerify(exactly = 0) { rnsCore.importIdentityFile(any(), any()) }
        verifyNoPersistenceCalls()
    }

    @Test
    fun `overlong identity file is rejected before backend or persistence`() = runBlocking {
        stubActive(activeIdentity())
        stubFile(ByteArray(65))
        val viewModel = createViewModel()

        viewModel.importIdentityFile(uri)

        val state = viewModel.awaitTerminalState()
        assertTrue(state is IdentityUnlockUiState.Error)
        assertEquals(
            "Invalid identity file: expected 64 bytes, file is larger than 64 bytes",
            (state as IdentityUnlockUiState.Error).message,
        )
        coVerify(exactly = 0) { rnsCore.importIdentityFile(any(), any()) }
        verifyNoPersistenceCalls()
    }

    @Test
    fun `backend parse error is surfaced without persistence mutation`() = runBlocking {
        val active = activeIdentity()
        stubActive(active)
        stubFile(keyData)
        coEvery { rnsCore.importIdentityFile(keyData, active.displayName) } returns
            mapOf("success" to false, "error" to "Invalid private key data")
        val viewModel = createViewModel()

        viewModel.importIdentityFile(uri)

        val state = viewModel.awaitTerminalState()
        assertTrue(state is IdentityUnlockUiState.Error)
        assertEquals("Invalid private key data", (state as IdentityUnlockUiState.Error).message)
        verifyNoPersistenceCalls()
    }

    @Test
    fun `missing active identity rejects import before opening selected file`() = runBlocking {
        stubActive(null)
        val viewModel = createViewModel()

        viewModel.importIdentityFile(uri)

        val state = viewModel.awaitTerminalState()
        assertTrue(state is IdentityUnlockUiState.Error)
        assertEquals("No active identity to restore into", (state as IdentityUnlockUiState.Error).message)
        verify(exactly = 0) { contentResolver.openInputStream(any()) }
        coVerify(exactly = 0) { rnsCore.importIdentityFile(any(), any()) }
        verifyNoPersistenceCalls()
    }

    private fun activeIdentity() =
        LocalIdentityEntity(
            identityHash = activeHash,
            displayName = "Recovered identity",
            destinationHash = destinationHash,
            filePath = "",
            encryptedKeyData = byteArrayOf(1, 2, 3),
            keyEncryptionVersion = 1,
            createdTimestamp = 1L,
            lastUsedTimestamp = 2L,
            isActive = true,
        )

    private fun stubActive(active: LocalIdentityEntity?) {
        every { identityRepository.activeIdentity } returns flowOf(active)
        coEvery { identityRepository.getActiveIdentitySync() } returns active
    }

    private fun stubFile(bytes: ByteArray) {
        every { contentResolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }
    }

    private fun successResult(identityHash: String): Map<String, Any> =
        mapOf(
            "success" to true,
            "identity_hash" to identityHash,
            "destination_hash" to destinationHash,
            "key_data" to keyData,
        )

    private fun createViewModel() =
        IdentityUnlockViewModel(
            context = context,
            identityRepository = identityRepository,
            settingsRepository = settingsRepository,
            rnsCore = rnsCore,
        )

    private suspend fun IdentityUnlockViewModel.awaitTerminalState(): IdentityUnlockUiState =
        withTimeout(3_000) {
            uiState
                .filter { state ->
                    state !is IdentityUnlockUiState.Idle &&
                        state !is IdentityUnlockUiState.Loading
                }.first()
        }

    private fun verifyNoPersistenceCalls() {
        coVerify(exactly = 0) { identityRepository.rewrapKeyWithDeviceKey(any(), any()) }
        coVerify(exactly = 0) { settingsRepository.setNeedsIdentityUnlock(any()) }
        verifyNoDestructiveIdentityCalls()
    }

    private fun verifyNoDestructiveIdentityCalls() {
        coVerify(exactly = 0) { identityRepository.deleteIdentity(any()) }
        coVerify(exactly = 0) {
            identityRepository.createIdentity(any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { identityRepository.switchActiveIdentity(any()) }
    }
}
