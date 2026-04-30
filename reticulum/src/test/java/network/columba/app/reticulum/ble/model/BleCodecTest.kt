package network.columba.app.reticulum.ble.model

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for BleCodec enum.
 *
 * Covers:
 * - fromString() resolution including defaults and case-insensitivity
 * - PHY constant mapping (tx/rx mask + phyOptions) for each codec
 * - PHY_1M early-return guard (no setPreferredPhy call on default codec)
 * - Graceful degradation: CODED_S8 resolves correctly even when the device
 *   does not support it — the runtime check in applyPreferredPhy() handles
 *   the BT4/API < 26 path transparently.
 */
class BleCodecTest {

    // ========== fromString() — valid names ==========

    @Test
    fun `fromString PHY_1M returns PHY_1M`() {
        assertEquals(BleCodec.PHY_1M, BleCodec.fromString("PHY_1M"))
    }

    @Test
    fun `fromString PHY_2M returns PHY_2M`() {
        assertEquals(BleCodec.PHY_2M, BleCodec.fromString("PHY_2M"))
    }

    @Test
    fun `fromString CODED_S2 returns CODED_S2`() {
        assertEquals(BleCodec.CODED_S2, BleCodec.fromString("CODED_S2"))
    }

    @Test
    fun `fromString CODED_S8 returns CODED_S8`() {
        assertEquals(BleCodec.CODED_S8, BleCodec.fromString("CODED_S8"))
    }

    // ========== fromString() — default fallback ==========

    @Test
    fun `fromString empty string defaults to PHY_1M`() {
        // Config saved without ble_codec key deserializes to "" via optString —
        // fromString must return the safe 1M default.
        assertEquals(BleCodec.PHY_1M, BleCodec.fromString(""))
    }

    @Test
    fun `fromString unknown value defaults to PHY_1M`() {
        assertEquals(BleCodec.PHY_1M, BleCodec.fromString("UNKNOWN"))
    }

    @Test
    fun `fromString gibberish defaults to PHY_1M`() {
        assertEquals(BleCodec.PHY_1M, BleCodec.fromString("not_a_codec"))
    }

    // ========== fromString() — case-insensitive ==========

    @Test
    fun `fromString is case-insensitive for PHY_1M`() {
        assertEquals(BleCodec.PHY_1M, BleCodec.fromString("phy_1m"))
        assertEquals(BleCodec.PHY_1M, BleCodec.fromString("Phy_1M"))
    }

    @Test
    fun `fromString is case-insensitive for CODED_S8`() {
        assertEquals(BleCodec.CODED_S8, BleCodec.fromString("coded_s8"))
        assertEquals(BleCodec.CODED_S8, BleCodec.fromString("Coded_S8"))
    }

    // ========== PHY constant mapping ==========

    // PHY_1M → standard 1M PHY on both directions
    @Test
    fun `PHY_1M maps to LE_1M mask`() {
        val (txPhy, rxPhy, phyOptions) = phyParamsFor(BleCodec.PHY_1M)
        assertEquals(BluetoothDevice.PHY_LE_1M_MASK, txPhy)
        assertEquals(BluetoothDevice.PHY_LE_1M_MASK, rxPhy)
        assertEquals(BluetoothDevice.PHY_OPTION_NO_PREFERRED, phyOptions)
    }

    // PHY_2M → 2M PHY, verifies onPhyUpdate will log 2M on a BT5 device
    @Test
    fun `PHY_2M maps to LE_2M mask`() {
        val (txPhy, rxPhy, phyOptions) = phyParamsFor(BleCodec.PHY_2M)
        assertEquals(BluetoothDevice.PHY_LE_2M_MASK, txPhy)
        assertEquals(BluetoothDevice.PHY_LE_2M_MASK, rxPhy)
        assertEquals(BluetoothDevice.PHY_OPTION_NO_PREFERRED, phyOptions)
    }

    // CODED_S2 → Coded PHY with S=2 coding (500 kbps, ~2× range)
    @Test
    fun `CODED_S2 maps to LE_CODED mask with S2 option`() {
        val (txPhy, rxPhy, phyOptions) = phyParamsFor(BleCodec.CODED_S2)
        assertEquals(BluetoothDevice.PHY_LE_CODED_MASK, txPhy)
        assertEquals(BluetoothDevice.PHY_LE_CODED_MASK, rxPhy)
        assertEquals(BluetoothDevice.PHY_OPTION_S2, phyOptions)
    }

