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
        // Each test stubs the specific methods needed
        mockDao = mockk()
        // Default stubs for interface count flows - tests don't use these directly
        every { mockDao.getEnabledInterfaceCount() } returns flowOf(0)
        every { mockDao.getTotalInterfaceCount() } returns flowOf(0)
        every { mockDao.hasEnabledBluetoothInterface() } returns flowOf(false)
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

    private fun createValidTcpServerEntity(
        id: Long = 6,
        name: String = "TCP Server",
        enabled: Boolean = true,
    ) = InterfaceEntity(
        id = id,
        name = name,
        type = "TCPServer",
        enabled = enabled,
        configJson = """{"listen_ip":"0.0.0.0","listen_port":4242,"mode":"full"}""",
        displayOrder = 5,
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

    // ========== TCP RNode Tests ==========

    private fun createValidTcpRNodeEntity(
        id: Long = 5,
        name: String = "RNode WiFi",
        enabled: Boolean = true,
    ) = InterfaceEntity(
        id = id,
        name = name,
        type = "RNode",
        enabled = enabled,
        configJson =
            """{"target_device_name":"","connection_mode":"tcp","tcp_host":"10.0.0.50","tcp_port":7633,""" +
                """"frequency":915000000,"bandwidth":125000,"tx_power":7,""" +
                """"spreading_factor":7,"coding_rate":5,"mode":"full","enable_framebuffer":true}""",
        displayOrder = 4,
    )

    @Test
    fun `enabledInterfaces correctly converts valid TCP RNode`() =
        runTest {
            val entity = createValidTcpRNodeEntity()
            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(entity))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                assertEquals(1, interfaces.size)
                val config = interfaces[0] as InterfaceConfig.RNode
                assertEquals("RNode WiFi", config.name)
                assertEquals("tcp", config.connectionMode)
                assertEquals("10.0.0.50", config.tcpHost)
                assertEquals(7633, config.tcpPort)
                assertEquals("", config.targetDeviceName) // Empty for TCP mode
                assertEquals(915000000L, config.frequency)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces skips TCP RNode with empty tcp_host`() =
        runTest {
            val invalidTcpRNode =
                InterfaceEntity(
                    id = 1,
                    name = "Invalid TCP RNode",
                    type = "RNode",
                    enabled = true,
                    configJson =
                        """{"target_device_name":"","connection_mode":"tcp","tcp_host":"","tcp_port":7633,""" +
                            """"frequency":915000000}""",
                    displayOrder = 0,
                )
            val validAuto = createValidAutoInterfaceEntity(id = 2)

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(invalidTcpRNode, validAuto))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                // TCP RNode with empty host should be skipped
                assertEquals(1, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.AutoInterface)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces allows TCP RNode without target_device_name`() =
        runTest {
            // TCP mode should work even without target_device_name (it's not used)
            val tcpRNodeNoDeviceName =
                InterfaceEntity(
                    id = 1,
                    name = "TCP RNode No Device",
                    type = "RNode",
                    enabled = true,
                    configJson =
                        """{"connection_mode":"tcp","tcp_host":"192.168.1.100","tcp_port":7633,""" +
                            """"frequency":915000000,"bandwidth":125000}""",
                    displayOrder = 0,
                )

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(tcpRNodeNoDeviceName))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                assertEquals(1, interfaces.size)
                val config = interfaces[0] as InterfaceConfig.RNode
                assertEquals("tcp", config.connectionMode)
                assertEquals("192.168.1.100", config.tcpHost)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces skips TCP RNode with invalid tcp_port`() =
        runTest {
            val invalidPortRNode =
                InterfaceEntity(
                    id = 1,
                    name = "Invalid Port TCP RNode",
                    type = "RNode",
                    enabled = true,
                    configJson =
                        """{"connection_mode":"tcp","tcp_host":"10.0.0.1","tcp_port":99999,""" +
                            """"frequency":915000000}""",
                    displayOrder = 0,
                )
            val validAuto = createValidAutoInterfaceEntity(id = 2)

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(invalidPortRNode, validAuto))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                // TCP RNode with invalid port should be skipped
                assertEquals(1, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.AutoInterface)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Additional Validation Tests ==========

    @Test
    fun `enabledInterfaces skips UDP with invalid listen port`() =
        runTest {
            val invalidListenPort =
                InterfaceEntity(
                    id = 1,
                    name = "Invalid Listen Port UDP",
                    type = "UDP",
                    enabled = true,
                    configJson = """{"listen_ip":"0.0.0.0","listen_port":70000,"forward_ip":"255.255.255.255","forward_port":4242}""",
                    displayOrder = 0,
                )
            val validTcp = createValidTcpClientEntity(id = 2)

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(invalidListenPort, validTcp))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                // UDP with invalid listen port should be skipped
                assertEquals(1, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.TCPClient)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces skips UDP with invalid forward port`() =
        runTest {
            val invalidForwardPort =
                InterfaceEntity(
                    id = 1,
                    name = "Invalid Forward Port UDP",
                    type = "UDP",
                    enabled = true,
                    configJson = """{"listen_ip":"0.0.0.0","listen_port":4242,"forward_ip":"255.255.255.255","forward_port":-1}""",
                    displayOrder = 0,
                )
            val validAuto = createValidAutoInterfaceEntity(id = 2)

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(invalidForwardPort, validAuto))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                // UDP with invalid forward port should be skipped
                assertEquals(1, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.AutoInterface)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces skips AutoInterface with invalid discovery_port`() =
        runTest {
            val invalidDiscoveryPort =
                InterfaceEntity(
                    id = 1,
                    name = "Invalid Discovery Port Auto",
                    type = "AutoInterface",
                    enabled = true,
                    configJson = """{"group_id":"default","discovery_scope":"link","discovery_port":100000,"mode":"full"}""",
                    displayOrder = 0,
                )
            val validTcp = createValidTcpClientEntity(id = 2)

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(invalidDiscoveryPort, validTcp))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                // AutoInterface with invalid discovery port should be skipped
                assertEquals(1, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.TCPClient)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces skips AutoInterface with invalid data_port`() =
        runTest {
            val invalidDataPort =
                InterfaceEntity(
                    id = 1,
                    name = "Invalid Data Port Auto",
                    type = "AutoInterface",
                    enabled = true,
                    configJson = """{"group_id":"default","discovery_scope":"link","data_port":0,"mode":"full"}""",
                    displayOrder = 0,
                )
            val validRNode = createValidRNodeEntity(id = 2)

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(invalidDataPort, validRNode))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                // AutoInterface with invalid data port should be skipped
                assertEquals(1, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.RNode)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces skips AndroidBLE with invalid device_name`() =
        runTest {
            val invalidDeviceName =
                InterfaceEntity(
                    id = 1,
                    name = "Invalid Device Name BLE",
                    type = "AndroidBLE",
                    enabled = true,
                    // Device name too long (max is 30 characters)
                    configJson = """{"device_name":"ThisDeviceNameIsWayTooLongToBeValid1234567890","max_connections":7,"mode":"full"}""",
                    displayOrder = 0,
                )
            val validTcp = createValidTcpClientEntity(id = 2)

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(invalidDeviceName, validTcp))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                // AndroidBLE with invalid device name should be skipped
                assertEquals(1, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.TCPClient)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces skips TCP RNode with invalid tcp_host format`() =
        runTest {
            // Host with invalid characters should be rejected
            val invalidHostRNode =
                InterfaceEntity(
                    id = 1,
                    name = "Invalid Host TCP RNode",
                    type = "RNode",
                    enabled = true,
                    configJson =
                        """{"connection_mode":"tcp","tcp_host":"invalid host with spaces","tcp_port":7633,""" +
                            """"frequency":915000000}""",
                    displayOrder = 0,
                )
            val validAuto = createValidAutoInterfaceEntity(id = 2)

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(invalidHostRNode, validAuto))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                // TCP RNode with invalid hostname format should be skipped
                assertEquals(1, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.AutoInterface)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== TCPServer Tests ==========

    @Test
    fun `enabledInterfaces correctly converts valid TCPServer`() =
        runTest {
            val entity = createValidTcpServerEntity()
            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(entity))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                assertEquals(1, interfaces.size)
                val config = interfaces[0] as InterfaceConfig.TCPServer
                assertEquals("TCP Server", config.name)
                assertEquals("0.0.0.0", config.listenIp)
                assertEquals(4242, config.listenPort)
                assertEquals("full", config.mode)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces skips TCPServer with invalid listen port`() =
        runTest {
            val invalidPort =
                InterfaceEntity(
                    id = 1,
                    name = "Invalid Port TCP Server",
                    type = "TCPServer",
                    enabled = true,
                    configJson = """{"listen_ip":"0.0.0.0","listen_port":99999,"mode":"full"}""",
                    displayOrder = 0,
                )
            val validAuto = createValidAutoInterfaceEntity(id = 2)

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(invalidPort, validAuto))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                // TCPServer with invalid port should be skipped
                assertEquals(1, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.AutoInterface)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces skips TCPServer with invalid listen IP`() =
        runTest {
            val invalidIp =
                InterfaceEntity(
                    id = 1,
                    name = "Invalid IP TCP Server",
                    type = "TCPServer",
                    enabled = true,
                    configJson = """{"listen_ip":"invalid host with spaces","listen_port":4242,"mode":"full"}""",
                    displayOrder = 0,
                )
            val validTcp = createValidTcpClientEntity(id = 2)

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(invalidIp, validTcp))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                // TCPServer with invalid IP should be skipped
                assertEquals(1, interfaces.size)
                assertTrue(interfaces[0] is InterfaceConfig.TCPClient)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enabledInterfaces converts TCPServer with custom port`() =
        runTest {
            val customPort =
                InterfaceEntity(
                    id = 1,
                    name = "Custom Port Server",
                    type = "TCPServer",
                    enabled = true,
                    configJson = """{"listen_ip":"192.168.1.1","listen_port":8080,"mode":"gateway"}""",
                    displayOrder = 0,
                )

            every { mockDao.getAllInterfaces() } returns flowOf(emptyList())
            every { mockDao.getEnabledInterfaces() } returns flowOf(listOf(customPort))
            val repository = InterfaceRepository(mockDao)

            repository.enabledInterfaces.test {
                val interfaces = awaitItem()

                assertEquals(1, interfaces.size)
                val config = interfaces[0] as InterfaceConfig.TCPServer
                assertEquals("Custom Port Server", config.name)
                assertEquals("192.168.1.1", config.listenIp)
                assertEquals(8080, config.listenPort)
                assertEquals("gateway", config.mode)

                cancelAndIgnoreRemainingEvents()
            }
        }
}
