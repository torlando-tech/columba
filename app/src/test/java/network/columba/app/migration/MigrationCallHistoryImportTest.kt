package network.columba.app.migration

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import network.columba.app.data.crypto.IdentityKeyEncryptor
import network.columba.app.data.database.InterfaceDatabase
import network.columba.app.data.database.dao.InterfaceDao
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.CallHistoryEntity
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.repository.SettingsRepository
import network.columba.app.service.PropagationNodeManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Suppress("NoRelaxedMocks") // External migration collaborators are irrelevant to the Room import assertions.
class MigrationCallHistoryImportTest {
    private lateinit var context: Context
    private lateinit var database: ColumbaDatabase
    private lateinit var importer: MigrationImporter
    private val json = Json { encodeDefaults = true }

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
    fun `import preserves attempt id and duplicate import is idempotent`() =
        runTest {
            val zip = zip(bundle(listOf(call())))

            val first = requireSuccess(importer.importData(Uri.EMPTY, cachedZipBytes = zip))
            val second = requireSuccess(importer.importData(Uri.EMPTY, cachedZipBytes = zip))

            assertEquals(1, first.callHistoryImported)
            assertEquals(0, first.callHistoryConflictsSkipped)
            assertEquals(0, second.callHistoryImported)
            assertEquals(0, second.callHistoryConflictsSkipped)
            assertEquals("CONNECTED_ENDED", database.callHistoryDao().getByAttemptId("attempt-1")?.outcome)
        }

    @Test
    fun `reimport cannot resurrect a deleted finalized call`() =
        runTest {
            val archive = zip(bundle(listOf(call())))
            requireSuccess(importer.importData(Uri.EMPTY, cachedZipBytes = archive))
            assertEquals(1, database.callHistoryDeletionDao().deleteFinalized("attempt-1", LOCAL_IDENTITY, 500L))

            val replay = requireSuccess(importer.importData(Uri.EMPTY, cachedZipBytes = archive))

            assertEquals(0, replay.callHistoryImported)
            assertEquals(1, replay.callHistoryConflictsSkipped)
            assertNull(database.callHistoryDao().getByAttemptId("attempt-1"))
        }

    @Test
    fun `transfer deletion authority removes and suppresses finalized evidence`() =
        runTest {
            val oldArchive = zip(bundle(listOf(call())))
            requireSuccess(importer.importData(Uri.EMPTY, cachedZipBytes = oldArchive))
            val deletionArchive =
                zip(
                    bundle(
                        calls = emptyList(),
                        deletions = listOf(CallHistoryDeletionExport("attempt-1", LOCAL_IDENTITY, 500L)),
                    ),
                )

            requireSuccess(importer.importData(Uri.EMPTY, cachedZipBytes = deletionArchive))
            val replay = requireSuccess(importer.importData(Uri.EMPTY, cachedZipBytes = oldArchive))

            assertNull(database.callHistoryDao().getByAttemptId("attempt-1"))
            assertEquals(LOCAL_IDENTITY, database.callHistoryDeletionDao().getDeletion("attempt-1")?.localIdentityHash)
            assertEquals(1, replay.callHistoryConflictsSkipped)
        }

    @Test
    fun `encrypted v8 import carries deletion authority through production decryption`() =
        runTest {
            val encrypted =
                MigrationCrypto.encrypt(
                    zip(
                        bundle(
                            calls = emptyList(),
                            deletions = listOf(CallHistoryDeletionExport("encrypted-deletion", LOCAL_IDENTITY, 500L)),
                        ),
                    ),
                    "transfer-password",
                )

            requireSuccess(importer.importData(writeTempFile(encrypted), password = "transfer-password"))

            assertEquals(500L, database.callHistoryDeletionDao().getDeletion("encrypted-deletion")?.deletedAt)
        }

    @Test
    fun `conflicting deletion timestamp is rejected as immutable evidence`() =
        runTest {
            val first = bundle(emptyList(), listOf(CallHistoryDeletionExport("immutable-deletion", LOCAL_IDENTITY, 500L)))
            val conflict = bundle(emptyList(), listOf(CallHistoryDeletionExport("immutable-deletion", LOCAL_IDENTITY, 501L)))

            requireSuccess(importer.importData(Uri.EMPTY, cachedZipBytes = zip(first)))
            val result = importer.importData(Uri.EMPTY, cachedZipBytes = zip(conflict))

            assertTrue(result is ImportResult.Error)
            assertEquals(500L, database.callHistoryDeletionDao().getDeletion("immutable-deletion")?.deletedAt)
        }

