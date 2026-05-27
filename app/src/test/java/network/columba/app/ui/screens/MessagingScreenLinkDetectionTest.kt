package network.columba.app.ui.screens

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [detectLinkRanges], the link detection behind LinkifiedMessageText.
 *
 * Runs under Robolectric because detection relies on android.util.Patterns.WEB_URL, which
 * is null in the plain unit-test Android stub. Assertions compare the *substrings* the
 * detector linkifies — that substring is both the highlighted span and the value handed to
 * the click handler, so it determines whether a tap opens the in-app NomadNet browser or a
 * bogus web page (#921).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MessagingScreenLinkDetectionTest {
    private fun links(text: String): List<String> =
        detectLinkRanges(text).map { (start, end) -> text.substring(start, end) }

    @Test
    fun `bare NomadNet address is linkified whole, not just the mu tail`() {
        // The exact case from #921: the spurious "register.mu" web match nested inside the
        // address must lose to the full NomadNet address.
        val text = "9ce92808be498e9e05590ff27cbfdfe4:/page/forum/register.mu"
        assertEquals(listOf(text), links(text))
    }

    @Test
    fun `NomadNet address inside surrounding text is linkified whole`() {
        val text = "Verify at 9ce92808be498e9e05590ff27cbfdfe4:/page/forum/register.mu to continue"
        assertEquals(
            listOf("9ce92808be498e9e05590ff27cbfdfe4:/page/forum/register.mu"),
            links(text),
        )
    }

    @Test
    fun `trailing closing paren is excluded from a NomadNet address`() {
        val text = "(see 9ce92808be498e9e05590ff27cbfdfe4:/page/forum/register.mu)"
        assertEquals(
            listOf("9ce92808be498e9e05590ff27cbfdfe4:/page/forum/register.mu"),
            links(text),
        )
    }

    @Test
    fun `plain web URL is still detected`() {
        val text = "see https://example.com/path for details"
        assertEquals(listOf("https://example.com/path"), links(text))
    }

    @Test
    fun `schemed nomadnetwork link is kept as a single range`() {
        val text = "open nomadnetwork://9ce92808be498e9e05590ff27cbfdfe4:/page/index.mu"
        assertEquals(
            listOf("nomadnetwork://9ce92808be498e9e05590ff27cbfdfe4:/page/index.mu"),
            links(text),
        )
    }

    @Test
    fun `bare 32-hex hash without a path is not linkified`() {
        val text = "my address is 9ce92808be498e9e05590ff27cbfdfe4 ok"
        assertEquals(emptyList<String>(), links(text))
    }

    @Test
    fun `web URL and NomadNet address coexist as two ranges`() {
        val text =
            "web https://example.com and node 9ce92808be498e9e05590ff27cbfdfe4:/page/index.mu"
        assertEquals(
            listOf(
                "https://example.com",
                "9ce92808be498e9e05590ff27cbfdfe4:/page/index.mu",
            ),
            links(text),
        )
    }
}
