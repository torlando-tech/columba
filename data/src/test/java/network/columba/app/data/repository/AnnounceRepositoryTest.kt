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
            val filteredPage =
                database
                    .announceDao()
                    .getEnrichedAnnouncesPaged(
                        interfaceTypes = listOf(InterfaceType.RNODE.storageName),
                        interfaceTypeCount = 1,
                        interfaceCutoff = now - AnnounceRepository.RECENT_INTERFACE_WINDOW_MS,
                    ).load(PagingSource.LoadParams.Refresh(key = null, loadSize = 1, placeholdersEnabled = false))
            val filteredRows =
                (filteredPage as PagingSource.LoadResult.Page<Int, EnrichedAnnounce>).data
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

            val filteredPage =
                dao.getEnrichedAnnouncesPaged(
                    interfaceTypes = listOf(InterfaceType.RNODE.storageName),
                    interfaceTypeCount = 1,
                    interfaceCutoff = System.currentTimeMillis() - AnnounceRepository.RECENT_INTERFACE_WINDOW_MS,
                ).load(PagingSource.LoadParams.Refresh(key = null, loadSize = 1, placeholdersEnabled = false))
            val filteredRows =
                (filteredPage as PagingSource.LoadResult.Page<Int, EnrichedAnnounce>).data
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

            val filteredPage =
                dao.getEnrichedAnnouncesPaged(
                    interfaceTypes = listOf(InterfaceType.BLE.storageName),
                    interfaceTypeCount = 1,
                    interfaceCutoff = System.currentTimeMillis() - AnnounceRepository.RECENT_INTERFACE_WINDOW_MS,
                ).load(PagingSource.LoadParams.Refresh(key = null, loadSize = 1, placeholdersEnabled = false))
            val filteredRows =
                (filteredPage as PagingSource.LoadResult.Page<Int, EnrichedAnnounce>).data
            assertEquals(listOf(destinationHash), filteredRows.map { it.destinationHash })
        }

    private suspend fun saveTestAnnounce(
        destinationHash: String,
        receivingInterface: String,
        timestamp: Long,
        hops: Int,
    ) {
        repository.saveAnnounce(
            destinationHash = destinationHash,
            peerName = "Test peer",
            publicKey = ByteArray(32) { it.toByte() },
            appData = null,
            hops = hops,
            timestamp = timestamp,
            nodeType = "PEER",
            receivingInterface = receivingInterface,
            receivingInterfaceType = InterfaceType.fromName(receivingInterface).storageName,
            aspect = "lxmf.delivery",
        )
    }
}
