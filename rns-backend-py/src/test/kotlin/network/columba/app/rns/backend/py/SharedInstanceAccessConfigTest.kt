package network.columba.app.rns.backend.py

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedInstanceAccessConfigTest {
    @Test
    fun `host exports exact pasteable config from live rpc key bytes`() {
        assertEquals(
            """[reticulum]
  share_instance = yes
  shared_instance_type = tcp
  shared_instance_port = 37428
  instance_control_port = 37429
  rpc_key = 000f80ff
""",
            formatSharedInstanceAccessConfig(
                isHosting = true,
                rpcKey = byteArrayOf(0x00, 0x0f, 0x80.toByte(), 0xff.toByte()),
            ),
        )
    }

    @Test
    fun `client returns null even when it has imported an rpc key`() {
        assertNull(formatSharedInstanceAccessConfig(isHosting = false, rpcKey = byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `not started returns null`() {
        assertNull(formatSharedInstanceAccessConfig(isHosting = false, rpcKey = null))
    }
}
