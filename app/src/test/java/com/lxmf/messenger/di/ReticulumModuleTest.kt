package com.lxmf.messenger.di

import android.content.Context
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReticulumModuleTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockContext: Context

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockContext = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `provideReticulumProtocol - returns ReticulumProtocol instance`() {
        // When
        val protocol: ReticulumProtocol =
            ReticulumModule.provideReticulumProtocol(mockContext)

        // Then
        assertNotNull(protocol)
        assertTrue("Protocol should implement ReticulumProtocol", protocol is ReticulumProtocol)
    }
}
