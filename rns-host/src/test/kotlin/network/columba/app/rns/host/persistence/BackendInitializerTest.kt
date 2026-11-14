package network.columba.app.rns.host.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import network.columba.app.data.crypto.CorruptedKeyException
import network.columba.app.data.crypto.IdentityKeyProvider
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.model.ReticulumConfig
import network.columba.app.rns.host.persistence.BackendInitializer.InitOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the snapshot self-init contract that the boot receiver (and the START_STICKY
 * recovery path) depend on:
 *
 *  - no snapshot  ⇒ do nothing (a never-configured install must not spin up a stack)
 *  - key unavailable (password-locked / Keystore failure) ⇒ **never** initialize.
 *    Initializing with an absent key makes the native stack mint a fresh identity,
 *    orphaning the real one — the divergent-identity bug this guard exists for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackendInitializerTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var identityRepository: IdentityRepository
    private lateinit var keyProvider: IdentityKeyProvider
    private lateinit var core: RnsCore
    private lateinit var backend: RnsBackend
    private lateinit var initializer: BackendInitializer

    @Before
    fun setup() {
        ReticulumConfigSnapshot.clear(context)
        identityRepository = mockk()
        keyProvider = mockk()
        core = mockk()
        backend = mockk()
        every { backend.core } returns core
        coEvery { identityRepository.getActiveIdentitySync() } returns null
        initializer = BackendInitializer(context, identityRepository, keyProvider)
    }

    private fun sampleConfig() =
        ReticulumConfig(
            storagePath = "/data/data/test/files/reticulum",
            enabledInterfaces = emptyList(),
            displayName = "Test Node",
            enableTransport = false,
            shareInstanceHosting = false,
            deliveryIdentityKey = null,
        )

    @Test
    fun `no snapshot does not initialize`() =
        runBlocking {
            val outcome = initializer.initializeFromSnapshot(backend)

            assertEquals(InitOutcome.NO_SNAPSHOT, outcome)
            coVerify(exactly = 0) { core.initialize(any()) }
        }

    @Test
    fun `locked identity never initializes and reports KEY_UNAVAILABLE`() =
        runBlocking {
            ReticulumConfigSnapshot.write(context, sampleConfig(), identityHashHex = "abcd1234")
            coEvery { keyProvider.getDecryptedKeyData("abcd1234") } returns
                Result.failure(CorruptedKeyException("Password required for this identity"))

            val outcome = initializer.initializeFromSnapshot(backend)

            assertEquals(InitOutcome.KEY_UNAVAILABLE, outcome)
            coVerify(exactly = 0) { core.initialize(any()) }
        }

    @Test
    fun `snapshot with a usable key initializes with that key`() =
        runBlocking {
            val key = ByteArray(64) { it.toByte() }
            ReticulumConfigSnapshot.write(context, sampleConfig(), identityHashHex = "abcd1234")
            coEvery { keyProvider.getDecryptedKeyData("abcd1234") } returns Result.success(key)
            val captured = slot<ReticulumConfig>()
            coEvery { core.initialize(capture(captured)) } returns Result.success(Unit)

            val outcome = initializer.initializeFromSnapshot(backend)

            assertEquals(InitOutcome.INITIALIZED, outcome)
            assertTrue("delivery key must be handed to the native stack", captured.captured.deliveryIdentityKey!!.contentEquals(key))
        }

    @Test
    fun `first run with no identity and no active identity still initializes without a key`() =
        runBlocking {
            ReticulumConfigSnapshot.write(context, sampleConfig(), identityHashHex = null)
            val captured = slot<ReticulumConfig>()
            coEvery { core.initialize(capture(captured)) } returns Result.success(Unit)

            val outcome = initializer.initializeFromSnapshot(backend)

            assertEquals(InitOutcome.INITIALIZED, outcome)
            assertNull("the native stack creates the first identity", captured.captured.deliveryIdentityKey)
        }

    @Test
    fun `initialize failure is reported as FAILED`() =
        runBlocking {
            val key = ByteArray(64) { it.toByte() }
            ReticulumConfigSnapshot.write(context, sampleConfig(), identityHashHex = "abcd1234")
            coEvery { keyProvider.getDecryptedKeyData("abcd1234") } returns Result.success(key)
            coEvery { core.initialize(any()) } returns Result.failure(IllegalStateException("boom"))

            val outcome = initializer.initializeFromSnapshot(backend)

            assertEquals(InitOutcome.FAILED, outcome)
        }
}
