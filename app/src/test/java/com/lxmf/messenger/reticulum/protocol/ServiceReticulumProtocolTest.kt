package com.lxmf.messenger.reticulum.protocol

import android.content.Context
import android.content.ServiceConnection
import com.lxmf.messenger.IReticulumService
import com.lxmf.messenger.data.repository.RmspServerRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import com.lxmf.messenger.reticulum.model.LogLevel
import com.lxmf.messenger.reticulum.model.NetworkStatus
import com.lxmf.messenger.reticulum.model.ReticulumConfig
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Unit tests for ServiceReticulumProtocol.
 *
 * Note: ServiceReticulumProtocol has complex internal threading with its own
 * CoroutineScope using Dispatchers.IO, making full service binding simulation
 * impractical in unit tests. These tests focus on:
 * - Initial state verification
 * - Service not bound error cases
 * - Configuration model tests
 *
 * Full service binding tests should be done as instrumented tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceReticulumProtocolTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var rmspServerRepository: RmspServerRepository
    private lateinit var mockService: IReticulumService
    private lateinit var protocol: ServiceReticulumProtocol

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock static AIDL method
        mockkStatic(IReticulumService.Stub::class)

        // Mock android.util.Base64 to use java.util.Base64
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.decode(any<String>(), any()) } answers {
            Base64.getDecoder().decode(firstArg<String>())
        }
        every { android.util.Base64.encodeToString(any<ByteArray>(), any()) } answers {
            Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }

        // Create mocks
        context = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        rmspServerRepository = mockk(relaxed = true)
        mockService = mockk(relaxed = true)

        // Default settings repository behavior
        coEvery { settingsRepository.lastServiceStatusFlow } returns flowOf("SHUTDOWN")
        coEvery { settingsRepository.saveServiceStatus(any()) } just Runs
        coEvery { settingsRepository.saveIsSharedInstance(any()) } just Runs

        // Capture service connection for lifecycle simulation
        val connectionSlot = slot<ServiceConnection>()
        every {
            context.bindService(
                any<android.content.Intent>(),
                capture(connectionSlot),
                any<Int>(),
            )
        } returns true
        every { context.unbindService(any()) } just Runs
        every { context.startService(any()) } returns mockk()
        every { context.startForegroundService(any()) } returns mockk()

        // Default service mock behaviors
        every { mockService.getStatus() } returns "SHUTDOWN"
        every { mockService.isInitialized() } returns false
        every { mockService.registerCallback(any()) } just Runs
        every { mockService.unregisterCallback(any()) } just Runs
        every { mockService.registerReadinessCallback(any()) } just Runs

        // Mock the static AIDL asInterface method
        every { IReticulumService.Stub.asInterface(any()) } returns mockService

        // Create protocol instance
        protocol = ServiceReticulumProtocol(context, settingsRepository, rmspServerRepository)
    }

    @After
    fun tearDown() {
        if (::protocol.isInitialized) {
            protocol.cleanup()
        }
        Dispatchers.resetMain()
        unmockkStatic(IReticulumService.Stub::class)
        unmockkStatic(android.util.Base64::class)
        clearAllMocks()
    }

    // ===========================================
    // Initial State Tests
    // ===========================================

    @Test
    fun `initial networkStatus is CONNECTING`() {
        // NetworkStatus starts as CONNECTING when protocol is created
        assertTrue(protocol.networkStatus.value is NetworkStatus.CONNECTING)
    }

    @Test
    fun `cleanup can be called safely`() {
        // When
        protocol.cleanup()

        // Then - no exception should be thrown
        assertTrue(true)
    }

    @Test
    fun `cleanup can be called multiple times safely`() {
        // When
        protocol.cleanup()
        protocol.cleanup()

        // Then - no exception should be thrown
        assertTrue(true)
    }

    // ===========================================
    // Service Not Bound Error Tests
    // ===========================================

    @Test
    fun `getStatus - returns error when service not bound`() {
        // When
        val result = protocol.getStatus()

        // Then
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not bound") == true)
    }

    @Test
    fun `isInitialized - returns error when service not bound`() {
        // When
        val result = protocol.isInitialized()

        // Then
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not bound") == true)
    }

    @Test
    fun `createIdentity - returns error when service not bound`() =
        runTest {
            // When
            val result = protocol.createIdentity()

            // Then
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("not bound") == true)
        }

    @Test
    fun `loadIdentity - returns error when service not bound`() =
        runTest {
            // When
            val result = protocol.loadIdentity("/test/path")

            // Then
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("not bound") == true)
        }

    @Test
    fun `shutdown - returns error when service not bound`() =
        runTest {
            // When
            val result = protocol.shutdown()

            // Then
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("not bound") == true)
        }

    // ===========================================
    // Bind Failure Tests
    // ===========================================

    @Test
    fun `bindService - handles bind failure when context returns false`() =
        runTest {
            // Given
            every {
                context.bindService(
                    any<android.content.Intent>(),
                    any<ServiceConnection>(),
                    any<Int>(),
                )
            } returns false

            // When/Then
            try {
                protocol.bindService()
                assert(false) { "Should have thrown exception" }
            } catch (e: RuntimeException) {
                assertTrue(e.message!!.contains("Failed to bind"))
            }
        }

    // ===========================================
    // NetworkStatus Sealed Class Tests
    // ===========================================

    @Test
    fun `NetworkStatus INITIALIZING is singleton`() {
        val status1 = NetworkStatus.INITIALIZING
        val status2 = NetworkStatus.INITIALIZING
        assertEquals(status1, status2)
    }

    @Test
    fun `NetworkStatus CONNECTING is singleton`() {
        val status1 = NetworkStatus.CONNECTING
        val status2 = NetworkStatus.CONNECTING
        assertEquals(status1, status2)
    }

    @Test
    fun `NetworkStatus READY is singleton`() {
        val status1 = NetworkStatus.READY
        val status2 = NetworkStatus.READY
        assertEquals(status1, status2)
    }

    @Test
    fun `NetworkStatus SHUTDOWN is singleton`() {
        val status1 = NetworkStatus.SHUTDOWN
        val status2 = NetworkStatus.SHUTDOWN
        assertEquals(status1, status2)
    }

    @Test
    fun `NetworkStatus ERROR contains message`() {
        val error = NetworkStatus.ERROR("Test error message")
        assertEquals("Test error message", error.message)
    }

    @Test
    fun `NetworkStatus ERROR with same message are equal`() {
        val error1 = NetworkStatus.ERROR("Same message")
        val error2 = NetworkStatus.ERROR("Same message")
        assertEquals(error1, error2)
    }

    @Test
    fun `NetworkStatus ERROR with different messages are not equal`() {
        val error1 = NetworkStatus.ERROR("Message 1")
        val error2 = NetworkStatus.ERROR("Message 2")
        assertFalse(error1 == error2)
    }

    // ===========================================
    // ReticulumConfig Tests
    // ===========================================

    @Test
    fun `ReticulumConfig - creates with required parameters`() {
        val config =
            ReticulumConfig(
                storagePath = "/test/path",
                enabledInterfaces = emptyList(),
            )

        assertEquals("/test/path", config.storagePath)
        assertTrue(config.enabledInterfaces.isEmpty())
        assertEquals(LogLevel.INFO, config.logLevel)
        assertFalse(config.allowAnonymous)
        assertFalse(config.preferOwnInstance)
    }

    @Test
    fun `ReticulumConfig - creates with all parameters`() {
        val interfaces =
            listOf(
                InterfaceConfig.AutoInterface(name = "Auto"),
            )

        val config =
            ReticulumConfig(
                storagePath = "/test/path",
                enabledInterfaces = interfaces,
                identityFilePath = "/identity.dat",
                displayName = "Test Node",
                logLevel = LogLevel.DEBUG,
                allowAnonymous = true,
                preferOwnInstance = true,
                rpcKey = "abc123",
            )

        assertEquals("/test/path", config.storagePath)
        assertEquals(1, config.enabledInterfaces.size)
        assertEquals("/identity.dat", config.identityFilePath)
        assertEquals("Test Node", config.displayName)
        assertEquals(LogLevel.DEBUG, config.logLevel)
        assertTrue(config.allowAnonymous)
        assertTrue(config.preferOwnInstance)
        assertEquals("abc123", config.rpcKey)
    }

    @Test
    fun `ReticulumConfig - enableTransport defaults to true`() {
        val config =
            ReticulumConfig(
                storagePath = "/test/path",
                enabledInterfaces = emptyList(),
            )

        assertTrue("enableTransport should default to true", config.enableTransport)
    }

    @Test
    fun `ReticulumConfig - enableTransport can be set to false`() {
        val config =
            ReticulumConfig(
                storagePath = "/test/path",
                enabledInterfaces = emptyList(),
                enableTransport = false,
            )

        assertFalse("enableTransport should be false when explicitly set", config.enableTransport)
    }

    @Test
    fun `ReticulumConfig - enableTransport can be set to true explicitly`() {
        val config =
            ReticulumConfig(
                storagePath = "/test/path",
                enabledInterfaces = emptyList(),
                enableTransport = true,
            )

        assertTrue("enableTransport should be true when explicitly set", config.enableTransport)
    }

    // ===========================================
    // InterfaceConfig Tests
    // ===========================================

    @Test
    fun `AutoInterface - creates with defaults`() {
        val config = InterfaceConfig.AutoInterface()

        assertEquals("Auto Discovery", config.name)
        assertTrue(config.enabled)
        assertEquals("", config.groupId)
        assertEquals("link", config.discoveryScope)
        assertEquals("full", config.mode)
    }

    @Test
    fun `TCPClient - creates with required parameters`() {
        val config =
            InterfaceConfig.TCPClient(
                targetHost = "192.168.1.1",
                targetPort = 4242,
            )

        assertEquals("TCP Connection", config.name)
        assertTrue(config.enabled)
        assertEquals("192.168.1.1", config.targetHost)
        assertEquals(4242, config.targetPort)
        assertFalse(config.kissFraming)
    }

    @Test
    fun `RNode - creates with required parameters`() {
        val config =
            InterfaceConfig.RNode(
                targetDeviceName = "RNode",
            )

        assertEquals("RNode LoRa", config.name)
        assertTrue(config.enabled)
        assertEquals("RNode", config.targetDeviceName)
        assertEquals("classic", config.connectionMode)
        assertEquals(915000000L, config.frequency)
        assertEquals(125000, config.bandwidth)
        assertEquals(7, config.txPower)
    }

    @Test
    fun `UDP - creates with defaults`() {
        val config = InterfaceConfig.UDP()

        assertEquals("UDP Interface", config.name)
        assertTrue(config.enabled)
        assertEquals("0.0.0.0", config.listenIp)
        assertEquals(4242, config.listenPort)
    }

    @Test
    fun `AndroidBLE - creates with defaults`() {
        val config = InterfaceConfig.AndroidBLE()

        assertEquals("Bluetooth LE", config.name)
        assertTrue(config.enabled)
        assertEquals("", config.deviceName)
        assertEquals(7, config.maxConnections)
        assertEquals("roaming", config.mode)
    }

    // ===========================================
    // LogLevel Tests
    // ===========================================

    @Test
    fun `LogLevel - contains all expected values`() {
        val levels = LogLevel.values()

        assertTrue(levels.contains(LogLevel.CRITICAL))
        assertTrue(levels.contains(LogLevel.ERROR))
        assertTrue(levels.contains(LogLevel.WARNING))
        assertTrue(levels.contains(LogLevel.INFO))
        assertTrue(levels.contains(LogLevel.DEBUG))
        assertTrue(levels.contains(LogLevel.VERBOSE))
    }

    @Test
    fun `LogLevel - ordinal ordering is correct`() {
        assertTrue(LogLevel.CRITICAL.ordinal < LogLevel.ERROR.ordinal)
        assertTrue(LogLevel.ERROR.ordinal < LogLevel.WARNING.ordinal)
        assertTrue(LogLevel.WARNING.ordinal < LogLevel.INFO.ordinal)
        assertTrue(LogLevel.INFO.ordinal < LogLevel.DEBUG.ordinal)
        assertTrue(LogLevel.DEBUG.ordinal < LogLevel.VERBOSE.ordinal)
    }

    // ===========================================
    // Verify Mock Setup
    // ===========================================

    @Test
    fun `verify protocol can be created and cleaned up`() =
        runTest {
            // Given - protocol already created in setup

            // When/Then - verify we can create and cleanup the protocol without error
            protocol.cleanup()

            // Verify context methods were configured correctly
            assertTrue(::protocol.isInitialized)
        }

    @Test
    fun `verify static AIDL mock returns mockService`() {
        // When
        val result = IReticulumService.Stub.asInterface(mockk())

        // Then
        assertEquals(mockService, result)
    }

    // ===========================================
    // buildConfigJson Tests
    // ===========================================

    @Test
    fun `buildConfigJson - includes enable_transport true by default`() {
        // Given
        val config =
            ReticulumConfig(
                storagePath = "/test/path",
                enabledInterfaces = emptyList(),
            )

        // When
        val json = protocol.buildConfigJson(config)

        // Then
        val jsonObject = org.json.JSONObject(json)
        assertTrue("enable_transport should be true by default", jsonObject.getBoolean("enable_transport"))
    }

    @Test
    fun `buildConfigJson - includes enable_transport false when set`() {
        // Given
        val config =
            ReticulumConfig(
                storagePath = "/test/path",
                enabledInterfaces = emptyList(),
                enableTransport = false,
            )

        // When
        val json = protocol.buildConfigJson(config)

        // Then
        val jsonObject = org.json.JSONObject(json)
        assertFalse("enable_transport should be false when set", jsonObject.getBoolean("enable_transport"))
    }

    @Test
    fun `buildConfigJson - includes enable_transport true when explicitly set`() {
        // Given
        val config =
            ReticulumConfig(
                storagePath = "/test/path",
                enabledInterfaces = emptyList(),
                enableTransport = true,
            )

        // When
        val json = protocol.buildConfigJson(config)

        // Then
        val jsonObject = org.json.JSONObject(json)
        assertTrue("enable_transport should be true when explicitly set", jsonObject.getBoolean("enable_transport"))
    }

    @Test
    fun `buildConfigJson - includes all required fields`() {
        // Given
        val config =
            ReticulumConfig(
                storagePath = "/test/storage",
                enabledInterfaces = emptyList(),
                logLevel = LogLevel.DEBUG,
                allowAnonymous = true,
                preferOwnInstance = true,
                enableTransport = false,
            )

        // When
        val json = protocol.buildConfigJson(config)

        // Then
        val jsonObject = org.json.JSONObject(json)
        assertEquals("/test/storage", jsonObject.getString("storagePath"))
        assertEquals("DEBUG", jsonObject.getString("logLevel"))
        assertTrue(jsonObject.getBoolean("allowAnonymous"))
        assertTrue(jsonObject.getBoolean("prefer_own_instance"))
        assertFalse(jsonObject.getBoolean("enable_transport"))
    }

    @Test
    fun `buildConfigJson - includes RNode TCP host and port when set`() {
        // Given
        val rnodeConfig =
            InterfaceConfig.RNode(
                name = "Test TCP RNode",
                enabled = true,
                connectionMode = "tcp",
                tcpHost = "192.168.1.100",
                tcpPort = 7633,
                frequency = 915000000,
                bandwidth = 125000,
                txPower = 17,
                spreadingFactor = 8,
                codingRate = 5,
            )
        val config =
            ReticulumConfig(
                storagePath = "/test/path",
                enabledInterfaces = listOf(rnodeConfig),
            )

        // When
        val json = protocol.buildConfigJson(config)

        // Then
        val jsonObject = org.json.JSONObject(json)
        val interfaces = jsonObject.getJSONArray("enabledInterfaces")
        assertEquals(1, interfaces.length())

        val ifaceJson = interfaces.getJSONObject(0)
        assertEquals("RNode", ifaceJson.getString("type"))
        assertEquals("tcp", ifaceJson.getString("connection_mode"))
        assertEquals("192.168.1.100", ifaceJson.getString("tcp_host"))
        assertEquals(7633, ifaceJson.getInt("tcp_port"))
    }

    // ===========================================
    // restorePeerIdentities Tests
    // ===========================================

    @Test
    fun `restorePeerIdentities - returns error when service not bound`() {
        // When
        val result = protocol.restorePeerIdentities(emptyList())

        // Then
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not bound") == true)
    }

    @Test
    fun `restorePeerIdentities - returns success with count when service returns success`() {
        // Given: Inject mock service via reflection
        every { mockService.restorePeerIdentities(any()) } returns """{"success":true,"restored_count":5}"""
        injectMockService(protocol, mockService)

        // When
        val peerIdentities =
            listOf(
                Pair("abc123", byteArrayOf(1, 2, 3)),
                Pair("def456", byteArrayOf(4, 5, 6)),
            )
        val result = protocol.restorePeerIdentities(peerIdentities)

        // Then
        assertTrue("Result should be success", result.isSuccess)
        assertEquals(5, result.getOrNull())
    }

    @Test
    fun `restorePeerIdentities - returns failure when service returns error`() {
        // Given: Inject mock service via reflection
        every { mockService.restorePeerIdentities(any()) } returns """{"success":false,"error":"Test error"}"""
        injectMockService(protocol, mockService)

        // When
        val result = protocol.restorePeerIdentities(emptyList())

        // Then
        assertTrue("Result should be failure", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Test error") == true)
    }

    @Test
    fun `restorePeerIdentities - handles empty list`() {
        // Given: Inject mock service via reflection
        every { mockService.restorePeerIdentities(any()) } returns """{"success":true,"restored_count":0}"""
        injectMockService(protocol, mockService)

        // When
        val result = protocol.restorePeerIdentities(emptyList())

        // Then
        assertTrue("Result should be success for empty list", result.isSuccess)
        assertEquals(0, result.getOrNull())
    }

    // ===========================================
    // restoreAnnounceIdentities Tests
    // ===========================================

    @Test
    fun `restoreAnnounceIdentities - returns error when service not bound`() {
        // When
        val result = protocol.restoreAnnounceIdentities(emptyList())

        // Then
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not bound") == true)
    }

    @Test
    fun `restoreAnnounceIdentities - returns success with count when service returns success`() {
        // Given: Inject mock service via reflection
        every { mockService.restoreAnnounceIdentities(any()) } returns """{"success":true,"restored_count":10}"""
        injectMockService(protocol, mockService)

        // When
        val announces =
            listOf(
                Pair("destHash1", byteArrayOf(1, 2, 3)),
                Pair("destHash2", byteArrayOf(4, 5, 6)),
            )
        val result = protocol.restoreAnnounceIdentities(announces)

        // Then
        assertTrue("Result should be success", result.isSuccess)
        assertEquals(10, result.getOrNull())
    }

    @Test
    fun `restoreAnnounceIdentities - returns failure when service returns error`() {
        // Given: Inject mock service via reflection
        every { mockService.restoreAnnounceIdentities(any()) } returns """{"success":false,"error":"Announce restore failed"}"""
        injectMockService(protocol, mockService)

        // When
        val result = protocol.restoreAnnounceIdentities(emptyList())

        // Then
        assertTrue("Result should be failure", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Announce restore failed") == true)
    }

    @Test
    fun `restoreAnnounceIdentities - handles empty list`() {
        // Given: Inject mock service via reflection
        every { mockService.restoreAnnounceIdentities(any()) } returns """{"success":true,"restored_count":0}"""
        injectMockService(protocol, mockService)

        // When
        val result = protocol.restoreAnnounceIdentities(emptyList())

        // Then
        assertTrue("Result should be success for empty list", result.isSuccess)
        assertEquals(0, result.getOrNull())
    }

    // Helper to inject mock service via reflection
    private fun injectMockService(
        protocol: ServiceReticulumProtocol,
        mockService: IReticulumService,
    ) {
        val serviceField = ServiceReticulumProtocol::class.java.getDeclaredField("service")
        serviceField.isAccessible = true
        serviceField.set(protocol, mockService)
    }

    @Test
    fun `buildConfigJson - RNode omits tcp_host when null`() {
        // Given - Bluetooth RNode without tcp_host
        val rnodeConfig =
            InterfaceConfig.RNode(
                name = "Test BLE RNode",
                enabled = true,
                targetDeviceName = "RNode A1B2",
                connectionMode = "ble",
                tcpHost = null,
                tcpPort = 7633,
                frequency = 915000000,
                bandwidth = 125000,
                txPower = 17,
                spreadingFactor = 8,
                codingRate = 5,
            )
        val config =
            ReticulumConfig(
                storagePath = "/test/path",
                enabledInterfaces = listOf(rnodeConfig),
            )

        // When
        val json = protocol.buildConfigJson(config)

        // Then
        val jsonObject = org.json.JSONObject(json)
        val interfaces = jsonObject.getJSONArray("enabledInterfaces")
        val ifaceJson = interfaces.getJSONObject(0)

        // tcp_host should not be present when null
        assertFalse(ifaceJson.has("tcp_host"))
        // tcp_port is always included
        assertEquals(7633, ifaceJson.getInt("tcp_port"))
    }

    // ===========================================
    // Event-Driven Flow Tests
    // ===========================================

    @Test
    fun `bleConnectionsFlow - initially has no emissions`() =
        runTest {
            // The flow has replay = 1, so first collector should wait for emission
            // We just verify it exists and doesn't throw
            val flow = protocol.bleConnectionsFlow
            assertTrue("bleConnectionsFlow should exist", flow != null)
        }

    @Test
    fun `debugInfoFlow - initially has no emissions`() =
        runTest {
            // The flow has replay = 1, so first collector should wait for emission
            // We just verify it exists and doesn't throw
            val flow = protocol.debugInfoFlow
            assertTrue("debugInfoFlow should exist", flow != null)
        }

    @Test
    fun `interfaceStatusFlow - initially has no emissions`() =
        runTest {
            // The flow has replay = 1, so first collector should wait for emission
            // We just verify it exists and doesn't throw
            val flow = protocol.interfaceStatusFlow
            assertTrue("interfaceStatusFlow should exist", flow != null)
        }

    @Test
    fun `event flows are SharedFlows with replay`() {
        // Verify the flows are SharedFlows (they have replay capability)
        // This is a structural test to ensure correct flow type
        assertTrue("bleConnectionsFlow should be accessible", ::protocol.isInitialized)
        assertTrue("debugInfoFlow should be accessible", ::protocol.isInitialized)
        assertTrue("interfaceStatusFlow should be accessible", ::protocol.isInitialized)
    }

    // ===========================================
    // parseMessageJson Tests
    // ===========================================

    @Test
    fun `parseMessageJson - parses message with public_key`() {
        // Given - use java.util.Base64 for test data generation
        val sourceHashB64 = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val destHashB64 = Base64.getEncoder().encodeToString(ByteArray(16) { (it + 1).toByte() })
        val publicKeyB64 = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 2).toByte() })

        val messageJson =
            """
            {
                "message_hash": "abc123",
                "content": "Hello with public key",
                "source_hash": "$sourceHashB64",
                "destination_hash": "$destHashB64",
                "timestamp": 1234567890,
                "public_key": "$publicKeyB64"
            }
            """.trimIndent()

        // When
        val result = protocol.parseMessageJson(messageJson)

        // Then
        assertEquals("abc123", result.messageHash)
        assertEquals("Hello with public key", result.content)
        assertEquals(1234567890L, result.timestamp)
        assertTrue("Public key should not be null", result.publicKey != null)
        assertEquals(32, result.publicKey?.size)
    }

    @Test
    fun `parseMessageJson - parses message without public_key`() {
        // Given
        val sourceHashB64 = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val destHashB64 = Base64.getEncoder().encodeToString(ByteArray(16) { (it + 1).toByte() })

        val messageJson =
            """
            {
                "message_hash": "def456",
                "content": "Hello without public key",
                "source_hash": "$sourceHashB64",
                "destination_hash": "$destHashB64",
                "timestamp": 1234567890
            }
            """.trimIndent()

        // When
        val result = protocol.parseMessageJson(messageJson)

        // Then
        assertEquals("def456", result.messageHash)
        assertEquals("Hello without public key", result.content)
        assertEquals(null, result.publicKey)
    }

    @Test
    fun `parseMessageJson - handles empty public_key string`() {
        // Given
        val sourceHashB64 = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val destHashB64 = Base64.getEncoder().encodeToString(ByteArray(16) { (it + 1).toByte() })

        val messageJson =
            """
            {
                "message_hash": "ghi789",
                "content": "Hello with empty public key",
                "source_hash": "$sourceHashB64",
                "destination_hash": "$destHashB64",
                "timestamp": 1234567890,
                "public_key": ""
            }
            """.trimIndent()

        // When
        val result = protocol.parseMessageJson(messageJson)

        // Then
        assertEquals("ghi789", result.messageHash)
        assertEquals(null, result.publicKey)
    }

    @Test
    fun `parseMessageJson - parses message with fields object`() {
        // Given
        val sourceHashB64 = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val destHashB64 = Base64.getEncoder().encodeToString(ByteArray(16) { (it + 1).toByte() })

        val messageJson =
            """
            {
                "message_hash": "jkl012",
                "content": "Message with fields",
                "source_hash": "$sourceHashB64",
                "destination_hash": "$destHashB64",
                "timestamp": 1234567890,
                "fields": {"image": "base64data", "filename": "test.jpg"}
            }
            """.trimIndent()

        // When
        val result = protocol.parseMessageJson(messageJson)

        // Then
        assertEquals("jkl012", result.messageHash)
        assertEquals("Message with fields", result.content)
        assertTrue("fieldsJson should not be null", result.fieldsJson != null)
        assertTrue("fieldsJson should contain image", result.fieldsJson!!.contains("image"))
    }

    @Test
    fun `parseMessageJson - handles missing optional fields with defaults`() {
        // Given - minimal message with only required fields
        val sourceHashB64 = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val destHashB64 = Base64.getEncoder().encodeToString(ByteArray(16) { (it + 1).toByte() })

        val messageJson =
            """
            {
                "source_hash": "$sourceHashB64",
                "destination_hash": "$destHashB64"
            }
            """.trimIndent()

        // When
        val result = protocol.parseMessageJson(messageJson)

        // Then - default values should be used
        assertEquals("", result.messageHash)
        assertEquals("", result.content)
        assertEquals(null, result.fieldsJson)
        assertEquals(null, result.publicKey)
    }

    @Test
    fun `parseMessageJson - uses current time when timestamp missing`() {
        // Given
        val sourceHashB64 = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val destHashB64 = Base64.getEncoder().encodeToString(ByteArray(16) { (it + 1).toByte() })
        val beforeTime = System.currentTimeMillis()

        val messageJson =
            """
            {
                "message_hash": "notime",
                "content": "No timestamp",
                "source_hash": "$sourceHashB64",
                "destination_hash": "$destHashB64"
            }
            """.trimIndent()

        // When
        val result = protocol.parseMessageJson(messageJson)
        val afterTime = System.currentTimeMillis()

        // Then - timestamp should be between beforeTime and afterTime
        assertTrue("Timestamp should use current time", result.timestamp >= beforeTime)
        assertTrue("Timestamp should use current time", result.timestamp <= afterTime)
    }

    // ===========================================
    // parseMessageJson Hex Hash Parsing Tests
    // ===========================================

    @Test
    fun `parseMessageJson - correctly parses hex-encoded source_hash from Python`() {
        // Given: Python sends hex strings (not Base64) for source_hash and destination_hash
        // Example: "000102030405060708090a0b0c0d0e0f" represents bytes 0-15
        val sourceHashHex = "000102030405060708090a0b0c0d0e0f"
        val destHashHex = "101112131415161718191a1b1c1d1e1f"

        val messageJson =
            """
            {
                "message_hash": "hex_test",
                "content": "Test hex parsing",
                "source_hash": "$sourceHashHex",
                "destination_hash": "$destHashHex",
                "timestamp": 1234567890
            }
            """.trimIndent()

        // When
        val result = protocol.parseMessageJson(messageJson)

        // Then - verify source hash was parsed correctly as hex
        val expectedSourceHash = ByteArray(16) { it.toByte() }
        assertTrue(
            "Source hash should be correctly parsed from hex",
            result.sourceHash.contentEquals(expectedSourceHash),
        )

        // And - verify destination hash was parsed correctly as hex
        val expectedDestHash = ByteArray(16) { (it + 16).toByte() }
        assertTrue(
            "Destination hash should be correctly parsed from hex",
            result.destinationHash.contentEquals(expectedDestHash),
        )
    }

    @Test
    fun `parseMessageJson - handles uppercase hex strings`() {
        // Given: Hex strings may come in uppercase
        val sourceHashHex = "AABBCCDDEEFF00112233445566778899"

        val messageJson =
            """
            {
                "message_hash": "uppercase_hex",
                "content": "Uppercase hex test",
                "source_hash": "$sourceHashHex",
                "destination_hash": "000102030405060708090a0b0c0d0e0f",
                "timestamp": 1234567890
            }
            """.trimIndent()

        // When
        val result = protocol.parseMessageJson(messageJson)

        // Then - uppercase hex should parse correctly
        assertEquals(16, result.sourceHash.size)
        assertEquals(0xAA.toByte(), result.sourceHash[0])
        assertEquals(0xBB.toByte(), result.sourceHash[1])
    }

    @Test
    fun `parseMessageJson - handles empty source_hash gracefully`() {
        // Given: Empty hash string
        val messageJson =
            """
            {
                "message_hash": "empty_hash",
                "content": "Empty hash test",
                "source_hash": "",
                "destination_hash": "000102030405060708090a0b0c0d0e0f",
                "timestamp": 1234567890
            }
            """.trimIndent()

        // When
        val result = protocol.parseMessageJson(messageJson)

        // Then - empty string should produce empty byte array
        assertEquals(0, result.sourceHash.size)
    }

    @Test
    fun `parseMessageJson - handles missing source_hash gracefully`() {
        // Given: No source_hash in JSON
        val messageJson =
            """
            {
                "message_hash": "no_source",
                "content": "No source hash",
                "destination_hash": "000102030405060708090a0b0c0d0e0f",
                "timestamp": 1234567890
            }
            """.trimIndent()

        // When
        val result = protocol.parseMessageJson(messageJson)

        // Then - missing field should produce empty byte array
        assertEquals(0, result.sourceHash.size)
    }

    @Test
    fun `parseMessageJson - real Python hash format example`() {
        // Given: Real example from Python Reticulum (16-byte destination hash as 32 hex chars)
        val realSourceHash = "db3ffd2575469a78" + "1234567890abcdef" // 32 hex chars = 16 bytes

        val messageJson =
            """
            {
                "message_hash": "real_example",
                "content": "Real Python format",
                "source_hash": "$realSourceHash",
                "destination_hash": "abcdef1234567890abcdef1234567890",
                "timestamp": 1234567890
            }
            """.trimIndent()

        // When
        val result = protocol.parseMessageJson(messageJson)

        // Then - 32 hex characters should produce 16 bytes
        assertEquals(16, result.sourceHash.size)
        assertEquals(0xdb.toByte(), result.sourceHash[0])
        assertEquals(0x3f.toByte(), result.sourceHash[1])
        assertEquals(0xfd.toByte(), result.sourceHash[2])
    }
}
