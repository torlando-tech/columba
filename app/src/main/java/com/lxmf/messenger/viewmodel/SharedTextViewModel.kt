package com.lxmf.messenger.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class SharedTextViewModel
    @Inject
    constructor() : ViewModel() {
        private val _sharedText = MutableStateFlow<String?>(null)
        val sharedText: StateFlow<String?> = _sharedText

        fun setText(text: String) {
            _sharedText.value = text
        }

        fun consumeText(): String? {
            val current = _sharedText.value
            _sharedText.value = null
            return current
        }

        fun clear() {
            _sharedText.value = null
        }
    }
