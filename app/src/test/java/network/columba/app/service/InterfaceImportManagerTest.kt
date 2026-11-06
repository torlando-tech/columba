package network.columba.app.service

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import network.columba.app.repository.InterfaceRepository
import network.columba.app.rns.api.BackendCapabilities
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsTransportAdmin
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.host.manager.CurrentTransport
import network.columba.app.service.manager.InterfaceTransportObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class InterfaceImportManagerTest {
    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var rnsBackend: RnsBackend
    private lateinit var transportAdmin: RnsTransportAdmin
    private lateinit var transportObserver: InterfaceTransportObserver
    private lateinit var configManager: InterfaceConfigManager
    private lateinit var manager: InterfaceImportManager

    private var nextId = 10L
    private var enabled: List<InterfaceConfig> = emptyList()

    private fun caps(hotReload: Boolean): BackendCapabilities =
        BackendCapabilities.UNKNOWN.copy(
            interfaces = BackendCapabilities.InterfaceCaps(hotReloadInterfaces = hotReload),
        )

    @Before
    fun setup() {
        interfaceRepository = mockk()
        rnsBackend = mockk()
        transportAdmin = mockk()
        transportObserver = mockk()
        configManager = mockk()

        every { rnsBackend.capabilities } returns MutableStateFlow(caps(hotReload = true))
        every { transportObserver.snapshotTransport() } returns CurrentTransport.WIFI_LIKE
        every { configManager.setPendingChanges(any(), any()) } just Runs
        every { interfaceRepository.enabledInterfaces } answers { flowOf(enabled) }
        coEvery { interfaceRepository.insertInterface(any()) } answers {
            val config: InterfaceConfig = firstArg()
            val id = nextId++
            enabled = enabled + config
            id
        }

        manager = InterfaceImportManager(
            interfaceRepository = interfaceRepository,
            rnsBackend = rnsBackend,
            transportAdmin = transportAdmin,
            transportObserver = transportObserver,
            configManager = configManager,
        )
    }

    @Test
    fun `import inserts configs and reloads the live stack`() =
        runTest {
            coEvery { transportAdmin.reloadInterfaces(any()) } returns Unit
            enabled = listOf(InterfaceConfig.AutoInterface(name = "Existing", enabled = true))
            val imported =
                InterfaceConfig.TCPClient(
                    name = "Imported",
                    enabled = true,
                    targetHost = "node.example.com",
                    targetPort = 4242,
                )

            val result = manager.importInterfaces(listOf(imported))

            assertEquals(listOf(10L), result.insertedIds)
            assertTrue(result.appliedLive)
            assertNull(result.applyErrorMessage)
            coVerify(exactly = 1) { transportAdmin.reloadInterfaces(any()) }
            verify(exactly = 0) { configManager.setPendingChanges(any(), any()) }
        }

    @Test
    fun `non hot-reload backend marks enabled interfaces pending and skips reload`() =
        runTest {
            every { rnsBackend.capabilities } returns MutableStateFlow(caps(hotReload = false))
            val imported =
                InterfaceConfig.TCPClient(
                    name = "Imported",
                    enabled = true,
                    targetHost = "node.example.com",
                    targetPort = 4242,
                )

            val result = manager.importInterfaces(listOf(imported))

            assertFalse(result.appliedLive)
            coVerify(exactly = 0) { transportAdmin.reloadInterfaces(any()) }
            verify(exactly = 1) { configManager.setPendingChanges(true, 10L) }
        }

    @Test
    fun `reload failure falls back to pending apply with an error message`() =
        runTest {
            coEvery { transportAdmin.reloadInterfaces(any()) } throws IOException("boom")
            val imported =
                InterfaceConfig.TCPClient(
                    name = "Imported",
                    enabled = true,
                    targetHost = "node.example.com",
                    targetPort = 4242,
                )

            val result = manager.importInterfaces(listOf(imported))

            assertFalse(result.appliedLive)
            assertEquals("boom", result.applyErrorMessage)
            verify(exactly = 1) { configManager.setPendingChanges(true, 10L) }
        }

    @Test
    fun `disabled imports need neither reload nor apply`() =
        runTest {
            val imported =
                InterfaceConfig.TCPClient(
                    name = "Paused",
                    enabled = false,
                    targetHost = "node.example.com",
                    targetPort = 4242,
                )

            val result = manager.importInterfaces(listOf(imported))

            assertTrue(result.appliedLive)
            coVerify(exactly = 0) { transportAdmin.reloadInterfaces(any()) }
            verify(exactly = 0) { configManager.setPendingChanges(any(), any()) }
        }

    @Test
    fun `mixed import only marks the enabled ids pending`() =
        runTest {
            every { rnsBackend.capabilities } returns MutableStateFlow(caps(hotReload = false))
            val paused =
                InterfaceConfig.TCPClient(
                    name = "Paused",
                    enabled = false,
                    targetHost = "paused.example.com",
                    targetPort = 4242,
                )
            val active =
                InterfaceConfig.TCPClient(
                    name = "Active",
                    enabled = true,
                    targetHost = "active.example.com",
                    targetPort = 4242,
                )

            val result = manager.importInterfaces(listOf(paused, active))

            assertEquals(listOf(10L, 11L), result.insertedIds)
            assertFalse(result.appliedLive)
            verify(exactly = 0) { configManager.setPendingChanges(true, 10L) }
            verify(exactly = 1) { configManager.setPendingChanges(true, 11L) }
        }
}
