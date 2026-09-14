package network.columba.app.service

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.RnsTransportAdmin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SharedInstanceStatus.persist], the single shared write
 * path for the `isSharedInstance` DataStore flag consumed by the Settings
 * shared-instance banner.
 *
 * Both production call sites delegate here — ColumbaApplication.onCreate
 * (cold start) and InterfaceConfigManager.applyInterfaceChanges (toggle-off
 * / Apply & Restart) — so these tests cover the previously-untested
 * cold-start persistence logic in addition to the restart path.
 *
 * Regression: the flag was previously never written in production, so the
 * banner always showed "Using Columba's Own Instance" even while connected
 * to a shared instance (e.g. Sideband on 127.0.0.1:37428), and the
 * "Use Columba's own instance" toggle looked stuck after toggling off.
 */
class SharedInstanceStatusTest {
    private lateinit var transportAdmin: RnsTransportAdmin
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setup() {
        transportAdmin = mockk()
        settingsRepository = mockk()
    }

    @Test
    fun `persist - shared client persists true`() =
        runTest {
            // Given: connected to another app's shared master.
            coEvery { transportAdmin.isSharedInstanceAvailable() } returns true
            coEvery { transportAdmin.isHostingSharedInstance() } returns false
            coEvery { settingsRepository.saveIsSharedInstance(any()) } just Runs

            // When
            val result = SharedInstanceStatus.persist(transportAdmin, settingsRepository)

            // Then
            assertEquals(true, result)
            coVerify(exactly = 1) { settingsRepository.saveIsSharedInstance(true) }
        }

    @Test
    fun `persist - own instance persists false`() =
        runTest {
            // Given: no shared instance present at all.
            coEvery { transportAdmin.isSharedInstanceAvailable() } returns false
            coEvery { settingsRepository.saveIsSharedInstance(any()) } just Runs

            // When
            val result = SharedInstanceStatus.persist(transportAdmin, settingsRepository)

            // Then
            assertEquals(false, result)
            coVerify(exactly = 1) { settingsRepository.saveIsSharedInstance(false) }
            // Short-circuit: no hosting probe when nothing is available.
            coVerify(exactly = 0) { transportAdmin.isHostingSharedInstance() }
        }

    @Test
    fun `persist - hosting persists false so the host is not mislabelled a client`() =
        runTest {
            // Given: Columba itself is the shared master. isSharedInstanceAvailable
            // is true for hosting too, so the client signal must exclude it via
            // the hosting probe.
            coEvery { transportAdmin.isSharedInstanceAvailable() } returns true
            coEvery { transportAdmin.isHostingSharedInstance() } returns true
            coEvery { settingsRepository.saveIsSharedInstance(any()) } just Runs

            // When
            val result = SharedInstanceStatus.persist(transportAdmin, settingsRepository)

            // Then: hosting is driven by the separate isHostingSharedInstance UI
            // state, never the client flag.
            assertEquals(false, result)
            coVerify(exactly = 1) { settingsRepository.saveIsSharedInstance(false) }
        }

    @Test
    fun `persist - probe failure is best-effort and returns null`() =
        runTest {
            // Given: the availability probe throws (e.g. BackendNotReady).
            coEvery { transportAdmin.isSharedInstanceAvailable() } throws
                RuntimeException("backend not ready")

            // When
            val result = SharedInstanceStatus.persist(transportAdmin, settingsRepository)

            // Then: best-effort - null, no write, no rethrow.
            assertNull(result)
            coVerify(exactly = 0) { settingsRepository.saveIsSharedInstance(any()) }
        }

    @Test
    fun `persist - persistence failure is best-effort and returns null`() =
        runTest {
            // Given: probe succeeds but the DataStore write throws.
            coEvery { transportAdmin.isSharedInstanceAvailable() } returns true
            coEvery { transportAdmin.isHostingSharedInstance() } returns false
            coEvery { settingsRepository.saveIsSharedInstance(any()) } throws
                RuntimeException("datastore unavailable")

            // When
            val result = SharedInstanceStatus.persist(transportAdmin, settingsRepository)

            // Then
            assertNull(result)
        }

    @Test
    fun `persist - cancellation is rethrown not swallowed`() =
        runTest {
            // Given: the availability probe is cancelled (e.g. ViewModel scope
            // cleared while running).
            coEvery { transportAdmin.isSharedInstanceAvailable() } throws
                CancellationException("cancelled")

            // When / Then: cancellation must propagate so the surrounding
            // coroutine stops, rather than continuing post-cancellation.
            val thrown =
                runCatching { SharedInstanceStatus.persist(transportAdmin, settingsRepository) }
                    .exceptionOrNull()
            assertEquals(CancellationException::class.java, thrown?.javaClass)
            coVerify(exactly = 0) { settingsRepository.saveIsSharedInstance(any()) }
        }
}
