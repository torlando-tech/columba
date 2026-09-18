package network.columba.app.migration

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import network.columba.app.data.crypto.IdentityKeyEncryptor
import network.columba.app.data.crypto.IdentityKeyProvider
import network.columba.app.data.database.InterfaceDatabase
import network.columba.app.data.database.dao.InterfaceDao
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.CallHistoryEntity
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.repository.SettingsRepository
import network.columba.app.service.PropagationNodeManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Suppress("NoRelaxedMocks") // External migration collaborators are irrelevant to the Room round-trip assertions.
class MigrationCallHistoryRoundTripTest {
    private lateinit var context: Context
    private lateinit var database: ColumbaDatabase
    private lateinit var importer: MigrationImporter

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java).allowMainThreadQueries().build()
        val interfaceDao = mockk<InterfaceDao>(relaxed = true)
        every { interfaceDao.getAllInterfaces() } returns flowOf(emptyList())
        val interfaceDatabase = mockk<InterfaceDatabase>(relaxed = true)
        every { interfaceDatabase.interfaceDao() } returns interfaceDao
        importer =
            MigrationImporter(
                context = context,
                database = database,
                interfaceDatabase = interfaceDatabase,
                settingsRepository = mockk<SettingsRepository>(relaxed = true),
                propagationNodeManager = mockk<PropagationNodeManager>(relaxed = true),
                keyEncryptor = mockk<IdentityKeyEncryptor>(relaxed = true),
            )
        runTest { database.localIdentityDao().insert(identity()) }
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `encrypted production export round trips every reduced outcome with legacy key protection`() =
        runTest {
            assertProductionExportRoundTrip(exportPassword = null)
        }

    @Test
    fun `encrypted production export round trips every reduced outcome with password key protection`() =
        runTest {
            assertProductionExportRoundTrip(exportPassword = "identity-password".toCharArray())
        }

    private suspend fun assertProductionExportRoundTrip(exportPassword: CharArray?) {
        val source = Room.inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java).allowMainThreadQueries().build()
        try {
            source.localIdentityDao().insert(identity().copy(keyData = ByteArray(64) { it.toByte() }))
            // Every reduced outcome: outgoing
            insertCall(source, "out-connected-ended", connectedAt = 120L, outcome = "CONNECTED_ENDED")
            insertCall(source, "out-rejected", connectedAt = null, outcome = "REJECTED_REMOTE")
            insertCall(source, "out-busy", connectedAt = null, outcome = "BUSY_REMOTE")
            insertCall(source, "out-cancelled", connectedAt = null, outcome = "CANCELLED_LOCAL")
            insertCall(source, "out-not-connected", connectedAt = null, outcome = "NOT_CONNECTED")
            insertCall(source, "out-failed", connectedAt = null, outcome = "FAILED", failureReason = "NETWORK_UNAVAILABLE")
            insertCall(source, "out-interrupted", connectedAt = 120L, outcome = "INTERRUPTED", inferredEnding = true)
            // Every reduced outcome: incoming
            insertCall(source, "in-connected-ended", connectedAt = 120L, direction = "INCOMING", outcome = "CONNECTED_ENDED")
            insertCall(source, "in-missed", connectedAt = null, direction = "INCOMING", outcome = "MISSED_INCOMING")
            insertCall(source, "in-declined", connectedAt = null, direction = "INCOMING", outcome = "DECLINED_LOCAL")
            insertCall(source, "in-failed", connectedAt = null, direction = "INCOMING", outcome = "FAILED", failureReason = "MICROPHONE_PERMISSION_DENIED")
            insertCall(source, "in-interrupted", connectedAt = 120L, direction = "INCOMING", outcome = "INTERRUPTED", inferredEnding = true)

            val interfaceDao = mockk<InterfaceDao>(relaxed = true)
            every { interfaceDao.getAllInterfaces() } returns flowOf(emptyList())
            val interfaceDatabase = mockk<InterfaceDatabase>(relaxed = true)
            every { interfaceDatabase.interfaceDao() } returns interfaceDao
            val settings = mockk<SettingsRepository>(relaxed = true)
            coEvery { settings.exportAllPreferences() } returns emptyList()
            val keyEncryptor = mockk<IdentityKeyEncryptor>(relaxed = true)
            every { keyEncryptor.encryptForExport(any(), any()) } returns ByteArray(96) { 7 }
            val exporter =
                MigrationExporter(
                    context,
                    source,
                    interfaceDatabase,
                    settings,
                    keyEncryptor,
                    mockk<IdentityKeyProvider>(relaxed = true),
                )

            val exportDirectory = File(context.cacheDir, "migration_export").apply {
                listFiles()?.forEach(File::delete)
            }
            exporter.exportData(
                password = "archive-password",
                includeAttachments = false,
                exportPassword = exportPassword,
            )
            val encryptedExport = exportDirectory.listFiles()?.singleOrNull()
                ?: error("Production exporter did not create exactly one archive")
            val result =
                requireSuccess(
                    importer.importData(
                        writeTempFile(encryptedExport.readBytes()),
                        password = "archive-password",
                        importPassword = exportPassword,
                    ),
                )

            assertEquals(12, result.callHistoryImported)
            assertEquals("CONNECTED_ENDED", database.callHistoryDao().getByAttemptId("out-connected-ended")?.outcome)
            assertEquals("REJECTED_REMOTE", database.callHistoryDao().getByAttemptId("out-rejected")?.outcome)
            assertEquals("BUSY_REMOTE", database.callHistoryDao().getByAttemptId("out-busy")?.outcome)
            assertEquals("CANCELLED_LOCAL", database.callHistoryDao().getByAttemptId("out-cancelled")?.outcome)
            assertEquals("NOT_CONNECTED", database.callHistoryDao().getByAttemptId("out-not-connected")?.outcome)
            assertEquals("FAILED", database.callHistoryDao().getByAttemptId("out-failed")?.outcome)
            assertEquals("NETWORK_UNAVAILABLE", database.callHistoryDao().getByAttemptId("out-failed")?.failureReason)
            assertEquals("INTERRUPTED", database.callHistoryDao().getByAttemptId("out-interrupted")?.outcome)
            assertEquals(true, database.callHistoryDao().getByAttemptId("out-interrupted")?.inferredEnding)
            assertEquals("MISSED_INCOMING", database.callHistoryDao().getByAttemptId("in-missed")?.outcome)
            assertEquals("DECLINED_LOCAL", database.callHistoryDao().getByAttemptId("in-declined")?.outcome)
            assertEquals("FAILED", database.callHistoryDao().getByAttemptId("in-failed")?.outcome)
            assertEquals("MICROPHONE_PERMISSION_DENIED", database.callHistoryDao().getByAttemptId("in-failed")?.failureReason)
            assertEquals("INTERRUPTED", database.callHistoryDao().getByAttemptId("in-interrupted")?.outcome)
        } finally {
            source.close()
        }
    }

    @Test
    fun `deletion authority round trips and suppresses later re-import`() =
        runTest {
            val source = Room.inMemoryDatabaseBuilder(context, ColumbaDatabase::class.java).allowMainThreadQueries().build()
            try {
                source.localIdentityDao().insert(identity().copy(keyData = ByteArray(64) { it.toByte() }))
                insertCall(source, "deleted-call", connectedAt = 120L, outcome = "CONNECTED_ENDED")
                source.callHistoryDeletionDao().deleteFinalized("deleted-call", LOCAL_IDENTITY, 900L)

                val interfaceDao = mockk<InterfaceDao>(relaxed = true)
                every { interfaceDao.getAllInterfaces() } returns flowOf(emptyList())
                val interfaceDatabase = mockk<InterfaceDatabase>(relaxed = true)
                every { interfaceDatabase.interfaceDao() } returns interfaceDao
                val settings = mockk<SettingsRepository>(relaxed = true)
                coEvery { settings.exportAllPreferences() } returns emptyList()
                val keyEncryptor = mockk<IdentityKeyEncryptor>(relaxed = true)
                every { keyEncryptor.encryptForExport(any(), any()) } returns ByteArray(96) { 7 }
                val exporter =
                    MigrationExporter(
                        context,
                        source,
                        interfaceDatabase,
                        settings,
                        keyEncryptor,
                        mockk<IdentityKeyProvider>(relaxed = true),
                    )
                val exportDirectory = File(context.cacheDir, "migration_export").apply {
                    listFiles()?.forEach(File::delete)
                }
                exporter.exportData(password = "archive-password", includeAttachments = false, exportPassword = null)
                val encryptedExport = exportDirectory.listFiles()?.singleOrNull()
                    ?: error("Production exporter did not create exactly one archive")

                val result =
                    requireSuccess(
                        importer.importData(writeTempFile(encryptedExport.readBytes()), password = "archive-password"),
                    )

                assertEquals(0, result.callHistoryImported)
                assertNull(database.callHistoryDao().getByAttemptId("deleted-call"))
                assertEquals(900L, database.callHistoryDeletionDao().getDeletion("deleted-call")?.deletedAt)
            } finally {
                source.close()
            }
        }

    @Test
    fun `deleting a local identity cascade-deletes its call history`() =
        runTest {
            insertCall(database, "identity-call", connectedAt = 120L, outcome = "CONNECTED_ENDED")
            assertNotNull(database.callHistoryDao().getByAttemptId("identity-call"))

            database.localIdentityDao().delete(LOCAL_IDENTITY)

            assertNull(database.callHistoryDao().getByAttemptId("identity-call"))
        }

    private suspend fun insertCall(
        db: ColumbaDatabase,
        callAttemptId: String,
        direction: String = "OUTGOING",
        connectedAt: Long?,
        outcome: String,
        inferredEnding: Boolean = false,
        failureReason: String? = null,
    ) {
        db.callHistoryDao().insertInitial(
            CallHistoryEntity(
                callAttemptId = callAttemptId,
                localIdentityHash = LOCAL_IDENTITY,
                remoteIdentityHash = REMOTE_IDENTITY,
                direction = direction,
                peerDisplayNameSnapshot = "Peer",
                codecProfileCode = 2,
                attemptedAt = 100L,
                ringingAt = 110L,
                connectedAt = connectedAt,
                endedAt = 200L,
                outcome = outcome,
                inferredEnding = inferredEnding,
                failureReason = failureReason,
                serviceInstanceId = "source-service",
            ),
        )
    }

    private fun requireSuccess(result: ImportResult): ImportResult.Success =
        result as? ImportResult.Success
            ?: error("Expected successful import, got $result")

    private fun identity() =
        LocalIdentityEntity(
            identityHash = LOCAL_IDENTITY,
            displayName = "Local",
            destinationHash = "local-destination",
            filePath = "/identity/local",
            keyData = null,
            createdTimestamp = 1L,
            lastUsedTimestamp = 1L,
            isActive = true,
        )

    private fun writeTempFile(bytes: ByteArray): Uri {
        val file = File.createTempFile("call_history_transfer_", ".columba", context.cacheDir)
        file.writeBytes(bytes)
        file.deleteOnExit()
        return Uri.fromFile(file)
    }

    private companion object {
        const val LOCAL_IDENTITY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val REMOTE_IDENTITY = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
