package com.lxmf.messenger.receiver

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lxmf.messenger.service.ReticulumService
import com.lxmf.messenger.service.SosTriggerService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Unit tests for [BootReceiver].
 *
 * Verifies that BOOT_COMPLETED starts the expected services and that
 * other actions are ignored.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BootReceiverTest {
    private lateinit var context: Application

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun getAllStartedServices(): List<Intent> {
        val shadow = Shadows.shadowOf(context)
        val services = mutableListOf<Intent>()
        var next = shadow.nextStartedService
        while (next != null) {
            services.add(next)
            next = shadow.nextStartedService
        }
        return services
    }

    @Test
    fun `ignores non-boot-completed actions`() {
        val receiver = BootReceiver()
        receiver.onReceive(context, Intent("com.example.SOME_ACTION"))

        val shadow = Shadows.shadowOf(context)
        assertNull("No service should be started for non-BOOT action", shadow.nextStartedService)
    }

    @Test
    fun `boot completed starts ReticulumService`() {
        val receiver = BootReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val services = getAllStartedServices()
        assertTrue(
            "Expected ReticulumService to be started, got: ${services.map { it.component?.className }}",
            services.any { it.component?.className == ReticulumService::class.java.name },
        )
    }

    @Test
    fun `boot completed starts SosTriggerService`() {
        val receiver = BootReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val services = getAllStartedServices()
        assertTrue(
            "Expected SosTriggerService to be started, got: ${services.map { it.component?.className }}",
            services.any { it.component?.className == SosTriggerService::class.java.name },
        )
    }

    @Test
    fun `boot completed sets ReticulumService ACTION_START`() {
        val receiver = BootReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val services = getAllStartedServices()
        val reticulumIntent =
            services.find {
                it.component?.className == ReticulumService::class.java.name
            }
        assertNotNull("ReticulumService should be started", reticulumIntent)
        assertTrue(
            "ReticulumService intent should have ACTION_START",
            reticulumIntent!!.action == ReticulumService.ACTION_START,
        )
    }
}
