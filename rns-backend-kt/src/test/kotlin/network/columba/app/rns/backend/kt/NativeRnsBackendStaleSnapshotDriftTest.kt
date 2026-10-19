package network.columba.app.rns.backend.kt

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
 * Characterization test pinning the READY short-circuit contract: a second
 * `initialize()` while the stack is up reports success and DROPS its config —
 * the running stack keeps the first-applied one (the backend logs a warning
 * when the dropped config differs from the applied one).
 *
 * Scenario (ColumbaApplication cold start):
 *  1. `:reticulum` self-initializes from the on-disk snapshot, which still
 *     carries the PREVIOUS settings (lazy-saved toggles like shared-instance
 *     hosting are documented as "does NOT trigger a service restart").
 *  2. The UI process then calls initialize() with the CURRENT settings.
 *  3. The guard answers SUCCESS without applying the new config — and
 *     ColumbaApplication's onSuccess then rewrites the snapshot from that same
 *     config, so the snapshot now describes a stack that is not running.
 *
 * This pins CURRENT (warn-only) behavior on purpose: making the mismatch
 * actually reconfigure or fail (Tier 2/3 of the single-config-authority work)
 * must be a deliberate, test-visible edit, not a silent side effect.
 */
class NativeRnsBackendStaleSnapshotDriftTest {
    private lateinit var snapshotStorage: File
    private lateinit var uiStorage: File
    private lateinit var backend: NativeRnsBackendImpl

    @Before
    fun setUp() {
        snapshotStorage = Files.createTempDirectory("columba-snapshot").toFile()
        uiStorage = Files.createTempDirectory("columba-ui").toFile()
        backend = NativeRnsBackendImpl()
    }

    @After
    fun tearDown() {
        runBlocking { backend.shutdown() }
        snapshotStorage.deleteRecursively()
        uiStorage.deleteRecursively()
    }

    @Test
    fun `UI initialize after stale-snapshot self-init succeeds but its config is dropped`() =
        runBlocking {
            // Step 1: service self-init from the stale snapshot (old settings).
            val snapshotConfig =
                ReticulumConfig(
                    storagePath = snapshotStorage.absolutePath,
                    enabledInterfaces = emptyList(),
                    displayName = "old-display-name",
                )
            assertTrue(backend.initialize(snapshotConfig).isSuccess)

            // Step 2: UI calls initialize() with the changed settings.
            val uiConfig =
                snapshotConfig.copy(
                    storagePath = uiStorage.absolutePath,
                    displayName = "new-display-name",
                )
            val result = backend.initialize(uiConfig)

            // The no-op success contract...
            assertTrue(
                "expected the no-op success contract, got: ${result.exceptionOrNull()}",
                result.isSuccess,
            )
            assertEquals(NetworkStatus.READY, backend.networkStatus.value)

            // ...and the running stack is still the snapshot's — the UI
            // config was never applied (its storage tree was never materialized,
            // while the snapshot's was).
            assertTrue(
                "snapshot storage should have been materialized: ${snapshotStorage.walkTopDown().joinToString()}",
                snapshotStorage.walkTopDown().any { it.isFile },
            )
            assertTrue(
                "UI config was accepted-then-dropped, yet its storage contains files: " +
                    uiStorage.walkTopDown().filter { it.isFile }.toList(),
                uiStorage.walkTopDown().none { it.isFile },
            )
        }
}
