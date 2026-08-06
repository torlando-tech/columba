package network.columba.app.audio

import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.api.model.CallState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallMicrophoneAdmissionCoordinatorTest {
    @Test
    fun `call lease survives screen owner and releases on terminal state`() = runTest {
        val callState = MutableStateFlow<CallState>(CallState.Idle)
        val telephony = mockk<RnsTelephony>()
        every { telephony.callState } returns callState
        val arbiter = MicrophoneAdmissionArbiter()
        val coordinator = CallMicrophoneAdmissionCoordinator(arbiter, telephony, backgroundScope)
        runCurrent()

        assertEquals(true, coordinator.tryAcquireForOutgoing())
        coordinator.markOutgoingStarted()
        callState.value = CallState.Active("peer")
        runCurrent()

        // A screen-scoped CallViewModel may now be gone; the coordinator remains application-scoped.
        callState.value = CallState.Ended
        runCurrent()

        assertNotNull(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING))
    }
}