    @Test
    fun `historical encrypted v7 manifest without deletion field imports with empty authority`() =
        runTest {
            val current = json.parseToJsonElement(json.encodeToString(bundle(listOf(call())))).jsonObject.toMutableMap()
            current["version"] = JsonPrimitive(7)
            current.remove("callHistoryDeletions")
            val encrypted = MigrationCrypto.encrypt(zipManifest(JsonObject(current).toString()), "legacy-password")

            val result = requireSuccess(importer.importData(writeTempFile(encrypted), password = "legacy-password"))

            assertEquals(1, result.callHistoryImported)
            assertNull(database.callHistoryDeletionDao().getDeletion("attempt-1"))
        }

    @Test
    fun `v7 manifest cannot smuggle v8 deletion authority`() =
        runTest {
            val current =
                json.parseToJsonElement(
                    json.encodeToString(
                        bundle(
                            calls = listOf(call()),
                            deletions = listOf(CallHistoryDeletionExport("attempt-deleted", LOCAL_IDENTITY, 500L)),
                        ),
                    ),
                ).jsonObject.toMutableMap()
            current["version"] = JsonPrimitive(7)

            val result = importer.importData(Uri.EMPTY, cachedZipBytes = zipManifest(JsonObject(current).toString()))

            assertTrue(result is ImportResult.Error)
            assertNull(database.callHistoryDao().getByAttemptId("attempt-1"))
            assertNull(database.callHistoryDeletionDao().getDeletion("attempt-deleted"))
        }

    @Test
    fun `deletion authority for unfinished local call rolls back entire import`() =
        runTest {
            database.callHistoryDao().insertInitial(
                CallHistoryEntity(
                    callAttemptId = "unfinished-local",
                    localIdentityHash = LOCAL_IDENTITY,
                    remoteIdentityHash = REMOTE_IDENTITY,
                    direction = "OUTGOING",
                    peerDisplayNameSnapshot = "Peer",
                    codecProfileCode = null,
                    attemptedAt = 50L,
                    ringingAt = null,
                    connectedAt = null,
                    endedAt = null,
                    outcome = null,
                    inferredEnding = false,
                    failureReason = null,
                    serviceInstanceId = "live-service",
                ),
            )

            val result =
                importer.importData(
                    Uri.EMPTY,
                    cachedZipBytes =
                        zip(
                            bundle(
                                calls = listOf(call().copy(callAttemptId = "must-roll-back")),
                                deletions =
                                    listOf(
                                        CallHistoryDeletionExport(
                                            callAttemptId = "unfinished-local",
                                            localIdentityHash = LOCAL_IDENTITY,
                                            deletedAt = 500L,
                                        ),
                                    ),
                            ),
                        ),
                )

            assertTrue(result is ImportResult.Error)
            assertNull(database.callHistoryDao().getByAttemptId("must-roll-back"))
            assertTrue(database.callHistoryDao().getByAttemptId("unfinished-local")?.endedAt == null)
            assertNull(database.callHistoryDeletionDao().getDeletion("unfinished-local"))
        }

    @Test
    fun `conflicting attempt is skipped without overwriting local evidence`() =
        runTest {
            importer.importData(Uri.EMPTY, cachedZipBytes = zip(bundle(listOf(call()))))

            val result =
                importer.importData(
                    Uri.EMPTY,
                    cachedZipBytes = zip(bundle(listOf(call().copy(connectedAt = 130L)))),
                ).let(::requireSuccess)

            assertEquals(0, result.callHistoryImported)
            assertEquals(1, result.callHistoryConflictsSkipped)
            assertEquals("CONNECTED_ENDED", database.callHistoryDao().getByAttemptId("attempt-1")?.outcome)
        }

    @Test
    fun `open call evidence is transferred with non-live imported ownership`() =
        runTest {
            val openCall =
                call().copy(
                    callAttemptId = "open-attempt",
                    endedAt = null,
                    outcome = null,
                    failureReason = null,
                )

            val result = requireSuccess(importer.importData(Uri.EMPTY, cachedZipBytes = zip(bundle(listOf(openCall)))))
            val imported = database.callHistoryDao().getByAttemptId("open-attempt")

            assertEquals(1, result.callHistoryImported)
            assertEquals(121L, imported?.endedAt)
            assertEquals("INTERRUPTED", imported?.outcome)
            assertEquals(true, imported?.inferredEnding)
            assertEquals("migration-import-v8", imported?.serviceInstanceId)
        }

    @Test
    fun `open call whose recovery timestamp would overflow is rejected transactionally`() =
        runTest {
            val overflow =
                call().copy(
                    callAttemptId = "overflow-attempt",
                    attemptedAt = Long.MAX_VALUE,
                    ringingAt = Long.MAX_VALUE,
                    connectedAt = Long.MAX_VALUE,
                    endedAt = null,
                    outcome = null,
                )

            val result = importer.importData(Uri.EMPTY, cachedZipBytes = zip(bundle(listOf(overflow))))

            assertTrue(result is ImportResult.Error)
            assertNull(database.callHistoryDao().getByAttemptId("overflow-attempt"))
        }

