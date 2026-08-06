package network.columba.app.rns.backend.py

import org.junit.Assert.assertEquals
import org.junit.Test

class OutgoingTransferPollActionTest {
    @Test
    fun `waits for asynchronous Resource representation selection`() {
        assertEquals(
            OutgoingTransferPollAction.WAIT,
            outgoingTransferPollAction(
                representation = PythonRnsLxmf.LXMF_REPRESENTATION_UNKNOWN,
                state = PythonRnsLxmf.LXMF_STATE_OUTBOUND,
            ),
        )
        assertEquals(
            OutgoingTransferPollAction.PUBLISH,
            outgoingTransferPollAction(
                representation = PythonRnsLxmf.LXMF_REPRESENTATION_RESOURCE,
                state = PythonRnsLxmf.LXMF_STATE_SENDING,
            ),
        )
    }

    @Test
    fun `stops for packet and terminal messages without publishing progress`() {
        assertEquals(
            OutgoingTransferPollAction.STOP,
            outgoingTransferPollAction(
                representation = PythonRnsLxmf.LXMF_REPRESENTATION_PACKET,
                state = PythonRnsLxmf.LXMF_STATE_SENDING,
            ),
        )
        assertEquals(
            OutgoingTransferPollAction.STOP,
            outgoingTransferPollAction(
                representation = PythonRnsLxmf.LXMF_REPRESENTATION_UNKNOWN,
                state = PythonRnsLxmf.LXMF_STATE_FAILED,
            ),
        )
    }
}
