package network.columba.app.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import network.columba.app.repository.InterfaceRepository
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.service.InterfaceImportManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterfaceImportViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var interfaceRepository: InterfaceRepository
    private lateinit var importManager: InterfaceImportManager
    private lateinit var viewModel: InterfaceImportViewModel

    private val snippet =
        """
        [[Central Oklahoma Reticulum Hub]]
          type = BackboneInterface
          enabled = yes
          remote = reticulum.mcswain.dev
          target_port = 4242
        """.trimIndent()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        InterfaceImportViewModel.ioDispatcher = testDispatcher

        interfaceRepository = mockk()
        importManager = mockk()
        every { interfaceRepository.allInterfaces } returns flowOf(emptyList())
        coEvery { importManager.importInterfaces(any()) } returns
            InterfaceImportManager.ImportApplyResult(insertedIds = listOf(7L), appliedLive = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `open parses text and selects non-duplicate interfaces`() =
        runTest {
            viewModel = InterfaceImportViewModel(interfaceRepository, importManager)

            viewModel.open(snippet)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.isOpen)
            assertFalse(state.isLoadingExisting)
            assertEquals(1, state.items.size)
            assertTrue(state.items.single().selected)
            assertFalse(state.items.single().duplicate)
            assertTrue(state.canImport)
        }

    @Test
    fun `already configured endpoint starts unchecked`() =
        runTest {
            every { interfaceRepository.allInterfaces } returns
                flowOf(
                    listOf(
                        InterfaceConfig.TCPClient(
                            name = "Old Hub",
                            targetHost = "reticulum.mcswain.dev",
                            targetPort = 4242,
                        ),
                    ),
                )
            viewModel = InterfaceImportViewModel(interfaceRepository, importManager)

            viewModel.open(snippet)
            advanceUntilIdle()

            val item = viewModel.state.value.items.single()
            assertTrue(item.duplicate)
            assertFalse(item.selected)
            assertFalse(viewModel.state.value.canImport)
        }

    @Test
    fun `toggle selects a duplicate for an explicit opt-in import`() =
        runTest {
            every { interfaceRepository.allInterfaces } returns
                flowOf(
                    listOf(
                        InterfaceConfig.TCPClient(
                            name = "Old Hub",
                            targetHost = "reticulum.mcswain.dev",
                            targetPort = 4242,
                        ),
                    ),
                )
            viewModel = InterfaceImportViewModel(interfaceRepository, importManager)
            viewModel.open(snippet)
            advanceUntilIdle()

            viewModel.toggle(0)

            assertTrue(viewModel.state.value.items.single().selected)
            assertTrue(viewModel.state.value.canImport)
        }

    @Test
    fun `import sends only selected configs and reports the count`() =
        runTest {
            viewModel = InterfaceImportViewModel(interfaceRepository, importManager)
            viewModel.open(snippet)
            advanceUntilIdle()

            viewModel.importSelected()
            advanceUntilIdle()

            coVerify(exactly = 1) { importManager.importInterfaces(any()) }
            val state = viewModel.state.value
            assertFalse(state.isOpen)
            assertEquals(1, state.importedCount)
            assertEquals("Added 1 interface", state.resultMessage)
            assertFalse(state.needsManualApply)
            assertEquals(1, state.importGeneration)
        }

    @Test
    fun `manual-apply result is surfaced to the host`() =
        runTest {
            coEvery { importManager.importInterfaces(any()) } returns
                InterfaceImportManager.ImportApplyResult(
                    insertedIds = listOf(7L),
                    appliedLive = false,
                    applyErrorMessage = "reload failed",
                )
            viewModel = InterfaceImportViewModel(interfaceRepository, importManager)
            viewModel.open(snippet)
            advanceUntilIdle()

            viewModel.importSelected()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.needsManualApply)
            assertTrue(state.resultMessage!!.contains("apply"))
            assertTrue(state.resultMessage!!.contains("reload failed"))
        }

    @Test
    fun `loading existing interfaces blocks import`() =
        runTest {
            viewModel = InterfaceImportViewModel(interfaceRepository, importManager)
            viewModel.open(snippet)
            // The load runs on the test dispatcher; reparse still sees no existing rows.
            viewModel.updateText(snippet)

            assertTrue(viewModel.state.value.isLoadingExisting)
            assertFalse(viewModel.state.value.canImport)

            viewModel.importSelected()
            advanceUntilIdle()

            coVerify(exactly = 0) { importManager.importInterfaces(any()) }
        }

    @Test
    fun `late import cannot close a reopened dialog`() =
        runTest {
            val gate = CompletableDeferred<InterfaceImportManager.ImportApplyResult>()
            coEvery { importManager.importInterfaces(any()) } coAnswers { gate.await() }
            viewModel = InterfaceImportViewModel(interfaceRepository, importManager)
            viewModel.open(snippet)
            advanceUntilIdle()

            viewModel.importSelected()
            runCurrent()
            viewModel.close()
            viewModel.open(
                """
                [[One]]
                  type = TCPClientInterface
                  target_host = 10.0.0.21
                  target_port = 4242

                [[Two]]
                  type = TCPClientInterface
                  target_host = 10.0.0.22
                  target_port = 4242
                """.trimIndent(),
            )
            runCurrent()

            assertTrue(viewModel.state.value.isImporting)
            assertEquals(2, viewModel.state.value.items.size)
            viewModel.toggle(0)
            assertFalse(viewModel.state.value.items[0].selected)
            viewModel.importSelected()
            runCurrent()
            coVerify(exactly = 1) { importManager.importInterfaces(any()) }

            gate.complete(InterfaceImportManager.ImportApplyResult(insertedIds = listOf(7L), appliedLive = true))
            advanceUntilIdle()

            assertTrue(viewModel.state.value.isOpen)
            assertFalse(viewModel.state.value.isImporting)
            assertEquals(0, viewModel.state.value.importedCount)
            assertEquals(2, viewModel.state.value.items.size)
            assertFalse(viewModel.state.value.items[0].selected)
            assertTrue(viewModel.state.value.items[1].selected)
        }

    @Test
    fun `empty editor has nothing to import`() =
        runTest {
            viewModel = InterfaceImportViewModel(interfaceRepository, importManager)
            viewModel.open("")
            advanceUntilIdle()

            val state = viewModel.state.value
            assertTrue(state.items.isEmpty())
            assertFalse(state.canImport)
        }

    @Test
    fun `parse errors are reported without blocking valid sections`() =
        runTest {
            viewModel = InterfaceImportViewModel(interfaceRepository, importManager)
            viewModel.open(
                """
                [[Broken]]
                  type = AndroidBLE
                  enabled = yes

                [[Works]]
                  type = TCPClientInterface
                  enabled = yes
                  target_host = 10.0.0.1
                  target_port = 4242
                """.trimIndent(),
            )
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(1, state.items.size)
            assertEquals("Works", state.items.single().config.name)
            assertTrue(state.errors.any { it.contains("Unsupported interface type") })
        }

    @Test
    fun `consumeResult clears the one-shot message`() =
        runTest {
            viewModel = InterfaceImportViewModel(interfaceRepository, importManager)
            viewModel.open(snippet)
            advanceUntilIdle()
            viewModel.importSelected()
            advanceUntilIdle()

            viewModel.consumeResult()

            assertNull(viewModel.state.value.resultMessage)
        }
}
