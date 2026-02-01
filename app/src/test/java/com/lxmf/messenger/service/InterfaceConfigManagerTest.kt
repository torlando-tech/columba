package com.lxmf.messenger.service

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.dao.AnnounceDao
import com.lxmf.messenger.data.db.entity.AnnounceEntity
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import io.mockk.Ordering
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for InterfaceConfigManager.
 *
 * Tests cover:
 * - Manager lifecycle during service restart (stop before, start after)
 * - Correct order of manager stop/start calls
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InterfaceConfigManagerTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope

    private lateinit var context: Context
    private lateinit var reticulumProtocol: ReticulumProtocol
    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var identityRepository: IdentityRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var messageCollector: MessageCollector
    private lateinit var database: ColumbaDatabase
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var autoAnnounceManager: AutoAnnounceManager
    private lateinit var identityResolutionManager: IdentityResolutionManager
    private lateinit var propagationNodeManager: PropagationNodeManager
    private lateinit var applicationScope: CoroutineScope

    private lateinit var manager: InterfaceConfigManager
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var sharedPrefsEditor: SharedPreferences.Editor

    @Suppress("NoRelaxedMocks") // Android Context and SharedPreferences require relaxed mocks
    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        testScope = TestScope(testDispatcher)

        context = mockk(relaxed = true)
        reticulumProtocol = mockk()
        interfaceRepository = mockk()
        identityRepository = mockk()
        conversationRepository = mockk()
        messageCollector = mockk()
        database = mockk()
        settingsRepository = mockk()
        autoAnnounceManager = mockk()
        identityResolutionManager = mockk()
        propagationNodeManager = mockk()
        applicationScope = testScope.backgroundScope

        // Setup SharedPreferences mock
        sharedPrefsEditor = mockk(relaxed = true)
        sharedPrefs = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs
        every { sharedPrefs.edit() } returns sharedPrefsEditor
        every { sharedPrefsEditor.putBoolean(any(), any()) } returns sharedPrefsEditor
        every { sharedPrefsEditor.commit() } returns true

        // Setup context mocks
        every { context.filesDir } returns
            mockk {
                every { absolutePath } returns "/data/data/com.lxmf.messenger/files"
            }
        every { context.packageName } returns "com.lxmf.messenger"

        // Setup ActivityManager mock - no running processes by default
        val activityManager = mockk<ActivityManager>()
        every { context.getSystemService(any()) } returns activityManager
        every { activityManager.runningAppProcesses } returns emptyList()

        // Setup interface repository mock
        every { interfaceRepository.enabledInterfaces } returns flowOf(emptyList())

        // Setup settings repository mock
        every { settingsRepository.preferOwnInstanceFlow } returns flowOf(true)
        every { settingsRepository.rpcKeyFlow } returns flowOf(null)
        coEvery { settingsRepository.getTransportNodeEnabled() } returns true
        coEvery { settingsRepository.getDiscoverInterfacesEnabled() } returns false
        coEvery { settingsRepository.getAutoconnectDiscoveredCount() } returns 0

        // Setup identity repository mock
        coEvery { identityRepository.getActiveIdentitySync() } returns null

        // Setup database mock
        val announceDao = mockk<AnnounceDao>()
        every { database.announceDao() } returns announceDao
        coEvery { announceDao.getAnnouncesBatch(any(), any()) } returns emptyList()

        // Setup conversation repository mock
        coEvery { conversationRepository.getPeerIdentitiesBatch(any(), any()) } returns emptyList()

        // Setup protocol mock
        coEvery { reticulumProtocol.shutdown() } returns Result.success(Unit)
        coEvery { reticulumProtocol.initialize(any()) } returns Result.success(Unit)

        // Setup manager mocks (start/stop methods)
        every { messageCollector.stopCollecting() } just Runs
        every { messageCollector.startCollecting() } just Runs
        every { autoAnnounceManager.stop() } just Runs
        every { autoAnnounceManager.start() } just Runs
        every { identityResolutionManager.stop() } just Runs
        every { identityResolutionManager.start(any()) } just Runs
        every { propagationNodeManager.stop() } just Runs
        every { propagationNodeManager.start() } just Runs

        manager =
            InterfaceConfigManager(
                context = context,
                reticulumProtocol = reticulumProtocol,
                interfaceRepository = interfaceRepository,
                identityRepository = identityRepository,
                conversationRepository = conversationRepository,
                messageCollector = messageCollector,
                database = database,
                settingsRepository = settingsRepository,
                autoAnnounceManager = autoAnnounceManager,
                identityResolutionManager = identityResolutionManager,
                propagationNodeManager = propagationNodeManager,
                applicationScope = applicationScope,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ========== Manager Lifecycle Tests ==========

    @Test
    fun `applyInterfaceChanges - stops managers before service restart`() =
        runTest {
            // When
            val result = manager.applyInterfaceChanges()

            // Then: Should succeed
            assertTrue("applyInterfaceChanges should succeed", result.isSuccess)

            // And: All managers should have stop() called
            verify { autoAnnounceManager.stop() }
            verify { identityResolutionManager.stop() }
            verify { propagationNodeManager.stop() }
        }

    @Test
    fun `applyInterfaceChanges - starts managers after successful initialization`() =
        runTest {
            // When
            val result = manager.applyInterfaceChanges()

            // Then: Should succeed
            assertTrue("applyInterfaceChanges should succeed", result.isSuccess)

            // And: All managers should have start() called
            verify { autoAnnounceManager.start() }
            verify { identityResolutionManager.start(any()) }
            verify { propagationNodeManager.start() }
        }

    @Test
    fun `applyInterfaceChanges - managers stopped before collectors and started after`() =
        runTest {
            // When
            val result = manager.applyInterfaceChanges()

            // Then: Should succeed
            assertTrue("applyInterfaceChanges should succeed", result.isSuccess)

            // Verify order: stop managers happens early, start managers happens at the end
            coVerifyOrder {
                // Step 1: Stop message collector
                messageCollector.stopCollecting()

                // Step 1b: Stop managers
                autoAnnounceManager.stop()
                identityResolutionManager.stop()
                propagationNodeManager.stop()

                // ... (service restart happens in between) ...

                // Step 11: Start message collector
                messageCollector.startCollecting()

                // Step 12: Start managers
                autoAnnounceManager.start()
                identityResolutionManager.start(any())
                propagationNodeManager.start()
            }
        }

    @Test
    fun `applyInterfaceChanges - stops message collector first`() =
        runTest {
            // When
            val result = manager.applyInterfaceChanges()

            // Then: Should succeed and message collector should be stopped before managers
            assertTrue("applyInterfaceChanges should succeed", result.isSuccess)
            coVerify(ordering = Ordering.ORDERED) {
                messageCollector.stopCollecting()
                autoAnnounceManager.stop()
            }
        }

    @Test
    fun `applyInterfaceChanges - starts message collector before managers`() =
        runTest {
            // When
            val result = manager.applyInterfaceChanges()

            // Then: Should succeed and message collector should be started before managers
            assertTrue("applyInterfaceChanges should succeed", result.isSuccess)
            coVerify(ordering = Ordering.ORDERED) {
                messageCollector.startCollecting()
                autoAnnounceManager.start()
            }
        }

    @Test
    fun `applyInterfaceChanges - propagationNodeManager started with applicationScope`() =
        runTest {
            // When
            val result = manager.applyInterfaceChanges()

            // Then: Should succeed and identityResolutionManager.start() called with scope
            assertTrue("applyInterfaceChanges should succeed", result.isSuccess)
            verify { identityResolutionManager.start(applicationScope) }
        }

    @Test
    fun `applyInterfaceChanges - all three managers are started`() =
        runTest {
            // This test ensures we don't accidentally remove one of the manager start calls

            // When
            val result = manager.applyInterfaceChanges()

            // Then: Should succeed and all three managers should be started
            assertTrue("applyInterfaceChanges should succeed", result.isSuccess)
            verify(exactly = 1) { autoAnnounceManager.start() }
            verify(exactly = 1) { identityResolutionManager.start(any()) }
            verify(exactly = 1) { propagationNodeManager.start() }
        }

    @Test
    fun `applyInterfaceChanges - all three managers are stopped`() =
        runTest {
            // This test ensures we don't accidentally remove one of the manager stop calls

            // When
            val result = manager.applyInterfaceChanges()

            // Then: Should succeed and all three managers should be stopped
            assertTrue("applyInterfaceChanges should succeed", result.isSuccess)
            verify(exactly = 1) { autoAnnounceManager.stop() }
            verify(exactly = 1) { identityResolutionManager.stop() }
            verify(exactly = 1) { propagationNodeManager.stop() }
        }

    // ========== Transport Node Setting Tests ==========

    @Test
    fun `applyInterfaceChanges - loads transport node enabled setting`() =
        runTest {
            // When
            val result = manager.applyInterfaceChanges()

            // Then: Should succeed and load transport node setting from repository
            assertTrue("applyInterfaceChanges should succeed", result.isSuccess)
            coVerify { settingsRepository.getTransportNodeEnabled() }
        }

    @Test
    fun `applyInterfaceChanges - passes transport enabled true to config`() =
        runTest {
            // Given: Transport node is enabled
            coEvery { settingsRepository.getTransportNodeEnabled() } returns true

            // When
            val result = manager.applyInterfaceChanges()

            // Then: Should succeed and config should have enableTransport = true
            assertTrue("applyInterfaceChanges should succeed", result.isSuccess)
            coVerify {
                reticulumProtocol.initialize(
                    match { config ->
                        config.enableTransport == true
                    },
                )
            }
        }

    @Test
    fun `applyInterfaceChanges - passes transport enabled false to config`() =
        runTest {
            // Given: Transport node is disabled
            coEvery { settingsRepository.getTransportNodeEnabled() } returns false

            // When
            val result = manager.applyInterfaceChanges()

            // Then: Should succeed and config should have enableTransport = false
            assertTrue("applyInterfaceChanges should succeed", result.isSuccess)
            coVerify {
                reticulumProtocol.initialize(
                    match { config ->
                        config.enableTransport == false
                    },
                )
            }
        }

    // ========== Bulk Restore Tests ==========

    @Test
    fun `applyInterfaceChanges - calls restorePeerIdentities when peer identities exist`() =
        runTest {
            // Given: ServiceReticulumProtocol with peer identities
            val serviceProtocol = mockk<ServiceReticulumProtocol>()
            coEvery { serviceProtocol.shutdown() } returns Result.success(Unit)
            coEvery { serviceProtocol.initialize(any()) } returns Result.success(Unit)
            coEvery { serviceProtocol.bindService() } just Runs
            coEvery { serviceProtocol.restorePeerIdentities(any()) } returns Result.success(5)
            coEvery { serviceProtocol.restoreAnnounceIdentities(any()) } returns Result.success(0)

            val peerIdentities =
                listOf(
                    Pair("hash1", byteArrayOf(1, 2, 3)),
                    Pair("hash2", byteArrayOf(4, 5, 6)),
                )
            coEvery { conversationRepository.getPeerIdentitiesBatch(any(), any()) } returns peerIdentities

            val managerWithServiceProtocol =
                InterfaceConfigManager(
                    context = context,
                    reticulumProtocol = serviceProtocol,
                    interfaceRepository = interfaceRepository,
                    identityRepository = identityRepository,
                    conversationRepository = conversationRepository,
                    messageCollector = messageCollector,
                    database = database,
                    settingsRepository = settingsRepository,
                    autoAnnounceManager = autoAnnounceManager,
                    identityResolutionManager = identityResolutionManager,
                    propagationNodeManager = propagationNodeManager,
                    applicationScope = applicationScope,
                )

            // When
            val result = managerWithServiceProtocol.applyInterfaceChanges()

            // Then: Should succeed and restorePeerIdentities should be called
            assertTrue("applyInterfaceChanges should succeed", result.isSuccess)
            coVerify { serviceProtocol.restorePeerIdentities(peerIdentities) }
        }

    @Test
    fun `applyInterfaceChanges - calls restoreAnnounceIdentities when announces exist`() =
        runTest {
            // Given: ServiceReticulumProtocol with announce identities
            val serviceProtocol = mockk<ServiceReticulumProtocol>()
            coEvery { serviceProtocol.shutdown() } returns Result.success(Unit)
            coEvery { serviceProtocol.initialize(any()) } returns Result.success(Unit)
            coEvery { serviceProtocol.bindService() } just Runs
            coEvery { serviceProtocol.restoreAnnounceIdentities(any()) } returns Result.success(3)
            coEvery { serviceProtocol.restorePeerIdentities(any()) } returns Result.success(0)

            val announces =
                listOf(
                    AnnounceEntity(
                        destinationHash = "destHash1",
                        peerName = "Test1",
                        publicKey = byteArrayOf(1, 2, 3),
                        appData = null,
                        hops = 1,
                        lastSeenTimestamp = System.currentTimeMillis(),
                        nodeType = "lxmf",
                        receivingInterface = null,
                        aspect = "lxmf.delivery",
                    ),
                    AnnounceEntity(
                        destinationHash = "destHash2",
                        peerName = "Test2",
                        publicKey = byteArrayOf(4, 5, 6),
                        appData = null,
                        hops = 2,
                        lastSeenTimestamp = System.currentTimeMillis(),
                        nodeType = "lxmf",
                        receivingInterface = null,
                        aspect = "lxmf.delivery",
                    ),
                )
            val announceDao = mockk<AnnounceDao>()
            every { database.announceDao() } returns announceDao
            coEvery { announceDao.getAnnouncesBatch(any(), any()) } returns announces

            val managerWithServiceProtocol =
                InterfaceConfigManager(
                    context = context,
                    reticulumProtocol = serviceProtocol,
                    interfaceRepository = interfaceRepository,
                    identityRepository = identityRepository,
                    conversationRepository = conversationRepository,
                    messageCollector = messageCollector,
                    database = database,
                    settingsRepository = settingsRepository,
                    autoAnnounceManager = autoAnnounceManager,
                    identityResolutionManager = identityResolutionManager,
                    propagationNodeManager = propagationNodeManager,
                    applicationScope = applicationScope,
                )

            // When
            val result = managerWithServiceProtocol.applyInterfaceChanges()

            // Then: Should succeed and restoreAnnounceIdentities should be called
            assertTrue("applyInterfaceChanges should succeed", result.isSuccess)
            coVerify {
                serviceProtocol.restoreAnnounceIdentities(
                    match { list ->
                        list.size == 2 &&
                            list[0].first == "destHash1" &&
                            list[1].first == "destHash2"
                    },
                )
            }
        }

    @Test
    fun `applyInterfaceChanges - skips restorePeerIdentities when list is empty`() =
        runTest {
            // Given: ServiceReticulumProtocol with no peer identities
            val serviceProtocol = mockk<ServiceReticulumProtocol>()
            coEvery { serviceProtocol.shutdown() } returns Result.success(Unit)
            coEvery { serviceProtocol.initialize(any()) } returns Result.success(Unit)
            coEvery { serviceProtocol.bindService() } just Runs

            coEvery { conversationRepository.getPeerIdentitiesBatch(any(), any()) } returns emptyList()

            val managerWithServiceProtocol =
                InterfaceConfigManager(
                    context = context,
                    reticulumProtocol = serviceProtocol,
                    interfaceRepository = interfaceRepository,
                    identityRepository = identityRepository,
                    conversationRepository = conversationRepository,
                    messageCollector = messageCollector,
                    database = database,
                    settingsRepository = settingsRepository,
                    autoAnnounceManager = autoAnnounceManager,
                    identityResolutionManager = identityResolutionManager,
                    propagationNodeManager = propagationNodeManager,
                    applicationScope = applicationScope,
                )

            // When
            val result = managerWithServiceProtocol.applyInterfaceChanges()

            // Then: Should succeed and restorePeerIdentities should NOT be called
            assertTrue("applyInterfaceChanges should succeed", result.isSuccess)
            coVerify(exactly = 0) { serviceProtocol.restorePeerIdentities(any()) }
        }

    @Test
    fun `applyInterfaceChanges - skips restoreAnnounceIdentities when list is empty`() =
        runTest {
            // Given: ServiceReticulumProtocol with no announces
            val serviceProtocol = mockk<ServiceReticulumProtocol>()
            coEvery { serviceProtocol.shutdown() } returns Result.success(Unit)
            coEvery { serviceProtocol.initialize(any()) } returns Result.success(Unit)
            coEvery { serviceProtocol.bindService() } just Runs

            val announceDao = mockk<AnnounceDao>()
            every { database.announceDao() } returns announceDao
            coEvery { announceDao.getAnnouncesBatch(any(), any()) } returns emptyList()

            val managerWithServiceProtocol =
                InterfaceConfigManager(
                    context = context,
                    reticulumProtocol = serviceProtocol,
                    interfaceRepository = interfaceRepository,
                    identityRepository = identityRepository,
                    conversationRepository = conversationRepository,
                    messageCollector = messageCollector,
                    database = database,
                    settingsRepository = settingsRepository,
                    autoAnnounceManager = autoAnnounceManager,
                    identityResolutionManager = identityResolutionManager,
                    propagationNodeManager = propagationNodeManager,
                    applicationScope = applicationScope,
                )

            // When
            val result = managerWithServiceProtocol.applyInterfaceChanges()

            // Then: Should succeed and restoreAnnounceIdentities should NOT be called
            assertTrue("applyInterfaceChanges should succeed", result.isSuccess)
            coVerify(exactly = 0) { serviceProtocol.restoreAnnounceIdentities(any()) }
        }

    @Test
    fun `applyInterfaceChanges - continues when restorePeerIdentities fails`() =
        runTest {
            // Given: ServiceReticulumProtocol where restorePeerIdentities fails
            val serviceProtocol = mockk<ServiceReticulumProtocol>()
            coEvery { serviceProtocol.shutdown() } returns Result.success(Unit)
            coEvery { serviceProtocol.initialize(any()) } returns Result.success(Unit)
            coEvery { serviceProtocol.bindService() } just Runs
            coEvery { serviceProtocol.restorePeerIdentities(any()) } returns Result.failure(Exception("Test failure"))
            coEvery { serviceProtocol.restoreAnnounceIdentities(any()) } returns Result.success(0)

            val peerIdentities = listOf(Pair("hash1", byteArrayOf(1, 2, 3)))
            coEvery { conversationRepository.getPeerIdentitiesBatch(any(), any()) } returns peerIdentities

            val managerWithServiceProtocol =
                InterfaceConfigManager(
                    context = context,
                    reticulumProtocol = serviceProtocol,
                    interfaceRepository = interfaceRepository,
                    identityRepository = identityRepository,
                    conversationRepository = conversationRepository,
                    messageCollector = messageCollector,
                    database = database,
                    settingsRepository = settingsRepository,
                    autoAnnounceManager = autoAnnounceManager,
                    identityResolutionManager = identityResolutionManager,
                    propagationNodeManager = propagationNodeManager,
                    applicationScope = applicationScope,
                )

            // When
            val result = managerWithServiceProtocol.applyInterfaceChanges()

            // Then: Should still succeed (peer restore failure is not fatal)
            assertTrue("applyInterfaceChanges should succeed even if peer restore fails", result.isSuccess)

            // And: Message collector should still be started
            verify { messageCollector.startCollecting() }
        }

    @Test
    fun `applyInterfaceChanges - continues when restoreAnnounceIdentities fails`() =
        runTest {
            // Given: ServiceReticulumProtocol where restoreAnnounceIdentities fails
            val serviceProtocol = mockk<ServiceReticulumProtocol>()
            coEvery { serviceProtocol.shutdown() } returns Result.success(Unit)
            coEvery { serviceProtocol.initialize(any()) } returns Result.success(Unit)
            coEvery { serviceProtocol.bindService() } just Runs
            coEvery { serviceProtocol.restoreAnnounceIdentities(any()) } returns Result.failure(Exception("Test failure"))
            coEvery { serviceProtocol.restorePeerIdentities(any()) } returns Result.success(0)

            val announces =
                listOf(
                    AnnounceEntity(
                        destinationHash = "destHash1",
                        peerName = "Test1",
                        publicKey = byteArrayOf(1, 2, 3),
                        appData = null,
                        hops = 1,
                        lastSeenTimestamp = System.currentTimeMillis(),
                        nodeType = "lxmf",
                        receivingInterface = null,
                        aspect = "lxmf.delivery",
                    ),
                )
            val announceDao = mockk<AnnounceDao>()
            every { database.announceDao() } returns announceDao
            coEvery { announceDao.getAnnouncesBatch(any(), any()) } returns announces

            val managerWithServiceProtocol =
                InterfaceConfigManager(
                    context = context,
                    reticulumProtocol = serviceProtocol,
                    interfaceRepository = interfaceRepository,
                    identityRepository = identityRepository,
                    conversationRepository = conversationRepository,
                    messageCollector = messageCollector,
                    database = database,
                    settingsRepository = settingsRepository,
                    autoAnnounceManager = autoAnnounceManager,
                    identityResolutionManager = identityResolutionManager,
                    propagationNodeManager = propagationNodeManager,
                    applicationScope = applicationScope,
                )

            // When
            val result = managerWithServiceProtocol.applyInterfaceChanges()

            // Then: Should still succeed (announce restore failure is not fatal)
            assertTrue("applyInterfaceChanges should succeed even if announce restore fails", result.isSuccess)

            // And: Message collector should still be started
            verify { messageCollector.startCollecting() }
        }
}
