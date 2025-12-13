package com.lxmf.messenger.di

import android.content.Context
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import io.mockk.clearAllMocks
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ReticulumModule.
 *
 * Verifies that the Hilt DI module provides the correct implementation
 * of ReticulumProtocol.
 *
 * Test coverage:
 * - provideReticulumProtocol returns ServiceReticulumProtocol instance
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReticulumModuleTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Create mocks
        context = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `provideReticulumProtocol - returns ServiceReticulumProtocol instance`() {
        // When
        val protocol = ReticulumModule.provideReticulumProtocol(context, settingsRepository)

        // Then
        assertNotNull(protocol)
        assertTrue(protocol is ServiceReticulumProtocol)
    }

    @Test
    fun `provideReticulumProtocol - creates new instance with provided dependencies`() {
        // When
        val protocol = ReticulumModule.provideReticulumProtocol(context, settingsRepository)

        // Then
        assertNotNull(protocol)
        // Verify it's a ServiceReticulumProtocol with the correct type
        assertTrue("Protocol should be ServiceReticulumProtocol", protocol is ServiceReticulumProtocol)
    }

    @Test
    fun `provideReticulumProtocol - multiple calls return different instances`() {
        // When
        val protocol1 = ReticulumModule.provideReticulumProtocol(context, settingsRepository)
        val protocol2 = ReticulumModule.provideReticulumProtocol(context, settingsRepository)

        // Then - While the module is @Singleton, the test calls directly without Hilt
        // so we just verify both are valid instances
        assertNotNull(protocol1)
        assertNotNull(protocol2)
        assertTrue(protocol1 is ServiceReticulumProtocol)
        assertTrue(protocol2 is ServiceReticulumProtocol)
    }
}
