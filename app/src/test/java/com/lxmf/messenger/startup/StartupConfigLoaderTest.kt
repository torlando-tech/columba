package com.lxmf.messenger.startup

import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for StartupConfigLoader.
 * Tests parallel configuration loading from repositories.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupConfigLoaderTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var identityRepository: IdentityRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var loader: StartupConfigLoader

    private val testIdentity = LocalIdentityEntity(
        identityHash = "test_hash_123",
        displayName = "Test User",
        destinationHash = "dest_hash_456",
        filePath = "/data/identity_test",
        createdTimestamp = System.currentTimeMillis(),
        lastUsedTimestamp = System.currentTimeMillis(),
        isActive = true,
    )

    private val testInterface = InterfaceConfig.AutoInterface(
        name = "Auto Discovery",
        enabled = true,
        mode = "full",
        groupId = "default",
        discoveryScope = "link",
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        interfaceRepository = mockk(relaxed = true)
        identityRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        loader = StartupConfigLoader(interfaceRepository, identityRepository, settingsRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Success Cases ==========

    @Test
    fun `loadConfig returns complete config when all repositories succeed`() = runTest {
        // Arrange
        coEvery { interfaceRepository.enabledInterfaces } returns flowOf(listOf(testInterface))
        coEvery { identityRepository.getActiveIdentitySync() } returns testIdentity
        coEvery { settingsRepository.preferOwnInstanceFlow } returns flowOf(true)
        coEvery { settingsRepository.rpcKeyFlow } returns flowOf("test-rpc-key")
        coEvery { settingsRepository.getTransportNodeEnabled() } returns false
        coEvery { settingsRepository.maxInboundAttachmentSizeKbFlow } returns flowOf(8192)

        // Act
        val config = loader.loadConfig()

        // Assert
        assertEquals(1, config.interfaces.size)
        assertEquals(testInterface, config.interfaces[0])
        assertEquals(testIdentity, config.identity)
        assertTrue(config.preferOwn)
        assertEquals("test-rpc-key", config.rpcKey)
        assertFalse(config.transport)
        assertEquals(8 * 1024 * 1024, config.maxInboundAttachmentSizeBytes)
    }

    @Test
    fun `loadConfig handles null identity correctly`() = runTest {
        // Arrange
        coEvery { interfaceRepository.enabledInterfaces } returns flowOf(emptyList())
        coEvery { identityRepository.getActiveIdentitySync() } returns null
        coEvery { settingsRepository.preferOwnInstanceFlow } returns flowOf(false)
        coEvery { settingsRepository.rpcKeyFlow } returns flowOf(null)
        coEvery { settingsRepository.getTransportNodeEnabled() } returns true
        coEvery { settingsRepository.maxInboundAttachmentSizeKbFlow } returns flowOf(8192)

        // Act
        val config = loader.loadConfig()

        // Assert
        assertTrue(config.interfaces.isEmpty())
        assertNull(config.identity)
        assertFalse(config.preferOwn)
        assertNull(config.rpcKey)
        assertTrue(config.transport)
    }

    @Test
    fun `loadConfig handles multiple interfaces`() = runTest {
        // Arrange
        val tcpInterface = InterfaceConfig.TCPClient(
            name = "TCP Server",
            enabled = true,
            mode = "full",
            targetHost = "10.0.0.1",
            targetPort = 4242,
            kissFraming = false,
        )
        val interfaces = listOf(testInterface, tcpInterface)

        coEvery { interfaceRepository.enabledInterfaces } returns flowOf(interfaces)
        coEvery { identityRepository.getActiveIdentitySync() } returns testIdentity
        coEvery { settingsRepository.preferOwnInstanceFlow } returns flowOf(false)
        coEvery { settingsRepository.rpcKeyFlow } returns flowOf(null)
        coEvery { settingsRepository.getTransportNodeEnabled() } returns false
        coEvery { settingsRepository.maxInboundAttachmentSizeKbFlow } returns flowOf(8192)

        // Act
        val config = loader.loadConfig()

        // Assert
        assertEquals(2, config.interfaces.size)
        assertTrue(config.interfaces[0] is InterfaceConfig.AutoInterface)
        assertTrue(config.interfaces[1] is InterfaceConfig.TCPClient)
    }

    // ========== Parallel Execution Tests ==========

    @Test
    fun `loadConfig calls all repositories`() = runTest {
        // Arrange
        coEvery { interfaceRepository.enabledInterfaces } returns flowOf(emptyList())
        coEvery { identityRepository.getActiveIdentitySync() } returns null
        coEvery { settingsRepository.preferOwnInstanceFlow } returns flowOf(false)
        coEvery { settingsRepository.rpcKeyFlow } returns flowOf(null)
        coEvery { settingsRepository.getTransportNodeEnabled() } returns false
        coEvery { settingsRepository.maxInboundAttachmentSizeKbFlow } returns flowOf(8192)

        // Act
        loader.loadConfig()
        advanceUntilIdle()

        // Assert - verify all repository methods were called
        coVerify(exactly = 1) { interfaceRepository.enabledInterfaces }
        coVerify(exactly = 1) { identityRepository.getActiveIdentitySync() }
        coVerify(exactly = 1) { settingsRepository.preferOwnInstanceFlow }
        coVerify(exactly = 1) { settingsRepository.rpcKeyFlow }
        coVerify(exactly = 1) { settingsRepository.getTransportNodeEnabled() }
        coVerify(exactly = 1) { settingsRepository.maxInboundAttachmentSizeKbFlow }
    }

    @Test
    fun `loadConfig completes successfully with all repository calls`() = runTest {
        // Arrange - all repositories return values
        coEvery { interfaceRepository.enabledInterfaces } returns flowOf(emptyList())
        coEvery { identityRepository.getActiveIdentitySync() } returns null
        coEvery { settingsRepository.preferOwnInstanceFlow } returns flowOf(false)
        coEvery { settingsRepository.rpcKeyFlow } returns flowOf(null)
        coEvery { settingsRepository.getTransportNodeEnabled() } returns false
        coEvery { settingsRepository.maxInboundAttachmentSizeKbFlow } returns flowOf(8192)

        // Act
        val config = loader.loadConfig()
        advanceUntilIdle()

        // Assert - all values loaded correctly
        assertTrue(config.interfaces.isEmpty())
        assertNull(config.identity)
        assertFalse(config.preferOwn)
        assertNull(config.rpcKey)
        assertFalse(config.transport)
    }

    // ========== Data Class Tests ==========

    @Test
    fun `StartupConfig data class properties are accessible`() = runTest {
        // Arrange
        coEvery { interfaceRepository.enabledInterfaces } returns flowOf(listOf(testInterface))
        coEvery { identityRepository.getActiveIdentitySync() } returns testIdentity
        coEvery { settingsRepository.preferOwnInstanceFlow } returns flowOf(true)
        coEvery { settingsRepository.rpcKeyFlow } returns flowOf("key")
        coEvery { settingsRepository.getTransportNodeEnabled() } returns true
        coEvery { settingsRepository.maxInboundAttachmentSizeKbFlow } returns flowOf(8192)

        // Act
        val config = loader.loadConfig()

        // Assert - access each property directly instead of destructuring
        assertEquals(listOf(testInterface), config.interfaces)
        assertEquals(testIdentity, config.identity)
        assertTrue(config.preferOwn)
        assertEquals("key", config.rpcKey)
        assertTrue(config.transport)
        assertEquals(8 * 1024 * 1024, config.maxInboundAttachmentSizeBytes)
    }

    @Test
    fun `StartupConfig data class equals and hashCode work correctly`() {
        val config1 = StartupConfigLoader.StartupConfig(
            interfaces = listOf(testInterface),
            identity = testIdentity,
            preferOwn = true,
            rpcKey = "key",
            transport = false,
            maxInboundAttachmentSizeBytes = 8 * 1024 * 1024,
        )
        val config2 = StartupConfigLoader.StartupConfig(
            interfaces = listOf(testInterface),
            identity = testIdentity,
            preferOwn = true,
            rpcKey = "key",
            transport = false,
            maxInboundAttachmentSizeBytes = 8 * 1024 * 1024,
        )
        val config3 = StartupConfigLoader.StartupConfig(
            interfaces = emptyList(),
            identity = null,
            preferOwn = false,
            rpcKey = null,
            transport = true,
            maxInboundAttachmentSizeBytes = 0,
        )

        assertEquals(config1, config2)
        assertEquals(config1.hashCode(), config2.hashCode())
        assertFalse(config1 == config3)
    }
}
