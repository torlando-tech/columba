package com.lxmf.messenger.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Returns the current wall-clock time and updates it periodically only while the
 * current lifecycle owner is RESUMED.
 */
@Composable
fun rememberLifecycleTickerMillis(
    periodMs: Long,
    enabled: Boolean = true,
): Long {
    val lifecycleOwner = LocalLifecycleOwner.current

    return produceState(
        initialValue = System.currentTimeMillis(),
        periodMs,
        lifecycleOwner,
        enabled,
    ) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            value = System.currentTimeMillis()
            @Suppress("StateFlowPollingLoop")
            while (enabled) {
                delay(periodMs)
                value = System.currentTimeMillis()
            }
        }
    }.value
}
