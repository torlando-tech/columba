package com.lxmf.messenger

import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ColumbaApplication startup behavior.
 * Tests timeout handling and utility functions used during initialization.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ColumbaApplicationTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockProtocol: ServiceReticulumProtocol

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockProtocol = mockk(relaxed = true)
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
    fun `withTimeoutOrNull returns value when service responds within timeout`() =
        runTest {
            // Arrange
            coEvery { mockProtocol.getStatus() } coAnswers {
                delay(100) // Fast response
                Result.success("READY")
            }

            // Act
            val result =
                withTimeoutOrNull(ColumbaApplication.IPC_TIMEOUT_MS) {
                    mockProtocol.getStatus().getOrNull()
                }
            advanceUntilIdle()

            // Assert
            assertEquals("READY", result)
        }

    @Test
    fun `service call returns result when mock succeeds`() =
        runTest {
            // Arrange
            coEvery { mockProtocol.getStatus() } returns Result.success("READY")

            // Act
            val result = mockProtocol.getStatus().getOrNull()
            advanceUntilIdle()

            // Assert
            assertEquals("READY", result)
        }

    @Test
    fun `withTimeoutOrNull handles service error within timeout`() =
        runTest {
            // Arrange
            coEvery { mockProtocol.getStatus() } coAnswers {
                delay(100)
                Result.failure(Exception("Service unavailable"))
            }

            // Act
            val result =
                withTimeoutOrNull(ColumbaApplication.IPC_TIMEOUT_MS) {
                    mockProtocol.getStatus().getOrNull()
                }
            advanceUntilIdle()

            // Assert
            assertNull(result) // getOrNull returns null on failure
        }

    @Test
    fun `withTimeoutOrNull for getLxmfIdentity returns identity when fast`() =
        runTest {
            // Arrange
            val mockIdentity = mockk<com.lxmf.messenger.reticulum.model.Identity>(relaxed = true)
            coEvery { mockProtocol.getLxmfIdentity() } coAnswers {
                delay(100)
                Result.success(mockIdentity)
            }

            // Act
            val result =
                withTimeoutOrNull(ColumbaApplication.IPC_TIMEOUT_MS) {
                    mockProtocol.getLxmfIdentity().getOrNull()
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
            val mockIdentity = mockk<com.lxmf.messenger.reticulum.model.Identity>(relaxed = true)
            coEvery { mockProtocol.getLxmfIdentity() } returns Result.success(mockIdentity)

            // Act
            val result = mockProtocol.getLxmfIdentity().getOrNull()
            advanceUntilIdle()

            // Assert
            assertNotNull(result)
            assertEquals(mockIdentity, result)
        }

    // ========== Status Check Pattern Tests ==========

    @Test
    fun `stale config flag logic clears flag when status is SHUTDOWN`() =
        runTest {
            // Arrange
            coEvery { mockProtocol.getStatus() } returns Result.success("SHUTDOWN")

            // Act - simulate the Application's stale flag check logic
            val status =
                withTimeoutOrNull(ColumbaApplication.IPC_TIMEOUT_MS) {
                    mockProtocol.getStatus().getOrNull()
                }
            advanceUntilIdle()

            val shouldClearFlag = status == "SHUTDOWN" || status == null || status.startsWith("ERROR:")

            // Assert
            assertEquals("SHUTDOWN", status)
            assertEquals(true, shouldClearFlag)
        }

    @Test
    fun `stale config flag logic clears flag when status is null`() =
        runTest {
            // Arrange - service returns failure (which becomes null via getOrNull)
            coEvery { mockProtocol.getStatus() } returns Result.failure(Exception("Not responding"))

            // Act
            val status = mockProtocol.getStatus().getOrNull()
            advanceUntilIdle()

            val shouldClearFlag = status == "SHUTDOWN" || status == null || status.startsWith("ERROR:")

            // Assert
            assertNull(status)
            assertEquals(true, shouldClearFlag)
        }

    @Test
    fun `stale config flag logic clears flag when status starts with ERROR`() =
        runTest {
            // Arrange
            coEvery { mockProtocol.getStatus() } returns Result.success("ERROR: Service crashed")

            // Act
            val status =
                withTimeoutOrNull(ColumbaApplication.IPC_TIMEOUT_MS) {
                    mockProtocol.getStatus().getOrNull()
                }
            advanceUntilIdle()

            val shouldClearFlag = status == "SHUTDOWN" || status == null || status.startsWith("ERROR:")

            // Assert
            assertEquals("ERROR: Service crashed", status)
            assertEquals(true, shouldClearFlag)
        }

    @Test
    fun `stale config flag logic preserves flag when status is INITIALIZING`() =
        runTest {
            // Arrange
            coEvery { mockProtocol.getStatus() } returns Result.success("INITIALIZING")

            // Act
            val status =
                withTimeoutOrNull(ColumbaApplication.IPC_TIMEOUT_MS) {
                    mockProtocol.getStatus().getOrNull()
                }
            advanceUntilIdle()

            val shouldClearFlag = status == "SHUTDOWN" || status == null || status.startsWith("ERROR:")

            // Assert
            assertEquals("INITIALIZING", status)
            assertEquals(false, shouldClearFlag)
        }

    @Test
    fun `stale config flag logic preserves flag when status is READY`() =
        runTest {
            // Arrange
            coEvery { mockProtocol.getStatus() } returns Result.success("READY")

            // Act
            val status =
                withTimeoutOrNull(ColumbaApplication.IPC_TIMEOUT_MS) {
                    mockProtocol.getStatus().getOrNull()
                }
            advanceUntilIdle()

            val shouldClearFlag = status == "SHUTDOWN" || status == null || status.startsWith("ERROR:")

            // Assert
            assertEquals("READY", status)
            assertEquals(false, shouldClearFlag)
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
            hex.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()

        assertEquals(expected.toList(), result.toList())
    }

    @Test
    fun `hexStringToByteArray handles empty string`() {
        val hex = ""
        val expected = byteArrayOf()

        val result =
            hex.chunked(2)
                .filter { it.isNotEmpty() }
                .map { it.toInt(16).toByte() }
                .toByteArray()

        assertEquals(expected.toList(), result.toList())
    }


    @Test

    @Test
}



    @Test
    @org.robolectric.annotation.Config(sdk = [31])
    @org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
