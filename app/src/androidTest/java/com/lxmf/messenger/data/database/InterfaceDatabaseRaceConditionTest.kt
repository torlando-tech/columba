package com.lxmf.messenger.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lxmf.messenger.data.database.dao.InterfaceDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Provider

/**
 * Test to reproduce and fix the database initialization race condition.
 *
 * Bug: InterfaceDatabase.onCreate() uses async coroutine to populate defaults,
 * but ColumbaApplication immediately reads from database before population completes.
 * Result: Empty enabledInterfaces list → empty [interfaces] section in RNS config.
 *
 * This test validates that default interfaces are available immediately after
 * database creation, without any race condition.
 */
@RunWith(AndroidJUnit4::class)
class InterfaceDatabaseRaceConditionTest {
    private lateinit var database: InterfaceDatabase
    private lateinit var interfaceDao: InterfaceDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Create application scope (matches production setup)
        val applicationScope = CoroutineScope(SupervisorJob())

        // Create a Provider that will provide the database instance
        // This is needed because the callback needs to access the database
        val databaseProvider =
            object : Provider<InterfaceDatabase> {
                override fun get(): InterfaceDatabase = database
            }

        // Create a fresh in-memory database to simulate first launch
        // This will trigger onCreate() which should populate defaults
        // IMPORTANT: We must attach the callback just like production does!
        database =
            Room.inMemoryDatabaseBuilder(
                context,
                InterfaceDatabase::class.java,
            )
                .addCallback(InterfaceDatabase.Callback(context, databaseProvider, applicationScope))
                .build()

        interfaceDao = database.interfaceDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    /**
     * Test Case 1: Verify default interfaces exist immediately after database creation.
     *
     * This simulates the exact scenario in ColumbaApplication where we try to
     * read interfaces immediately after database creation.
     *
     * EXPECTED: This test FAILS before the fix (race condition causes empty list)
     * EXPECTED: This test PASSES after the fix (runBlocking ensures population completes)
     */
    @Test
    fun testDefaultInterfacesAvailableImmediatelyAfterCreation() =
        runBlocking {
            // ACT: Read all interfaces immediately after database creation
            // This mimics ColumbaApplication.kt:90
            val allInterfaces = interfaceDao.getAllInterfaces().first()

            // ASSERT: Database should have default interfaces
            // Before fix: This will FAIL with empty list due to race condition
            // After fix: This will PASS with 2 interfaces
            assertNotNull("Interfaces list should not be null", allInterfaces)
            assertTrue(
                "Database should have default interfaces immediately after creation. " +
                    "Found ${allInterfaces.size} interfaces, expected 2 (AndroidBLE + AutoInterface). " +
                    "This failure indicates the race condition bug is still present.",
                allInterfaces.size >= 2,
            )

            // Verify specific interfaces exist
            val interfaceTypes = allInterfaces.map { it.type }
            assertTrue(
                "Should have AndroidBLE interface",
                "AndroidBLE" in interfaceTypes,
            )
            assertTrue(
                "Should have AutoInterface",
                "AutoInterface" in interfaceTypes,
            )
        }

    /**
     * Test Case 2: Verify enabled interfaces are available for config generation.
     *
     * This simulates the exact flow in ColumbaApplication where enabled interfaces
     * are read and passed to ReticulumService for config generation.
     *
     * EXPECTED: This test FAILS before the fix (AndroidBLE missing)
     * EXPECTED: This test PASSES after the fix (AndroidBLE present with correct config)
     */
    @Test
    fun testEnabledInterfacesAvailableForConfigGeneration() =
        runBlocking {
            // ACT: Read enabled interfaces (mimics ColumbaApplication.kt:90)
            val enabledInterfaces = interfaceDao.getEnabledInterfaces().first()

            // ASSERT: Should have at least one enabled interface (AndroidBLE by default)
            assertNotNull("Enabled interfaces should not be null", enabledInterfaces)
            assertFalse(
                "Enabled interfaces should not be empty. " +
                    "This causes RNS config to have empty [interfaces] section. " +
                    "This failure indicates the race condition bug.",
                enabledInterfaces.isEmpty(),
            )

            // Verify AndroidBLE is enabled by default
            val androidBleInterface = enabledInterfaces.find { it.type == "AndroidBLE" }
            assertNotNull(
                "AndroidBLE should be enabled by default for config generation",
                androidBleInterface,
            )

            // Verify AndroidBLE has correct configuration
            assertEquals("Bluetooth LE", androidBleInterface?.name)
            assertTrue("AndroidBLE should be enabled", androidBleInterface?.enabled == true)

            // Parse and verify config JSON
            val configJson = androidBleInterface?.configJson
            assertNotNull("AndroidBLE should have config JSON", configJson)
            assertTrue(
                "Config should contain device_name",
                configJson!!.contains("device_name"),
            )
            assertTrue(
                "Config should contain max_connections",
                configJson.contains("max_connections"),
            )
        }

    /**
     * Test Case 3: Verify timing - default population completes synchronously.
     *
     * This test verifies that onCreate() doesn't return until defaults are inserted.
     * It uses timing to detect if the population is truly synchronous.
     *
     * EXPECTED: This test FAILS before fix (interfaces not immediately available)
     * EXPECTED: This test PASSES after fix (interfaces available without delay)
     */
    @Ignore("Flaky on CI: Timing-sensitive test exceeds 100ms threshold on resource-constrained runners")
    @Test
    fun testDefaultPopulationIsSynchronous() =
        runBlocking {
            // The database is already created in @Before, triggering onCreate()
            // If onCreate() is synchronous, interfaces should be available NOW

            val startTime = System.currentTimeMillis()

            // Read interfaces without any delay
            val interfaces = interfaceDao.getAllInterfaces().first()

            val duration = System.currentTimeMillis() - startTime

            // If the read takes > 100ms, something is wrong (likely waiting for async operation)
            assertTrue(
                "Reading interfaces took ${duration}ms. If > 100ms, suggests async population not complete.",
                duration < 100,
            )

            // Interfaces should be present immediately
            assertTrue(
                "Interfaces should be populated synchronously in onCreate(). " +
                    "Found ${interfaces.size}, expected >= 2",
                interfaces.size >= 2,
            )
        }

    /**
     * Test Case 4: Stress test - multiple rapid reads.
     *
     * Simulates rapid successive reads that might happen in real app startup.
     * Before fix: Might get inconsistent results (sometimes empty, sometimes populated)
     * After fix: Should always get populated database
     */
    @Test
    fun testMultipleRapidReadsAllSucceed() =
        runBlocking {
            // Perform 10 rapid reads
            repeat(10) { iteration ->
                val interfaces = interfaceDao.getAllInterfaces().first()

                assertTrue(
                    "Read #$iteration: Should have default interfaces (race condition detected)",
                    interfaces.size >= 2,
                )
            }
        }
}
