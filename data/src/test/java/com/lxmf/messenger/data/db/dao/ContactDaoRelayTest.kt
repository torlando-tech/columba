package com.lxmf.messenger.data.db.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.db.entity.ContactEntity
import com.lxmf.messenger.data.db.entity.ContactStatus
import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Instrumented tests for ContactDao relay functionality.
 *
 * Design: Relay status (isMyRelay) and pinned status (isPinned) are INDEPENDENT.
 * - Relay contacts appear in their own "My Relay" section in the UI
 * - Pinned contacts appear in the "Pinned" section
 * - A contact can be both a relay AND pinned (appears in both sections)
 * - setAsMyRelay() only sets isMyRelay, does NOT touch isPinned
 * - clearMyRelay() only clears isMyRelay, does NOT touch isPinned
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContactDaoRelayTest {
    private lateinit var database: ColumbaDatabase
    private lateinit var contactDao: ContactDao
    private lateinit var localIdentityDao: LocalIdentityDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room.inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        contactDao = database.contactDao()
        localIdentityDao = database.localIdentityDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ========== Helper Functions ==========

    private fun createTestIdentity(
        hash: String = "test_identity_hash",
        displayName: String = "Test Identity",
    ) = LocalIdentityEntity(
        identityHash = hash,
        displayName = displayName,
        destinationHash = "dest_$hash",
        filePath = "/data/identity_$hash",
        createdTimestamp = System.currentTimeMillis(),
        lastUsedTimestamp = System.currentTimeMillis(),
        isActive = true,
    )

    private fun createTestContact(
        destinationHash: String,
        identityHash: String,
        isPinned: Boolean = false,
        isMyRelay: Boolean = false,
    ) = ContactEntity(
        destinationHash = destinationHash,
        identityHash = identityHash,
        publicKey = ByteArray(32) { it.toByte() },
        customNickname = "Test Contact",
        addedTimestamp = System.currentTimeMillis(),
        addedVia = "TEST",
        isPinned = isPinned,
        isMyRelay = isMyRelay,
        status = ContactStatus.ACTIVE,
    )

    // ========== setAsMyRelay Tests ==========

    @Test
    fun setAsMyRelay_setsIsMyRelayTrue() =
        runTest {
            // Given: Create identity and contact
            val identity = createTestIdentity(hash = "identity_1")
            localIdentityDao.insert(identity)

            val contact =
                createTestContact(
                    destinationHash = "relay_dest_hash",
                    identityHash = identity.identityHash,
                    isPinned = false,
                    isMyRelay = false,
                )
            contactDao.insertContact(contact)

            // When: Set as relay
            contactDao.setAsMyRelay("relay_dest_hash", identity.identityHash)

            // Then: isMyRelay should be true, isPinned should remain false (independent)
            val updated = contactDao.getContact("relay_dest_hash", identity.identityHash)
            assertNotNull("Updated contact should exist", updated)
            assertTrue("isMyRelay should be true after setAsMyRelay", updated!!.isMyRelay)
            assertFalse("isPinned should remain false (relay and pinned are independent)", updated.isPinned)
        }

    @Test
    fun setAsMyRelay_doesNotAffectIsPinned() =
        runTest {
            // Given: Create identity and already-pinned contact
            val identity = createTestIdentity(hash = "identity_2")
            localIdentityDao.insert(identity)

            val contact =
                createTestContact(
                    destinationHash = "relay_dest_hash_2",
                    identityHash = identity.identityHash,
                    isPinned = true, // Already pinned
                    isMyRelay = false,
                )
            contactDao.insertContact(contact)

            // When: Set as relay
            contactDao.setAsMyRelay("relay_dest_hash_2", identity.identityHash)

            // Then: isPinned should remain unchanged (true)
            val updated = contactDao.getContact("relay_dest_hash_2", identity.identityHash)
            assertNotNull(updated)
            assertTrue("isMyRelay should be true", updated!!.isMyRelay)
            assertTrue("isPinned should remain true (not affected by relay status)", updated.isPinned)
        }

    // ========== clearMyRelay Tests ==========

    @Test
    fun clearMyRelay_clearsIsMyRelayOnly() =
        runTest {
            // Given: Create identity and relay contact that's also pinned
            val identity = createTestIdentity(hash = "identity_3")
            localIdentityDao.insert(identity)

            val relayContact =
                createTestContact(
                    destinationHash = "old_relay_hash",
                    identityHash = identity.identityHash,
                    isPinned = true, // Also pinned
                    isMyRelay = true,
                )
            contactDao.insertContact(relayContact)

            // When: Clear relay
            contactDao.clearMyRelay(identity.identityHash)

            // Then: isMyRelay should be false, isPinned should remain true
            val updated = contactDao.getContact("old_relay_hash", identity.identityHash)
            assertNotNull(updated)
            assertFalse("isMyRelay should be false after clearMyRelay", updated!!.isMyRelay)
            assertTrue("isPinned should remain true (not affected by relay status)", updated.isPinned)
        }

    @Test
    fun clearMyRelay_onlyAffectsRelayContacts() =
        runTest {
            // Given: Create identity with a relay and a non-relay contact
            val identity = createTestIdentity(hash = "identity_4")
            localIdentityDao.insert(identity)

            val relayContact =
                createTestContact(
                    destinationHash = "relay_hash",
                    identityHash = identity.identityHash,
                    isPinned = false,
                    isMyRelay = true,
                )
            val regularContact =
                createTestContact(
                    destinationHash = "regular_hash",
                    identityHash = identity.identityHash,
                    isPinned = true,
                    isMyRelay = false,
                )
            contactDao.insertContact(relayContact)
            contactDao.insertContact(regularContact)

            // When: Clear relay
            contactDao.clearMyRelay(identity.identityHash)

            // Then: Only relay contact should have isMyRelay cleared
            val updatedRelay = contactDao.getContact("relay_hash", identity.identityHash)
            val updatedRegular = contactDao.getContact("regular_hash", identity.identityHash)

            assertFalse("Relay isMyRelay should be false", updatedRelay!!.isMyRelay)
            assertFalse("Regular contact isMyRelay should still be false", updatedRegular!!.isMyRelay)
            assertTrue("Regular contact isPinned should remain true", updatedRegular.isPinned)
        }

    // ========== Full Relay Switch Flow Test ==========

    @Test
    fun switchingRelays_onlyAffectsIsMyRelay() =
        runTest {
            // Given: Create identity and two contacts
            val identity = createTestIdentity(hash = "identity_5")
            localIdentityDao.insert(identity)

            val oldRelay =
                createTestContact(
                    destinationHash = "old_relay",
                    identityHash = identity.identityHash,
                    isPinned = true, // Also pinned
                    isMyRelay = true,
                )
            val newRelay =
                createTestContact(
                    destinationHash = "new_relay",
                    identityHash = identity.identityHash,
                    isPinned = false,
                    isMyRelay = false,
                )
            contactDao.insertContact(oldRelay)
            contactDao.insertContact(newRelay)

            // When: Switch relays (clear old, set new)
            contactDao.clearMyRelay(identity.identityHash)
            contactDao.setAsMyRelay("new_relay", identity.identityHash)

            // Then: Relay flags switched, pinned status unchanged
            val oldUpdated = contactDao.getContact("old_relay", identity.identityHash)
            val newUpdated = contactDao.getContact("new_relay", identity.identityHash)

            assertFalse("Old relay isMyRelay should be false", oldUpdated!!.isMyRelay)
            assertTrue("Old relay isPinned should remain true", oldUpdated.isPinned)

            assertTrue("New relay isMyRelay should be true", newUpdated!!.isMyRelay)
            assertFalse("New relay isPinned should remain false", newUpdated.isPinned)
        }

    @Test
    fun getMyRelay_returnsRelayContact() =
        runTest {
            // Given: Create identity and set a relay
            val identity = createTestIdentity(hash = "identity_6")
            localIdentityDao.insert(identity)

            val relayContact =
                createTestContact(
                    destinationHash = "my_relay",
                    identityHash = identity.identityHash,
                    isPinned = false,
                    isMyRelay = false,
                )
            contactDao.insertContact(relayContact)
            contactDao.setAsMyRelay("my_relay", identity.identityHash)

            // When: Get relay
            val relay = contactDao.getMyRelay(identity.identityHash)

            // Then: Should return the relay contact
            assertNotNull(relay)
            assertEquals("my_relay", relay!!.destinationHash)
            assertTrue("isMyRelay should be true", relay.isMyRelay)
        }
}
