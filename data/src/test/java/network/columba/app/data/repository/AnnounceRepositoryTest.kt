package network.columba.app.data.repository

import android.app.Application
import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.AnnounceEntity
import network.columba.app.data.db.entity.ContactEntity
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.model.EnrichedAnnounce
import network.columba.app.data.model.InterfaceType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnnounceRepositoryTest {
    private lateinit var database: ColumbaDatabase
    private lateinit var repository: AnnounceRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = AnnounceRepository(database.announceDao())
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun saveAnnounce_preservesRecentInterfaceTypesWhenCurrentPathChanges() =
        runTest {
            val destinationHash = "0123456789abcdef0123456789abcdef"
            val now = System.currentTimeMillis()

            saveTestAnnounce(
                destinationHash = destinationHash,
                receivingInterface = "RNodeInterface[Radio]",
                timestamp = now,
                hops = 2,
            )
            saveTestAnnounce(
                destinationHash = destinationHash,
                receivingInterface = "TCPClientInterface[Backbone]",
                timestamp = now + 1,
                hops = 1,
            )

            val updated = requireNotNull(repository.getAnnounceFlow(destinationHash).first())
            assertEquals(1, database.announceDao().getAnnounceCount())
            assertEquals("TCPClientInterface[Backbone]", updated.receivingInterface)
            assertEquals(
                setOf(InterfaceType.RNODE, InterfaceType.TCP_CLIENT),
                updated.recentInterfaceTypes,
            )

            val sightings = repository.getRecentInterfaceSightings(destinationHash).first()
            assertEquals(2, sightings.size)
            assertTrue(sightings.any { it.interfaceType == InterfaceType.RNODE && it.hops == 2 })
            assertTrue(sightings.any { it.interfaceType == InterfaceType.TCP_CLIENT && it.hops == 1 })

            // A newer non-matching row must not consume the first Paging slot and
            // produce a false-empty RNode page.
            saveTestAnnounce(
                destinationHash = "ffffffffffffffffffffffffffffffff",
                receivingInterface = "AndroidBLE[Nearby phone]",
                timestamp = now + 2_000,
                hops = 1,
            )
            val filteredRows =
                loadAnnouncePage(
                    interfaceTypes = listOf(InterfaceType.RNODE.storageName),
                    loadSize = 1,
                ).data
            assertEquals(listOf(destinationHash), filteredRows.map { it.destinationHash })
        }

    @Test
    fun olderOrEqualObservation_cannotRegressCurrentPathOrSightingMetadata() =
        runTest {
            val destinationHash = "00112233445566778899aabbccddeeff"
            val newest = System.currentTimeMillis()
            saveTestAnnounce(destinationHash, "RNodeInterface[Newest]", newest, hops = 1)
            saveTestAnnounce(destinationHash, "RNodeInterface[Older]", newest - 1, hops = 5)
            saveTestAnnounce(destinationHash, "RNodeInterface[Equal]", newest, hops = 4)

            val current = requireNotNull(repository.getAnnounceFlow(destinationHash).first())
            assertEquals("RNodeInterface[Newest]", current.receivingInterface)
            assertEquals(1, current.hops)

            val sighting = repository.getRecentInterfaceSightings(destinationHash).first().single()
            assertEquals("RNodeInterface[Newest]", sighting.receivingInterface)
            assertEquals(newest, sighting.lastSeenTimestamp)
            assertEquals(1, sighting.hops)
        }

    @Test
    fun staleSighting_isExcludedFromHistoryButFavoriteMatchesItsCurrentInterface() =
        runTest {
            val destinationHash = "fedcba9876543210fedcba9876543210"
            val staleTimestamp =
                System.currentTimeMillis() - AnnounceRepository.RECENT_INTERFACE_WINDOW_MS - 1

            saveTestAnnounce(
                destinationHash = destinationHash,
                receivingInterface = "RNodeInterface[Old Radio]",
                timestamp = staleTimestamp,
                hops = 3,
            )

            assertTrue(repository.getRecentInterfaceSightings(destinationHash).first().isEmpty())

            val dao = database.announceDao()
            dao.updateFavoriteStatus(destinationHash, isFavorite = true, timestamp = System.currentTimeMillis())
            dao.deleteStaleInterfaceSightings(System.currentTimeMillis() - AnnounceRepository.RECENT_INTERFACE_WINDOW_MS)

            val filteredRows =
                loadAnnouncePage(
                    interfaceTypes = listOf(InterfaceType.RNODE.storageName),
                    loadSize = 1,
                ).data
            assertEquals(listOf(destinationHash), filteredRows.map { it.destinationHash })
        }

    @Test
    fun importedFavorite_canonicalizesParentBeforeSightingExpires() =
        runTest {
            val destinationHash = "abababababababababababababababab"
            val staleTimestamp =
                System.currentTimeMillis() - AnnounceRepository.RECENT_INTERFACE_WINDOW_MS - 1
            val dao = database.announceDao()
            dao.insertAnnounces(
                listOf(
                    AnnounceEntity(
                        destinationHash = destinationHash,
                        peerName = "Imported peer",
                        publicKey = ByteArray(32) { it.toByte() },
                        appData = null,
                        hops = 2,
                        lastSeenTimestamp = staleTimestamp,
                        nodeType = "PEER",
                        receivingInterface = "AndroidBLE[Imported phone]",
                        receivingInterfaceType = "ANDROID_BLE",
                        isFavorite = true,
                        favoritedTimestamp = System.currentTimeMillis(),
                    ),
                ),
            )

            assertEquals(InterfaceType.BLE.storageName, dao.getAnnounce(destinationHash)?.receivingInterfaceType)
            dao.deleteStaleInterfaceSightings(System.currentTimeMillis() - AnnounceRepository.RECENT_INTERFACE_WINDOW_MS)

            val filteredRows =
                loadAnnouncePage(
                    interfaceTypes = listOf(InterfaceType.BLE.storageName),
                    loadSize = 1,
                ).data
            assertEquals(listOf(destinationHash), filteredRows.map { it.destinationHash })
        }

    @Test
    fun expiredHistoricalInterface_doesNotMatchCurrentPagingWindow() =
        runTest {
            val destinationHash = "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"
            val now = System.currentTimeMillis()
            saveTestAnnounce(
                destinationHash,
                "RNodeInterface[Expired radio]",
                now - AnnounceRepository.RECENT_INTERFACE_WINDOW_MS - 1_000,
                hops = 3,
            )
            saveTestAnnounce(destinationHash, "TCPClientInterface[Current path]", now, hops = 1)

            val rows =
                loadAnnouncePage(
                    interfaceTypes = listOf(InterfaceType.RNODE.storageName),
                    loadSize = 1,
                ).data
            assertTrue(rows.isEmpty())
        }

    @Test
    fun getSavedPropagationNodes_returnsOnlySavedContactsOfThatIdentity() =
        runTest {
            val dao = database.announceDao()
            val contacts = database.contactDao()
            val identities = database.localIdentityDao()
            val now = System.currentTimeMillis()
            // Seed the active identity so the contact FK (identityHash -> local_identities) holds.
            identities.insert(
                LocalIdentityEntity(
                    identityHash = "id1",
                    displayName = "main",
                    destinationHash = "mydest",
                    filePath = "/keys/main.key",
                    createdTimestamp = now,
                    lastUsedTimestamp = now,
                    isActive = true,
                )
            )
            // Two propagation-node announces: one saved by the active identity, one not.
            dao.upsertAnnounce(
                AnnounceEntity(
                    destinationHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    peerName = "Saved relay",
                    publicKey = ByteArray(32) { 0x01 },
                    appData = null,
                    hops = 1,
                    lastSeenTimestamp = now,
                    nodeType = "PROPAGATION_NODE",
                    receivingInterface = "RNodeInterface[Radio]",
                )
            )
            dao.upsertAnnounce(
                AnnounceEntity(
                    destinationHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    peerName = "Unserved relay",
                    publicKey = ByteArray(32) { 0x02 },
                    appData = null,
                    hops = 2,
                    lastSeenTimestamp = now,
                    nodeType = "PROPAGATION_NODE",
                    receivingInterface = "RNodeInterface[Radio]",
                )
            )
            // Save only the first as a contact for identity "id1".
            contacts.insertContact(
                ContactEntity(
                    destinationHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    identityHash = "id1",
                    publicKey = ByteArray(32) { 0x01 },
                    addedTimestamp = now,
                    addedVia = "ANNOUNCE",
                )
            )

            val saved = repository.getSavedPropagationNodes("id1").first()

            assertEquals(1, saved.size)
            assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", saved[0].destinationHash)
            assertEquals("Saved relay", saved[0].peerName)
        }

    @Test
    fun keysetPaging_keepsLoadedRowsStableWhenNewerRowArrives() =
        runTest {
            val now = System.currentTimeMillis()
            repeat(4) { i ->
                saveTestAnnounce("peer$i".hash32(), "TCPClientInterface[A]", now - i, hops = 1)
            }

            val firstPage = loadAnnouncePage(nodeTypes = listOf("PEER"), loadSize = 2)
            assertEquals(
                listOf("peer0".hash32(), "peer1".hash32()),
                firstPage.data.map { it.destinationHash },
            )

            // A new announce lands at the top of the stream while the user is on page 1.
            saveTestAnnounce("peer9".hash32(), "TCPClientInterface[A]", now + 10_000, hops = 1)

            val nextPage =
                loadAnnouncePage(
                    nodeTypes = listOf("PEER"),
                    loadSize = 2,
                    cursor = firstPage.nextKey,
                )
            assertEquals(
                listOf("peer2".hash32(), "peer3".hash32()),
                nextPage.data.map { it.destinationHash },
            )
        }

    @Test
    fun keysetPaging_prependReturnsNewerRowsInListOrder() =
        runTest {
            val now = System.currentTimeMillis()
            repeat(4) { i ->
                saveTestAnnounce("peer$i".hash32(), "TCPClientInterface[A]", now - i, hops = 1)
            }
            val firstPage = loadAnnouncePage(nodeTypes = listOf("PEER"), loadSize = 2)
            assertEquals("The refresh page is the top of the list", null, firstPage.prevKey)

            // Three newer rows land while the user is reading an append page.
            saveTestAnnounce("peer7".hash32(), "TCPClientInterface[A]", now + 10_000, hops = 1)
            saveTestAnnounce("peer8".hash32(), "TCPClientInterface[A]", now + 20_000, hops = 1)
            saveTestAnnounce("peer9".hash32(), "TCPClientInterface[A]", now + 30_000, hops = 1)

            val topRow = firstPage.data.first()
            val topCursor = AnnounceCursor(topRow.lastSeenTimestamp, topRow.destinationHash)
            val prepended =
                loadAnnouncePage(
                    nodeTypes = listOf("PEER"),
                    loadSize = 2,
                    cursor = topCursor,
                    isAppend = false,
                )
            // The LIMIT must keep the rows nearest the cursor, not the newest overall,
            // and they come back in list order.
            assertEquals(
                listOf("peer8".hash32(), "peer7".hash32()),
                prepended.data.map { it.destinationHash },
            )

            val olderPrepend =
                loadAnnouncePage(
                    nodeTypes = listOf("PEER"),
                    loadSize = 2,
                    cursor = requireNotNull(prepended.prevKey),
                    isAppend = false,
                )
            assertEquals(listOf("peer9".hash32()), olderPrepend.data.map { it.destinationHash })
            assertEquals(null, olderPrepend.prevKey)
        }

    @Test
    fun newArrivalQueries_respectNodeTypeFilterAndHighWaterMark() =
        runTest {
            val now = System.currentTimeMillis()
            saveTestAnnounce("peera".hash32(), "TCPClientInterface[A]", now, hops = 1)
            saveTestAnnounce("nodea".hash32(), "TCPClientInterface[A]", now + 1, hops = 1, nodeType = "NODE")

            val mark =
                requireNotNull(
                    repository.getNewestAnnounceTimestamp(
                        nodeTypes = listOf("PEER"),
                        searchQuery = "",
                        interfaceTypes = emptyList(),
                    ),
                )
            assertEquals("PEER-only mark ignores the newer NODE row", now, mark)

            saveTestAnnounce("peerz".hash32(), "TCPClientInterface[A]", now + 2, hops = 1)
            saveTestAnnounce("nodez".hash32(), "TCPClientInterface[A]", now + 3, hops = 1, nodeType = "NODE")

            val count =
                repository
                    .observeAnnounceCountNewerThan(
                        since = mark,
                        nodeTypes = listOf("PEER"),
                        searchQuery = "",
                        interfaceTypes = emptyList(),
                    ).first()
            assertEquals("Only the newer PEER row counts", 1, count)
        }

    private fun String.hash32(): String = "%032x".format(java.util.zip.CRC32().apply { update(toByteArray()) }.value)

    private suspend fun loadAnnouncePage(
        interfaceTypes: List<String> = emptyList(),
        nodeTypes: List<String> = emptyList(),
        searchQuery: String = "",
        loadSize: Int = 30,
        cursor: AnnounceCursor? = null,
        isAppend: Boolean = true,
    ): PagingSource.LoadResult.Page<AnnounceCursor, EnrichedAnnounce> =
        AnnouncePagingSource(
            announceDao = database.announceDao(),
            nodeTypes = nodeTypes,
            searchQuery = searchQuery,
            interfaceTypes = interfaceTypes,
        ).load(
            when {
                cursor == null ->
                    PagingSource.LoadParams.Refresh(
                        key = null,
                        loadSize = loadSize,
                        placeholdersEnabled = false,
                    )
                isAppend ->
                    PagingSource.LoadParams.Append(
                        key = cursor,
                        loadSize = loadSize,
                        placeholdersEnabled = false,
                    )
                else ->
                    PagingSource.LoadParams.Prepend(
                        key = cursor,
                        loadSize = loadSize,
                        placeholdersEnabled = false,
                    )
            },
        ) as PagingSource.LoadResult.Page

    private suspend fun saveTestAnnounce(
        destinationHash: String,
        receivingInterface: String,
        timestamp: Long,
        hops: Int,
        nodeType: String = "PEER",
    ) {
        repository.saveAnnounce(
            destinationHash = destinationHash,
            peerName = "Test peer",
            publicKey = ByteArray(32) { it.toByte() },
            appData = null,
            hops = hops,
            timestamp = timestamp,
            nodeType = nodeType,
            receivingInterface = receivingInterface,
            receivingInterfaceType = InterfaceType.fromName(receivingInterface).storageName,
            aspect = "lxmf.delivery",
        )
    }
}
