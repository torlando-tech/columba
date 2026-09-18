package network.columba.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneAdmissionArbiterTest {
    @Test
    fun `call and voice recording admission are mutually exclusive`() {
        val arbiter = MicrophoneAdmissionArbiter()

        val voiceLease = arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING)
        assertNotNull(voiceLease)
        assertNull(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING))
        assertNull(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.CALL))
        assertEquals(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING, arbiter.currentOwner())

        arbiter.release(requireNotNull(voiceLease))

        assertNotNull(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.CALL))
        assertNull(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING))
    }

    @Test
    fun `stale lease cannot release replacement admission`() {
        val arbiter = MicrophoneAdmissionArbiter()
        val original = requireNotNull(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.CALL))
        arbiter.release(original)
        val replacement = requireNotNull(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.CALL))

        arbiter.release(original)

        assertTrue(arbiter.isActive(replacement))
    }

    @Test
    fun `matching owner can adopt active call lease after recreation`() {
        val arbiter = MicrophoneAdmissionArbiter()
        val original = requireNotNull(arbiter.tryAcquire(MicrophoneAdmissionArbiter.Owner.CALL))

        val adopted = arbiter.adoptOrAcquire(MicrophoneAdmissionArbiter.Owner.CALL)

        assertEquals(original, adopted)
        assertNull(arbiter.adoptOrAcquire(MicrophoneAdmissionArbiter.Owner.VOICE_RECORDING))
    }
}
