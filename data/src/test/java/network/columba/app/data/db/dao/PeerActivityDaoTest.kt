package network.columba.app.data.db.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.AnnounceEntity
import network.columba.app.data.db.entity.ContactEntity
import network.columba.app.data.db.entity.ConversationEntity
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.data.db.entity.PeerActivityType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PeerActivityDaoTest {
    private lateinit var database: ColumbaDatabase
    private lateinit var dao: PeerActivityDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.peerActivityDao()
    }

    @After
    fun teardown() = database.close()

    @Test
    fun `recordActivity is monotonic and preserves newest source`() = runTest {
        dao.recordActivity("ABCDEF", 200L, PeerActivityType.MESSAGE)
        dao.recordActivity("abcdef", 100L, PeerActivityType.ANNOUNCE)
        dao.recordActivity("abcdef", 200L, PeerActivityType.PROOF)

        assertEquals(200L, dao.getActivity("ABCDEF")?.lastReceivedAt)
        assertEquals(PeerActivityType.MESSAGE, dao.getActivity("abcdef")?.activityType)

        dao.recordActivity("abcdef", 300L, PeerActivityType.PROOF)
        assertEquals(300L, dao.getActivity("abcdef")?.lastReceivedAt)
        assertEquals(PeerActivityType.PROOF, dao.getActivity("abcdef")?.activityType)
    }

    @Test
    fun `observeActivity emits persisted updates`() = runTest {
        dao.observeActivity("peer").test {
            assertNull(awaitItem())
            dao.recordActivity("PEER", 42L, PeerActivityType.TELEMETRY)
            assertEquals(42L, awaitItem()?.lastReceivedAt)
        }
    }

    @Test
    fun `recordActivityOnce rejects replayed protocol event`() = runTest {
        assertEquals(true, dao.recordActivityOnce("message:ABC", "peer", 100L, PeerActivityType.MESSAGE))
        assertEquals(false, dao.recordActivityOnce("message:abc", "peer", 200L, PeerActivityType.MESSAGE))
        assertEquals(100L, dao.getActivity("peer")?.lastReceivedAt)
    }

    @Test
    fun `activity survives database restart`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "peer-activity-restart-test"
        context.deleteDatabase(name)
        var persistentDb = Room.databaseBuilder(context, ColumbaDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        persistentDb.peerActivityDao().recordActivity("peer", 77L, PeerActivityType.MESSAGE)
        persistentDb.close()

        persistentDb = Room.databaseBuilder(context, ColumbaDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        assertEquals(77L, persistentDb.peerActivityDao().getActivity("peer")?.lastReceivedAt)
        persistentDb.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `enriched contact uses peer activity rather than outgoing conversation time`() = runTest {
        val identityHash = "owner"
        val peerHash = "peer"
        database.localIdentityDao().insert(
            LocalIdentityEntity(
                identityHash = identityHash,
                displayName = "Owner",
                destinationHash = "owner-destination",
                filePath = "/test",
                createdTimestamp = 1L,
                lastUsedTimestamp = 1L,
                isActive = true,
            ),
        )
        database.contactDao().insertContact(
            ContactEntity(
                destinationHash = peerHash,
                identityHash = identityHash,
                publicKey = ByteArray(32),
                addedTimestamp = 1L,
                addedVia = "MANUAL",
            ),
        )
        database.announceDao().upsertAnnounce(
            AnnounceEntity(
                destinationHash = peerHash,
                peerName = "Peer",
                publicKey = ByteArray(32),
                appData = null,
                hops = 1,
                lastSeenTimestamp = 10L,
                nodeType = "PEER",
                receivingInterface = null,
            ),
        )
        database.conversationDao().insertConversation(
            ConversationEntity(
                peerHash = peerHash,
                identityHash = identityHash,
                peerName = "Peer",
                lastMessage = "undelivered outgoing message",
                lastMessageTimestamp = 999L,
            ),
        )
        database.messageDao().insertMessage(
            MessageEntity(
                id = "outgoing",
                conversationHash = peerHash,
                identityHash = identityHash,
                content = "undelivered",
                timestamp = 999L,
                isFromMe = true,
                status = "failed",
                isRead = true,
            ),
        )
        dao.recordActivity(peerHash, 25L, PeerActivityType.TELEMETRY)

        database.contactDao().getEnrichedContacts(identityHash).test {
            val activityContact = awaitItem().single()
            assertEquals(25L, activityContact.lastSeenTimestamp)
            assertEquals(false, activityContact.isOnline)
            database.announceDao().upsertAnnounce(
                AnnounceEntity(
                    destinationHash = peerHash,
                    peerName = "Peer",
                    publicKey = ByteArray(32),
                    appData = null,
                    hops = 1,
                    lastSeenTimestamp = 30L,
                    nodeType = "PEER",
                    receivingInterface = null,
                ),
            )
            assertEquals(30L, awaitItem().single().lastSeenTimestamp)
            database.announceDao().upsertAnnounce(
                AnnounceEntity(
                    destinationHash = peerHash,
                    peerName = "Peer",
                    publicKey = ByteArray(32),
                    appData = null,
                    hops = 1,
                    lastSeenTimestamp = System.currentTimeMillis() + 24 * 60 * 60 * 1000L,
                    nodeType = "PEER",
                    receivingInterface = null,
                ),
            )
            assertEquals(25L, awaitItem().single().lastSeenTimestamp)
        }
    }
}
