package network.columba.app.rns.api.util

import network.columba.app.domain.model.SignatureStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class LxmfSignatureStatusTest {
    @Test
    fun `known source with a valid signature is verified`() {
        assertEquals(SignatureStatus.VERIFIED, lxmfSignatureStatus(true, null))
    }

    @Test
    fun `unknown identity is distinct from a checked invalid signature`() {
        assertEquals(SignatureStatus.SOURCE_UNKNOWN, lxmfSignatureStatus(false, 0x01))
        assertEquals(SignatureStatus.INVALID, lxmfSignatureStatus(false, 0x02))
    }

    @Test
    fun `missing or exceptional verification results never imply invalid signature`() {
        assertEquals(SignatureStatus.UNVERIFIED, lxmfSignatureStatus(false, null))
        assertEquals(SignatureStatus.UNVERIFIED, lxmfSignatureStatus(null, null))
        assertEquals(SignatureStatus.UNVERIFIED, lxmfSignatureStatus(false, 0x03))
    }

    @Test
    fun `successful validation outranks an obsolete unverified reason`() {
        assertEquals(SignatureStatus.VERIFIED, lxmfSignatureStatus(true, 0x01))
        assertEquals(SignatureStatus.VERIFIED, lxmfSignatureStatus(true, 0x02))
    }
}
