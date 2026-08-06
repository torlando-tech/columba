package network.columba.app.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneAdmissionArbiterTest {
    @Test
    fun `call and voice recording admission are mutually exclusive`() {
        val arbiter = MicrophoneAdmissionArbiter()

        assertTrue(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING))
        assertFalse(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING))
        assertTrue(arbiter.ensureOwned(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING))
        assertFalse(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.CALL))
        assertTrue(arbiter.isOwnedBy(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING))

        arbiter.release(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING)

        assertTrue(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.CALL))
        assertFalse(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING))
    }

    @Test
    fun `only the owning lifecycle can release admission`() {
        val arbiter = MicrophoneAdmissionArbiter()
        assertTrue(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.CALL))

        arbiter.release(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING)

        assertTrue(arbiter.isOwnedBy(MicrophoneAdmissionArbiter.Owner.CALL))
    }
}
