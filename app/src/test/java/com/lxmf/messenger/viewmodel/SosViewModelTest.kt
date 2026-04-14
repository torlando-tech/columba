package com.lxmf.messenger.viewmodel

import com.lxmf.messenger.service.SosManager
import com.lxmf.messenger.service.SosState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SosViewModelTest {
    private lateinit var sosManager: SosManager
    private lateinit var viewModel: SosViewModel

    @Before
    fun setUp() {
        sosManager = mockk()
        every { sosManager.state } returns MutableStateFlow(SosState.Idle)
        every { sosManager.trigger() } just Runs
        every { sosManager.cancel() } just Runs
        every { sosManager.deactivate(any()) } returns true
        viewModel = SosViewModel(sosManager)
    }

    @Test
    fun `initial state is Idle`() {
        assertEquals(SosState.Idle, viewModel.state.value)
    }

    @Test
    fun `state reflects manager state`() {
        val stateFlow = MutableStateFlow<SosState>(SosState.Idle)
        every { sosManager.state } returns stateFlow
        val vm = SosViewModel(sosManager)

        stateFlow.value = SosState.Active(2, 0)
        assertEquals(SosState.Active(2, 0), vm.state.value)
    }

    @Test
    fun `trigger delegates to manager`() {
        viewModel.trigger()
        verify { sosManager.trigger() }
        // State remains Idle since mock trigger doesn't change state
        assertEquals(SosState.Idle, viewModel.state.value)
    }

    @Test
    fun `cancel delegates to manager`() {
        viewModel.cancel()
        verify { sosManager.cancel() }
        // State remains Idle since mock cancel doesn't change state
        assertEquals(SosState.Idle, viewModel.state.value)
    }

    @Test
    fun `deactivate without pin delegates to manager`() {
        val result = viewModel.deactivate()
        verify { sosManager.deactivate(null) }
        assertTrue(result)
    }

    @Test
    fun `deactivate with pin delegates to manager`() {
        val result = viewModel.deactivate("1234")
        verify { sosManager.deactivate("1234") }
        assertTrue(result)
    }

    @Test
    fun `deactivate returns false when manager returns false`() {
        every { sosManager.deactivate(any()) } returns false
        val result = viewModel.deactivate("wrong")
        assertFalse(result)
    }
}