    @Test
    fun `valid incoming failed call imports without rejecting the bundle`() =
        runTest {
            val incomingFailed =
                call().copy(
                    callAttemptId = "incoming-failed",
                    direction = "INCOMING",
                    connectedAt = null,
                    outcome = "FAILED",
                    failureReason = "NETWORK_UNAVAILABLE",
                )

            val result = requireSuccess(importer.importData(Uri.EMPTY, cachedZipBytes = zip(bundle(listOf(incomingFailed)))))

            assertEquals(1, result.callHistoryImported)
            assertEquals("FAILED", database.callHistoryDao().getByAttemptId("incoming-failed")?.outcome)
        }

    @Test
    fun `unconnected outcome with connection evidence rolls back the transaction`() =
        runTest {
            val result =
                importer.importData(
                    Uri.EMPTY,
                    cachedZipBytes =
                        zip(
                            bundle(
                                listOf(
                                    call().copy(callAttemptId = "valid-before-contradiction"),
                                    call().copy(
                                        callAttemptId = "contradictory",
                                        outcome = "NOT_CONNECTED",
                                        connectedAt = 150L,
                                    ),
                                ),
                            ),
                        ),
                )

            assertTrue(result is ImportResult.Error)
            assertNull(database.callHistoryDao().getByAttemptId("valid-before-contradiction"))
            assertNull(database.callHistoryDao().getByAttemptId("contradictory"))
        }

    @Test
    fun `malformed call history rolls back the entire database transaction`() =
        runTest {
            val result =
                importer.importData(
                    Uri.EMPTY,
                    cachedZipBytes =
                        zip(
                            bundle(
                                listOf(
                                    call().copy(callAttemptId = "valid-attempt"),
                                    call().copy(
                                        callAttemptId = "malformed-attempt",
                                        direction = "INCOMING",
                                        outcome = "BUSY_REMOTE",
                                    ),
                                ),
                            ),
                        ),
                )

            assertTrue(result is ImportResult.Error)
            assertNull(database.callHistoryDao().getByAttemptId("valid-attempt"))
            assertNull(database.callHistoryDao().getByAttemptId("malformed-attempt"))
        }

    @Test
    fun `malformed identity hash rolls back the entire database transaction`() =
        runTest {
            val result =
                importer.importData(
                    Uri.EMPTY,
                    cachedZipBytes =
                        zip(
                            bundle(
                                listOf(
                                    call().copy(callAttemptId = "valid-attempt"),
                                    call().copy(callAttemptId = "malformed-hash", remoteIdentityHash = "not-a-hash"),
                                ),
                            ),
                        ),
                )

            assertTrue(result is ImportResult.Error)
            assertNull(database.callHistoryDao().getByAttemptId("valid-attempt"))
            assertNull(database.callHistoryDao().getByAttemptId("malformed-hash"))
        }

    @Test
    fun `uppercase call history identity hashes are canonicalized`() =
        runTest {
            val imported =
                call().copy(
                    callAttemptId = "uppercase-hashes",
                    localIdentityHash = LOCAL_IDENTITY.uppercase(),
                    remoteIdentityHash = REMOTE_IDENTITY.uppercase(),
                )

            val result = importer.importData(Uri.EMPTY, cachedZipBytes = zip(bundle(listOf(imported))))

            requireSuccess(result)
            val stored = database.callHistoryDao().getByAttemptId("uppercase-hashes")!!
            assertEquals(LOCAL_IDENTITY, stored.localIdentityHash)
            assertEquals(REMOTE_IDENTITY, stored.remoteIdentityHash)
        }

    private fun requireSuccess(result: ImportResult): ImportResult.Success =
        result as? ImportResult.Success
            ?: error("Expected successful import, got $result")

    private fun bundle(
        calls: List<CallHistoryExport>,
        deletions: List<CallHistoryDeletionExport> = emptyList(),
    ) =
        MigrationBundle(
            identities = emptyList(),
            conversations = emptyList(),
            messages = emptyList(),
            contacts = emptyList(),
            callHistory = calls,
            callHistoryDeletions = deletions,
            settings = SettingsExport(),
        )

    private fun call() =
        CallHistoryExport(
            callAttemptId = "attempt-1",
            localIdentityHash = LOCAL_IDENTITY,
            remoteIdentityHash = REMOTE_IDENTITY,
            direction = "OUTGOING",
            peerDisplayNameSnapshot = "Peer",
            codecProfileCode = 2,
            attemptedAt = 100L,
            ringingAt = 110L,
            connectedAt = 120L,
            endedAt = 200L,
            outcome = "CONNECTED_ENDED",
            inferredEnding = false,
            failureReason = null,
        )

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

    private fun zip(bundle: MigrationBundle): ByteArray {
        return zipManifest(json.encodeToString(bundle))
    }

    private fun zipManifest(manifest: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

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
