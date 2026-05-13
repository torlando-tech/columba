package network.columba.app.rns.api

import android.os.Parcel
import android.os.Parcelable
import network.columba.app.rns.api.model.AnnounceRestoreEntry
import network.columba.app.rns.api.model.FileAttachment
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.api.model.Link
import network.columba.app.rns.api.model.LinkEvent
import network.columba.app.rns.api.model.LinkStatus
import network.columba.app.rns.api.model.NetworkStatus
import network.columba.app.rns.api.model.NetworkRestriction
import network.columba.app.rns.api.model.PeerIdentityEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Parcel round-trip tests for the manual-Parcelable sealed classes ([RnsError],
 * [NetworkStatus], [LinkEvent], [InterfaceConfig]) plus the small wrapper
 * Parcelables added for AIDL transport ([FileAttachment], [PeerIdentityEntry],
 * [AnnounceRestoreEntry]).
 *
 * These run under Robolectric because `Parcel` is an Android framework class
 * with no JVM implementation. Each test marshals an instance through a Parcel
 * and asserts the unmarshaled value equals the original — catching tag/field
 * write-read drift in the manual implementations.
 */
@RunWith(RobolectricTestRunner::class)
class ParcelRoundTripTest {
    private fun <T : Parcelable> roundTrip(
        value: T,
        creator: Parcelable.Creator<T>,
    ): T {
        val parcel = Parcel.obtain()
        try {
            value.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return creator.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    /**
     * Round-trip via `writeParcelable`/`readParcelable` for @Parcelize types
     * whose auto-generated `CREATOR` field isn't reachable from Kotlin source
     * (Parcelize emits it as a JVM static, not a companion-object member).
     * The framework embeds the class name and dispatches to the right CREATOR
     * automatically.
     */
    @Suppress("DEPRECATION") // readParcelable(ClassLoader) targets minSdk 24.
    private inline fun <reified T : Parcelable> roundTripViaFramework(value: T): T {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(value, 0)
            parcel.setDataPosition(0)
            return parcel.readParcelable<T>(T::class.java.classLoader)
                ?: error("readParcelable returned null for ${T::class.java.name}")
        } finally {
            parcel.recycle()
        }
    }

    // ==================== RnsError ====================

    @Test
    fun `RnsError Generic round-trips`() {
        val original = RnsError.Generic("boom", "at foo(bar.kt:1)")
        val restored = roundTrip(original, RnsError.CREATOR)
        assertEquals(original, restored)
    }

    @Test
    fun `RnsError Generic round-trips with null stack trace`() {
        val original = RnsError.Generic("boom", null)
        val restored = roundTrip(original, RnsError.CREATOR)
        assertEquals(original, restored)
    }

    @Test
    fun `RnsError BackendNotReady round-trips as singleton`() {
        val restored = roundTrip(RnsError.BackendNotReady, RnsError.CREATOR)
        assertEquals(RnsError.BackendNotReady, restored)
        assertTrue(restored === RnsError.BackendNotReady)
    }

    @Test
    fun `RnsError IdentityNotFound round-trips`() {
        val original = RnsError.IdentityNotFound("deadbeef")
        val restored = roundTrip(original, RnsError.CREATOR)
        assertEquals(original, restored)
    }

    @Test
    fun `RnsError TimeoutExceeded round-trips`() {
        val original = RnsError.TimeoutExceeded("sendLxmfMessage", 5000)
        val restored = roundTrip(original, RnsError.CREATOR)
        assertEquals(original, restored)
    }

    @Test
    fun `RnsError FeatureUnsupported round-trips`() {
        val original = RnsError.FeatureUnsupported("performance.batteryProfileTuning")
        val restored = roundTrip(original, RnsError.CREATOR)
        assertEquals(original, restored)
    }

    @Test
    fun `RnsError CallStateInvalid round-trips`() {
        val original = RnsError.CallStateInvalid("ESTABLISHED", "RINGING")
        val restored = roundTrip(original, RnsError.CREATOR)
        assertEquals(original, restored)
    }

    @Test
    fun `RnsError NomadnetPageNotFound round-trips`() {
        val original = RnsError.NomadnetPageNotFound("abc123", "/page/index.mu")
        val restored = roundTrip(original, RnsError.CREATOR)
        assertEquals(original, restored)
    }

    // ==================== NetworkStatus ====================

    @Test
    fun `NetworkStatus objects round-trip as singletons`() {
        assertTrue(roundTrip(NetworkStatus.INITIALIZING, NetworkStatus.CREATOR) === NetworkStatus.INITIALIZING)
        assertTrue(roundTrip(NetworkStatus.CONNECTING, NetworkStatus.CREATOR) === NetworkStatus.CONNECTING)
        assertTrue(roundTrip(NetworkStatus.READY, NetworkStatus.CREATOR) === NetworkStatus.READY)
        assertTrue(roundTrip(NetworkStatus.SHUTDOWN, NetworkStatus.CREATOR) === NetworkStatus.SHUTDOWN)
    }

    @Test
    fun `NetworkStatus ERROR round-trips with message`() {
        val original = NetworkStatus.ERROR("test failure")
        val restored = roundTrip(original, NetworkStatus.CREATOR)
        assertEquals(original, restored)
    }

    // ==================== LinkEvent ====================

    @Test
    fun `LinkEvent Established round-trips`() {
        val link = makeLink()
        val original: LinkEvent = LinkEvent.Established(link)
        val restored = roundTrip(original, LinkEvent.CREATOR)
        assertEquals(original, restored)
    }

    @Test
    fun `LinkEvent DataReceived round-trips with bytes`() {
        val link = makeLink()
        val original: LinkEvent = LinkEvent.DataReceived(link, byteArrayOf(1, 2, 3, 4, 5))
        val restored = roundTrip(original, LinkEvent.CREATOR)
        assertEquals(original, restored)
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5),
            (restored as LinkEvent.DataReceived).data,
        )
    }

    @Test
    fun `LinkEvent Closed round-trips with null reason`() {
        val original: LinkEvent = LinkEvent.Closed(makeLink(), null)
        val restored = roundTrip(original, LinkEvent.CREATOR)
        assertEquals(original, restored)
    }

    @Test
    fun `LinkEvent Closed round-trips with reason`() {
        val original: LinkEvent = LinkEvent.Closed(makeLink(), "remote hangup")
        val restored = roundTrip(original, LinkEvent.CREATOR)
        assertEquals(original, restored)
    }

    // ==================== InterfaceConfig ====================

    @Test
    fun `InterfaceConfig AutoInterface round-trips`() {
        val original: InterfaceConfig = InterfaceConfig.AutoInterface(
            name = "MyAuto",
            enabled = true,
            groupId = "test-group",
            discoveryScope = "site",
            discoveryPort = 29716,
            dataPort = 42671,
            mode = "gateway",
            networkRestriction = NetworkRestriction.WIFI_ONLY,
        )
        val restored = roundTrip(original, InterfaceConfig.CREATOR)
        assertEquals(original, restored)
    }

    @Test
    fun `InterfaceConfig AutoInterface round-trips with null ports`() {
        val original: InterfaceConfig = InterfaceConfig.AutoInterface()
        val restored = roundTrip(original, InterfaceConfig.CREATOR)
        assertEquals(original, restored)
    }

    @Test
    fun `InterfaceConfig TCPClient round-trips`() {
        val original: InterfaceConfig = InterfaceConfig.TCPClient(
            targetHost = "192.168.1.1",
            targetPort = 4242,
            socksProxyEnabled = true,
        )
        val restored = roundTrip(original, InterfaceConfig.CREATOR)
        assertEquals(original, restored)
    }

    @Test
    fun `InterfaceConfig RNode round-trips with all nullables populated`() {
        val original: InterfaceConfig = InterfaceConfig.RNode(
            targetDeviceName = "RNode 1234",
            usbDeviceId = 42,
            usbVendorId = 0x1A86,
            usbProductId = 0x7523,
            stAlock = 1.5,
            ltAlock = 2.0,
        )
        val restored = roundTrip(original, InterfaceConfig.CREATOR)
        assertEquals(original, restored)
    }

    @Test
    fun `InterfaceConfig RNode round-trips with all nullables null`() {
        val original: InterfaceConfig = InterfaceConfig.RNode()
        val restored = roundTrip(original, InterfaceConfig.CREATOR)
        assertEquals(original, restored)
    }

    @Test
    fun `InterfaceConfig UDP round-trips`() {
        val original: InterfaceConfig = InterfaceConfig.UDP()
        val restored = roundTrip(original, InterfaceConfig.CREATOR)
        assertEquals(original, restored)
    }

    @Test
    fun `InterfaceConfig AndroidBLE round-trips`() {
        val original: InterfaceConfig = InterfaceConfig.AndroidBLE(deviceName = "Pixel")
        val restored = roundTrip(original, InterfaceConfig.CREATOR)
        assertEquals(original, restored)
    }

    @Test
    fun `InterfaceConfig TCPServer round-trips`() {
        val original: InterfaceConfig = InterfaceConfig.TCPServer(listenPort = 4243)
        val restored = roundTrip(original, InterfaceConfig.CREATOR)
        assertEquals(original, restored)
    }

    // ==================== Wrapper Parcelables ====================

    @Test
    fun `FileAttachment round-trips`() {
        val original = FileAttachment("photo.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        val restored = roundTripViaFramework(original)
        assertEquals(original, restored)
        assertArrayEquals(original.data, restored.data)
    }

    @Test
    fun `PeerIdentityEntry round-trips`() {
        val original = PeerIdentityEntry("deadbeef", ByteArray(32) { it.toByte() })
        val restored = roundTripViaFramework(original)
        assertEquals(original, restored)
    }

    @Test
    fun `AnnounceRestoreEntry round-trips`() {
        val original = AnnounceRestoreEntry("cafef00d", ByteArray(64) { (it * 2).toByte() })
        val restored = roundTripViaFramework(original)
        assertEquals(original, restored)
    }

    // ==================== Helpers ====================

    private fun makeLink(): Link {
        val identity = network.columba.app.rns.api.model.Identity(
            hash = ByteArray(16) { it.toByte() },
            publicKey = ByteArray(32) { it.toByte() },
            privateKey = null,
        )
        val destination = network.columba.app.rns.api.model.Destination(
            hash = ByteArray(16) { it.toByte() },
            hexHash = "0123456789abcdef",
            identity = identity,
            direction = network.columba.app.rns.api.model.Direction.OUT,
            type = network.columba.app.rns.api.model.DestinationType.SINGLE,
            appName = "test.app",
            aspects = listOf("lxmf", "delivery"),
        )
        return Link(
            id = "link-1",
            destination = destination,
            status = LinkStatus.ACTIVE,
            establishedAt = 1_700_000_000_000L,
            rtt = 0.15f,
        )
    }
}
