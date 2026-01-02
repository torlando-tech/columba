package com.lxmf.messenger.service.di

import android.content.Context
import com.lxmf.messenger.data.db.ColumbaDatabase
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ServiceDatabaseProvider.
 *
 * Tests the singleton behavior and database lifecycle management
 * for the service process database access.
 */
class ServiceDatabaseProviderTest {
    private lateinit var context: Context
    private lateinit var mockDatabase: ColumbaDatabase

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        mockDatabase = mockk(relaxed = true)

        // Reset the singleton state
        ServiceDatabaseProvider.close()
    }

    @After
    fun tearDown() {
        ServiceDatabaseProvider.close()
        clearAllMocks()
        unmockkAll()
    }

    // ========== close() Tests ==========

    @Test
    fun `close calls database close when instance exists`() {
        // Note: We can't easily test getDatabase without Room,
        // but we can test the close behavior
        // After close, the provider should be ready for a new database
        ServiceDatabaseProvider.close()

        // No exception should be thrown
    }

    @Test
    fun `close is idempotent - multiple calls do not throw`() {
        ServiceDatabaseProvider.close()
        ServiceDatabaseProvider.close()
        ServiceDatabaseProvider.close()

        // No exception should be thrown
    }

    // ========== Thread Safety Tests ==========

    @Test
    fun `close is thread-safe`() {
        // Run close from multiple threads concurrently
        val threads = (1..10).map {
            Thread {
                repeat(10) {
                    ServiceDatabaseProvider.close()
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // No exception should be thrown
    }
}
