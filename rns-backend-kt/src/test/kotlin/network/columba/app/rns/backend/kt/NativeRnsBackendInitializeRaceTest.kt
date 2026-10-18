package network.columba.app.rns.backend.kt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.api.model.ReticulumConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The :reticulum service self-initializes from its config snapshot while the UI
 * process can call initialize() over IPC at the same moment. Both must succeed
 * and leave one running stack — previously the loser threw "Reticulum is already
 * started" and its failure cleanup tore down the winner's stack.
 */
class NativeRnsBackendInitializeRaceTest {
    private lateinit var storage: File
    private lateinit var backend: NativeRnsBackendImpl

    @Before
    fun setUp() {
        storage = Files.createTempDirectory("columba-init-race").toFile()
        backend = NativeRnsBackendImpl()
    }

    @After
    fun tearDown() {
        runBlocking { backend.shutdown() }
        storage.deleteRecursively()
    }

    private fun config() =
        ReticulumConfig(
            storagePath = storage.absolutePath,
            enabledInterfaces = emptyList(),
        )

    @Test
    fun `concurrent initialize calls both succeed and leave the stack running`() =
        runBlocking {
            val results =
                List(2) { async(Dispatchers.Default) { backend.initialize(config()) } }.awaitAll()

            results.forEach { assertTrue("initialize failed: ${it.exceptionOrNull()}", it.isSuccess) }
            assertEquals(NetworkStatus.READY, backend.networkStatus.value)
        }

    @Test
    fun `initialize after shutdown starts the stack again`() =
        runBlocking {
            assertTrue(backend.initialize(config()).isSuccess)
            assertTrue(backend.shutdown().isSuccess)
            assertTrue(backend.initialize(config()).isSuccess)
            assertEquals(NetworkStatus.READY, backend.networkStatus.value)
        }
}
