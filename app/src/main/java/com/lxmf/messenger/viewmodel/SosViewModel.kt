package com.lxmf.messenger.viewmodel

import androidx.lifecycle.ViewModel
import com.lxmf.messenger.service.SosManager
import com.lxmf.messenger.service.SosState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SosViewModel
    @Inject
    constructor(
        private val sosManager: SosManager,
    ) : ViewModel() {
        val state: StateFlow<SosState> = sosManager.state

        fun trigger() = sosManager.trigger()

        fun cancel() = sosManager.cancel()

        fun deactivate(pin: String? = null): Boolean = sosManager.deactivate(pin)
    }
