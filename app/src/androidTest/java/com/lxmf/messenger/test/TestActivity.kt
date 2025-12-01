package com.lxmf.messenger.test

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Simple test activity for Compose UI tests.
 * Provides a clean container for testing Composable functions in isolation.
 */
class TestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Content will be set by test via setContent
    }
}