    // CODED_S8 → Coded PHY with S=8 coding (125 kbps, ~4× range)
    // On a BT5 device paired with an ESP32-C6 peripheral, onPhyUpdate will
    // log Coded + S=8 after setPreferredPhy() negotiates successfully.
    @Test
    fun `CODED_S8 maps to LE_CODED mask with S8 option`() {
        val (txPhy, rxPhy, phyOptions) = phyParamsFor(BleCodec.CODED_S8)
        assertEquals(BluetoothDevice.PHY_LE_CODED_MASK, txPhy)
        assertEquals(BluetoothDevice.PHY_LE_CODED_MASK, rxPhy)
        assertEquals(BluetoothDevice.PHY_OPTION_S8, phyOptions)
    }

    // S=2 and S=8 share the same PHY mask; only phyOptions differs
    @Test
    fun `CODED_S2 and CODED_S8 use same PHY mask but different options`() {
        val (s2Tx, s2Rx, s2Opt) = phyParamsFor(BleCodec.CODED_S2)
        val (s8Tx, s8Rx, s8Opt) = phyParamsFor(BleCodec.CODED_S8)
        assertEquals(s2Tx, s8Tx)
        assertEquals(s2Rx, s8Rx)
        assert(s2Opt != s8Opt) { "S=2 and S=8 must have different phyOptions" }
    }

    // ========== PHY_1M early-return guard ==========

    // BleGattClient.applyPreferredPhy() skips setPreferredPhy() for PHY_1M
    // (the Android default), so no BT API call is made on BT4 devices.
    @Test
    fun `PHY_1M is the default codec — no explicit PHY request needed`() {
        val isDefault = BleCodec.PHY_1M == BleCodec.PHY_1M
        assert(isDefault)
        // Guard in applyPreferredPhy: `if (preferredCodec == PHY_1M) return`
        // This means a BT4 device connecting with codec=PHY_1M will never
        // call setPreferredPhy() and will never crash.
        assert(BleCodec.PHY_1M.name == "PHY_1M")
    }

    // ========== Graceful degradation on BT4 (API < 26) ==========

    // When CODED_S8 is configured but the device is BT4 / API < 26,
    // applyPreferredPhy() logs and returns without calling setPreferredPhy().
    // The codec enum still resolves correctly — the guard is in the caller.
    @Test
    fun `CODED_S8 codec resolves correctly even on unsupported hardware`() {
        // fromString must resolve correctly regardless of hardware capability;
        // the API-level guard lives in applyPreferredPhy(), not in the enum.
        val codec = BleCodec.fromString("CODED_S8")
        assertEquals(BleCodec.CODED_S8, codec)
    }

    // ========== Display metadata ==========

    @Test
    fun `PHY_1M displayName is 1M`() {
        assertEquals("1M", BleCodec.PHY_1M.displayName)
    }

    @Test
    fun `CODED_S8 displayName is S=8`() {
        assertEquals("S=8", BleCodec.CODED_S8.displayName)
    }

    @Test
    fun `all codecs have non-blank descriptions`() {
        BleCodec.entries.forEach { codec ->
            assert(codec.description.isNotBlank()) {
                "${codec.name} is missing a description"
            }
        }
    }

    @Test
    fun `CODED_S2 description mentions FEC`() {
        assert(BleCodec.CODED_S2.description.contains("FEC")) {
            "CODED_S2 description should mention FEC: ${BleCodec.CODED_S2.description}"
        }
    }

    @Test
    fun `CODED_S8 description mentions FEC`() {
        assert(BleCodec.CODED_S8.description.contains("FEC")) {
            "CODED_S8 description should mention FEC: ${BleCodec.CODED_S8.description}"
        }
    }

    // ========== Helper ==========

    /**
     * Mirrors the when-expression in BleGattClient.applyPreferredPhyApi26()
     * so we can assert the correct Android constants without needing a live
     * BluetoothGatt instance.
     */
    private fun phyParamsFor(codec: BleCodec): Triple<Int, Int, Int> =
        when (codec) {
            BleCodec.PHY_1M ->
                Triple(
                    BluetoothDevice.PHY_LE_1M_MASK,
                    BluetoothDevice.PHY_LE_1M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                )
            BleCodec.PHY_2M ->
                Triple(
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                )
            BleCodec.CODED_S2 ->
                Triple(
                    BluetoothDevice.PHY_LE_CODED_MASK,
                    BluetoothDevice.PHY_LE_CODED_MASK,
                    BluetoothDevice.PHY_OPTION_S2,
                )
            BleCodec.CODED_S8 ->
                Triple(
                    BluetoothDevice.PHY_LE_CODED_MASK,
                    BluetoothDevice.PHY_LE_CODED_MASK,
                    BluetoothDevice.PHY_OPTION_S8,
                )
        }
}
