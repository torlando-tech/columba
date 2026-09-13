package network.columba.app.rns.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Live stats of an existing NomadNet link to a node, for image-loading and
 * other bandwidth decisions. Mirrors what upstream Browser.py reads directly
 * off `self.link` (`link.rtt`, `link.get_expected_rate()`).
 *
 * Never establishes anything: null means "no active link data available".
 *
 * @property rttSeconds link round-trip time in seconds, if measured
 * @property expectedRateBps expected data rate in bits/s (from measured
 *   transfers on this link), null before any transfer has been measured
 */
@Parcelize
data class NomadnetLinkStats(
    val rttSeconds: Double?,
    val expectedRateBps: Long?,
) : Parcelable
