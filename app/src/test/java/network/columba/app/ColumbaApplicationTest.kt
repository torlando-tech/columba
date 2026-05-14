package network.columba.app

import network.columba.app.rns.api.RnsLxmf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ColumbaApplication startup behavior.
 * Tests timeout handling and utility functions used during initialization.
 *
 * A.10b removed the legacy `getStatus()` / stale-config-flag startup branch —
 * `getStatus()` was a no-op on the kotlin backend and the `"READY"` string
 * comparison never matched, so that branch was dead code. The tests covering
 * it were deleted with the production logic. The `getLxmfIdentity` startup
 * verification path survives, now routed through [RnsLxmf].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ColumbaApplicationTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockRnsLxmf: RnsLxmf

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockRnsLxmf = mockk<RnsLxmf>()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== IPC Timeout Constants ==========

    @Test
    fun `IPC_TIMEOUT_MS is set to 5 seconds`() {
        assertEquals(5000L, ColumbaApplication.IPC_TIMEOUT_MS)
    }

    // ========== Timeout Behavior Tests ==========

    @Test
    fun `withTimeoutOrNull for getLxmfIdentity returns identity when fast`() =
        runTest {
            // Arrange
            val mockIdentity = mockk<network.columba.app.rns.api.model.Identity>()
            coEvery { mockRnsLxmf.getLxmfIdentity() } coAnswers {
                delay(100)
                Result.success(mockIdentity)
            }

            // Act
            val result =
                withTimeoutOrNull(ColumbaApplication.IPC_TIMEOUT_MS) {
                    mockRnsLxmf.getLxmfIdentity().getOrNull()
                }
            advanceUntilIdle()

            // Assert
            assertNotNull(result)
            assertEquals(mockIdentity, result)
        }

    @Test
    fun `getLxmfIdentity returns identity when mock succeeds`() =
        runTest {
            // Arrange
            val mockIdentity = mockk<network.columba.app.rns.api.model.Identity>()
            coEvery { mockRnsLxmf.getLxmfIdentity() } returns Result.success(mockIdentity)

            // Act
            val result = mockRnsLxmf.getLxmfIdentity().getOrNull()
            advanceUntilIdle()

            // Assert
            assertNotNull(result)
            assertEquals(mockIdentity, result)
        }

    // ========== ByteArray/Hex Utility Tests ==========

    @Test
    fun `toHexString converts byte array correctly`() {
        // These are the private extension functions from ColumbaApplication
        // Testing the logic pattern they implement
        val bytes = byteArrayOf(0x12, 0x34, 0xAB.toByte(), 0xCD.toByte())
        val expected = "1234abcd"

        val result = bytes.joinToString("") { "%02x".format(it) }

        assertEquals(expected, result)
    }

    @Test
    fun `toHexString handles empty array`() {
        val bytes = byteArrayOf()
        val expected = ""

        val result = bytes.joinToString("") { "%02x".format(it) }

        assertEquals(expected, result)
    }

    @Test
    fun `hexStringToByteArray parses hex string correctly`() {
        val hex = "1234abcd"
        val expected = byteArrayOf(0x12, 0x34, 0xAB.toByte(), 0xCD.toByte())

        val result =
            hex
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()

        assertEquals(expected.toList(), result.toList())
    }

    @Test
    fun `hexStringToByteArray handles empty string`() {
        val hex = ""
        val expected = byteArrayOf()

        val result =
            hex
                .chunked(2)
                .filter { it.isNotEmpty() }
                .map { it.toInt(16).toByte() }
                .toByteArray()

        assertEquals(expected.toList(), result.toList())
    }
}
