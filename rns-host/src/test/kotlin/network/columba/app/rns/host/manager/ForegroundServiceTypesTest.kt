package network.columba.app.rns.host.manager

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the foreground-service types the service claims at runtime.
 *
 * Background microphone capture is only exempted from the while-in-use
 * restriction while a foreground service runs with MICROPHONE among its active
 * types; the manifest declaration alone is not enough, [android.app.Service.startForeground]
 * has to pass the type. Recording permission is a hard precondition on API 34+
 * (SecurityException otherwise).
 */
class ForegroundServiceTypesTest {
    private val connectedDevice = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private val microphone = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    @Test
    fun `idle call claims connected device only`() {
        assertEquals(connectedDevice, foregroundServiceTypes(callActive = false, recordAudioGranted = true, sdkInt = 34))
    }

    @Test
    fun `active call with recording permission adds microphone`() {
        assertEquals(connectedDevice or microphone, foregroundServiceTypes(callActive = true, recordAudioGranted = true, sdkInt = 34))
    }

    @Test
    fun `active call without recording permission stays connected device`() {
        // API 34 rejects startForeground(MICROPHONE) with SecurityException when
        // RECORD_AUDIO is not granted, so the type must not be requested.
        assertEquals(connectedDevice, foregroundServiceTypes(callActive = true, recordAudioGranted = false, sdkInt = 34))
    }

    @Test
    fun `microphone type is not requested below API 30`() {
        // FGS types start at API 29 and MICROPHONE only exists from API 30; a
        // type outside the platform's known set would fail the subset check.
        assertEquals(connectedDevice, foregroundServiceTypes(callActive = true, recordAudioGranted = true, sdkInt = 29))
    }

    @Test
    fun `microphone type is claimed from API 30 up`() {
        assertEquals(connectedDevice or microphone, foregroundServiceTypes(callActive = true, recordAudioGranted = true, sdkInt = 30))
    }
}
