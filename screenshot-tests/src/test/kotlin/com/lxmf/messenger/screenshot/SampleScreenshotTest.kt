package com.lxmf.messenger.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Sample screenshot test demonstrating Paparazzi setup.
 *
 * This module is set up to run screenshot tests without a device/emulator.
 * Tests run on JVM using Android's layoutlib.
 *
 * To record golden images: ./gradlew :screenshot-tests:recordPaparazziDebug
 * To verify against golden images: ./gradlew :screenshot-tests:verifyPaparazziDebug
 *
 * Note: To test actual app composables, the UI components need to be in a
 * separate library module that both :app and :screenshot-tests can depend on.
 * This sample demonstrates the basic setup.
 */
class SampleScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun sampleComponent_light() {
        paparazzi.snapshot {
            MaterialTheme {
                Surface {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Screenshot Testing Setup Complete!",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun sampleCard_withContent() {
        paparazzi.snapshot {
            MaterialTheme {
                Surface {
                    androidx.compose.material3.Card(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            text = "Sample Card Content",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}
