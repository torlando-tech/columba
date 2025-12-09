package com.lxmf.messenger.repository

import app.cash.turbine.test
import com.lxmf.messenger.data.database.dao.InterfaceDao
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for InterfaceRepository.
 * Tests corruption handling and interface conversion logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InterfaceRepositoryTest {
    private lateinit var mockDao: InterfaceDao
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockDao = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Helper Functions ==========

    private fun createValidAutoInterfaceEntity(
        id: Long = 1,
        name: String = "Auto Discovery",
        enabled: Boolean = true,
    ) = InterfaceEntity(
        id = id,
        name = name,
        type = "AutoInterface",
        enabled = enabled,
        configJson = """{"group_id":"default","discovery_scope":"link","mode":"full"}""",
        displayOrder = 0,
    )

    private fun createValidTcpClientEntity(
        id: Long = 2,
        name: String = "TCP Server",
        enabled: Boolean = true,
    ) = InterfaceEntity(
        id = id,
        name = name,
        type = "TCPClient",
        enabled = enabled,
        configJson = """{"target_host":"10.0.0.1","target_port":4242,"kiss_framing":false,"mode":"full"}""",
        displayOrder = 1,
    )

    private fun createValidRNodeEntity(
        id: Long = 3,
        name: String = "RNode LoRa",
        enabled: Boolean = true,
    ) = InterfaceEntity(
        id = id,
        name = name,
        type = "RNode",
        enabled = enabled,
        configJson =
            """{"target_device_name":"RNode-BT","connection_mode":"ble",""" +
                """"frequency":915000000,"bandwidth":125000,"tx_power":7,""" +
                """"spreading_factor":7,"coding_rate":5,"mode":"full","enable_framebuffer":true}""",
        displayOrder = 2,
    )

    private fun createValidAndroidBleEntity(
        id: Long = 4,
        name: String = "Bluetooth LE",
        enabled: Boolean = true,
    ) = InterfaceEntity(
        id = id,
        name = name,
        type = "AndroidBLE",
        enabled = enabled,
        configJson = """{"device_name":"MyDevice","max_connections":7,"mode":"full"}""",
        displayOrder = 3,
    )

    // ========== Corruption Handling Tests ==========

    @Test
    fun `enabledInterfaces skips RNode with missing target_device_name field`() =
        runTest {
            // Given: One valid interface and one RNode with missing target_device_name
            val corruptedRNode =
                InterfaceEntity(
                    id = 1,
                    name = "Corrupted RNode",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"frequency":915000000,"bandwidth":125000}""", // Missing "target_device_name"
                    displayOrder = 0,
                )
            val validAuto = createValidAutoInterfaceEntity(id = 2)

            // Set up mocks and create repository
            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(corruptedRNode, validAuto))
            val repository = InterfaceRepository(mockDao)

            // When: Collecting enabled interfaces
            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                // Then: Only the valid interface is returned
                assertEquals(1, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.AutoInterface)
                assertEquals("Auto Discovery", interfaces[0].name)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces skips RNode with empty target_device_name field`() =
        runTest {
            val corruptedRNode =
                InterfaceEntity(
                    id = 1,
                    name = "Empty Device Name RNode",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"target_device_name":"","frequency":915000000}""", // Empty target_device_name
                    displayOrder = 0,
                )
            val validTcp = createValidTcpClientEntity(id = 2)

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(corruptedRNode, validTcp))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                assertEquals(1, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.TCPClient)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces skips interface with invalid JSON`() =
        runTest {
            val invalidJson =
                InterfaceEntity(
                    id = 1,
                    name = "Invalid JSON",
                    type = "AutoInterface",
                    enabled = true,
                    configJson = "not valid json {{{",
                    displayOrder = 0,
                )
            val validBle = createValidAndroidBleEntity(id = 2)

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(invalidJson, validBle))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                assertEquals(1, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.AndroidBLE)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces skips interface with unknown type`() =
        runTest {
            val unknownType =
                InterfaceEntity(
                    id = 1,
                    name = "Unknown Interface",
                    type = "FutureInterface",
                    enabled = true,
                    configJson = """{"some":"config"}""",
                    displayOrder = 0,
                )
            val validRNode = createValidRNodeEntity(id = 2)

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(unknownType, validRNode))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                assertEquals(1, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.RNode)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces skips TCPClient with invalid port`() =
        runTest {
            val invalidPort =
                InterfaceEntity(
                    id = 1,
                    name = "Invalid Port TCP",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"target_host":"10.0.0.1","target_port":99999}""", // Port > 65535
                    displayOrder = 0,
                )
            val validAuto = createValidAutoInterfaceEntity(id = 2)

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(invalidPort, validAuto))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                assertEquals(1, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.AutoInterface)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `allInterfaces skips corrupted interfaces`() =
        runTest {
            val corrupted =
                InterfaceEntity(
                    id = 1,
                    name = "Corrupted",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"no_device":"here"}""",
                    displayOrder = 0,
                )
            val valid = createValidAutoInterfaceEntity(id = 2)

            every { mockDao.getAllInterfaces() } returns flowOf(listOf(corrupted, valid))
            every { mockDao.getEnabledInterfaces() } returns flowOf(emptyList())
            val repository = InterfaceRepository(mockDao)

            repository.allInterfaces.test {
                val interfaces = awaitItem()

                assertEquals(1, interfaces.size)
                assertEquals("Auto Discovery", interfaces[0].name)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `allInterfaces returns empty list when all interfaces are corrupted`() =
        runTest {
            val corrupted1 =
                InterfaceEntity(
                    id = 1,
                    name = "Corrupted1",
                    type = "RNode",
                    enabled = true,
                    configJson = """{}""", // Missing target_device_name
                    displayOrder = 0,
                )
            val corrupted2 =
                InterfaceEntity(
                    id = 2,
                    name = "Corrupted2",
                    type = "UnknownType",
                    enabled = true,
                    configJson = """{"data":"test"}""",
                    displayOrder = 1,
                )

            every { mockDao.getAllInterfaces() } returns flowOf(listOf(corrupted1, corrupted2))
            every { mockDao.getEnabledInterfaces() } returns flowOf(emptyList())
            val repository = InterfaceRepository(mockDao)

            repository.allInterfaces.test {
                val interfaces = awaitItem()

                assertTrue(interfaces.isEmpty())

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Valid Interface Conversion Tests ==========

    @Test
    fun `enabledInterfaces correctly converts valid AutoInterface`() =
        runTest {
            val entity = createValidAutoInterfaceEntity()
            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(entity))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                assertEquals(1, interfaces.size)
                val config = interfaces[0] as InterfaceConfig.AutoInterface
                assertEquals("Auto Discovery", config.name)
                assertEquals("default", config.groupId)
                assertEquals("link", config.discoveryScope)
                assertNull(config.discoveryPort)
                assertNull(config.dataPort)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `entityToConfig parses AutoInterface with only discovery_port`() =
        runTest {
            val entity =
                InterfaceEntity(
                    id = 1,
                    name = "Auto With Discovery Port",
                    type = "AutoInterface",
                    enabled = true,
                    configJson = """{"group_id":"","discovery_scope":"link","discovery_port":29716,"mode":"full"}""",
                    displayOrder = 0,
                )
            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(entity))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                assertEquals(1, interfaces.size)
                val config = interfaces[0] as InterfaceConfig.AutoInterface
                assertEquals(29716, config.discoveryPort)
                assertNull(config.dataPort)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `entityToConfig parses AutoInterface with only data_port`() =
        runTest {
            val entity =
                InterfaceEntity(
                    id = 1,
                    name = "Auto With Data Port",
                    type = "AutoInterface",
                    enabled = true,
                    configJson = """{"group_id":"","discovery_scope":"link","data_port":42671,"mode":"full"}""",
                    displayOrder = 0,
                )
            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(entity))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                assertEquals(1, interfaces.size)
                val config = interfaces[0] as InterfaceConfig.AutoInterface
                assertNull(config.discoveryPort)
                assertEquals(42671, config.dataPort)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces correctly converts valid TCPClient`() =
        runTest {
            val entity = createValidTcpClientEntity()
            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(entity))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                assertEquals(1, interfaces.size)
                val config = interfaces[0] as InterfaceConfig.TCPClient
                assertEquals("TCP Server", config.name)
                assertEquals("10.0.0.1", config.targetHost)
                assertEquals(4242, config.targetPort)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces correctly converts valid RNode`() =
        runTest {
            val entity = createValidRNodeEntity()
            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(entity))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                assertEquals(1, interfaces.size)
                val config = interfaces[0] as InterfaceConfig.RNode
                assertEquals("RNode LoRa", config.name)
                assertEquals("RNode-BT", config.targetDeviceName)
                assertEquals(915000000L, config.frequency)
                assertEquals(125000, config.bandwidth)
                assertEquals(7, config.txPower)
                assertEquals(7, config.spreadingFactor)
                assertEquals(5, config.codingRate)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces correctly converts valid AndroidBLE`() =
        runTest {
            val entity = createValidAndroidBleEntity()
            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(entity))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                assertEquals(1, interfaces.size)
                val config = interfaces[0] as InterfaceConfig.AndroidBLE
                assertEquals("Bluetooth LE", config.name)
                assertEquals("MyDevice", config.deviceName)
                assertEquals(7, config.maxConnections)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces handles mix of valid and corrupted interfaces`() =
        runTest {
            val validAuto = createValidAutoInterfaceEntity(id = 1)
            val corruptedRNode =
                InterfaceEntity(
                    id = 2,
                    name = "Bad RNode",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"frequency":915000000}""", // Missing target_device_name
                    displayOrder = 1,
                )
            val validTcp = createValidTcpClientEntity(id = 3)
            val invalidJson =
                InterfaceEntity(
                    id = 4,
                    name = "Bad JSON",
                    type = "AutoInterface",
                    enabled = true,
                    configJson = "{broken",
                    displayOrder = 2,
                )
            val validBle = createValidAndroidBleEntity(id = 5)

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns
                flowOf(
                    listOf(validAuto, corruptedRNode, validTcp, invalidJson, validBle),
                )
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                // Only 3 valid interfaces should be returned
                assertEquals(3, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.AutoInterface)
                assertTrue(interfaces[1] is InterfaceConfig.TCPClient)
                assertTrue(interfaces[2] is InterfaceConfig.AndroidBLE)

                cancelAndIgnoreRemainingEvents()
            }
        }
}
