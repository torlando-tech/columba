package network.columba.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted name -> level mapping is a privacy boundary: an unrecognised value
 * must never fall back to a level that reveals message text.
 */
class NotificationPrivacyLevelTest {
    @Test
    fun `fromStoredName round trips every level`() {
        NotificationPrivacyLevel.entries.forEach { level ->
            assertEquals(level, NotificationPrivacyLevel.fromStoredName(level.name))
        }
    }

    @Test
    fun `fromStoredName fails closed to NONE on unknown value`() {
        assertEquals(NotificationPrivacyLevel.NONE, NotificationPrivacyLevel.fromStoredName("SOMETHING_NEW"))
        assertEquals(NotificationPrivacyLevel.NONE, NotificationPrivacyLevel.fromStoredName(""))
    }

    @Test
    fun `resolve keeps a stored level`() {
        NotificationPrivacyLevel.entries.forEach { level ->
            assertEquals(level, NotificationPrivacyLevel.resolve(level.name, corruptionRecovered = false))
            assertEquals(level, NotificationPrivacyLevel.resolve(level.name, corruptionRecovered = true))
        }
    }

    @Test
    fun `resolve defaults to FULL only when nothing was ever stored`() {
        assertEquals(NotificationPrivacyLevel.FULL, NotificationPrivacyLevel.resolve(null, corruptionRecovered = false))
    }

    @Test
    fun `resolve fails closed after corruption recovery`() {
        // The stored choice was lost with the corrupt file; message text must stay hidden.
        assertEquals(NotificationPrivacyLevel.NONE, NotificationPrivacyLevel.resolve(null, corruptionRecovered = true))
    }

    @Test
    fun `restrictiveness follows declaration order`() {
        assertTrue(NotificationPrivacyLevel.NAME_ONLY.isMoreRestrictiveThan(NotificationPrivacyLevel.FULL))
        assertTrue(NotificationPrivacyLevel.NONE.isMoreRestrictiveThan(NotificationPrivacyLevel.FULL))
        assertTrue(NotificationPrivacyLevel.NONE.isMoreRestrictiveThan(NotificationPrivacyLevel.NAME_ONLY))
        assertFalse(NotificationPrivacyLevel.FULL.isMoreRestrictiveThan(NotificationPrivacyLevel.NAME_ONLY))
        assertFalse(NotificationPrivacyLevel.NAME_ONLY.isMoreRestrictiveThan(NotificationPrivacyLevel.NAME_ONLY))
    }
}
