package network.columba.app.smoke

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.columba.app.service.ReticulumService
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Smoke tests for quick device verification.
 *
 * Verifies the APK installs, the app context is available, and the
 * :reticulum service process starts and produces a non-null binder when
 * bound. Deep protocol assertions live in the main unit-test suite —
 * these exist only for rapid deploy-verification via
 * scripts/verify-on-device.sh.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    private lateinit var context: Context
    private var binder: IBinder? = null
    private var serviceBound = false
    private val bindLatch = CountDownLatch(1)

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                binder = service
                serviceBound = true
                bindLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                serviceBound = false
            }
        }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        if (serviceBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                println("SmokeTest: Cleanup error: ${e.message}")
            }
        }
    }

    @Test
    fun test01_appContextAvailable() {
        assertNotNull("App context should not be null", context)
        assertTrue(
            "Package name should be network.columba.app",
            context.packageName == "network.columba.app",
        )
    }

    @Test
    fun test02_serviceBinds() {
        val intent = Intent(context, ReticulumService::class.java)
        val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        assertTrue("bindService should return true", bound)

        val connected = bindLatch.await(10, TimeUnit.SECONDS)
        assertTrue("Service should connect within 10 seconds", connected)
        assertNotNull("Binder from onServiceConnected should not be null", binder)
    }
}
