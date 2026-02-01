package com.lxmf.messenger.di

import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
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
import org.junit.Assert.assertSame
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
 * - provideReticulumProtocol returns the same ServiceReticulumProtocol instance
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReticulumModuleTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockServiceProtocol: ServiceReticulumProtocol

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Create mock ServiceReticulumProtocol (no methods called, just checking same instance returned)
        mockServiceProtocol = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `provideReticulumProtocol - returns same ServiceReticulumProtocol instance`() {
        // When
        val protocol: ReticulumProtocol = ReticulumModule.provideReticulumProtocol(mockServiceProtocol)

        // Then
        assertNotNull(protocol)
        assertSame("Should return the same instance", mockServiceProtocol, protocol)
    }

    @Test
    fun `provideReticulumProtocol - returns ReticulumProtocol interface`() {
        // When
        val protocol = ReticulumModule.provideReticulumProtocol(mockServiceProtocol)

        // Then
        assertNotNull(protocol)
        assertTrue("Protocol should implement ReticulumProtocol", protocol is ReticulumProtocol)
    }

    @Test
    fun `provideReticulumProtocol - passes through ServiceReticulumProtocol`() {
        // Given (no methods called, just checking same instance returned)
        val serviceProtocol: ServiceReticulumProtocol = mockk()

        // When
        val result = ReticulumModule.provideReticulumProtocol(serviceProtocol)

        // Then
        assertSame("Should pass through the same instance", serviceProtocol, result)
    }
}
