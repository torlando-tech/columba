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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import network.columba.app.data.crypto.IdentityKeyEncryptor
import network.columba.app.data.database.InterfaceDatabase
import network.columba.app.data.database.dao.InterfaceDao
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.repository.SettingsRepository
import network.columba.app.service.PropagationNodeManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Threads must survive backup export/import (v9 bundles): message rows keep
 * their `threadId` membership and thread rows (title, root, archive) restore.
 * Pre-v9 bundles must still import with every message in Main.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@Suppress("NoRelaxedMocks") // Migration collaborators are irrelevant to the Room import assertions.
class MigrationThreadImportTest {
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
        runTest {
            database.localIdentityDao().insert(
                LocalIdentityEntity(
                    identityHash = LOCAL_IDENTITY,
                    displayName = "Local",
                    destinationHash = "local-destination",
                    filePath = "/identity/local",
                    keyData = null,
                    createdTimestamp = 1L,
                    lastUsedTimestamp = 1L,
                    isActive = true,
                ),
            )
        }
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `v9 import restores thread rows and message membership`() =
        runTest {
            requireSuccess(importer.importData(Uri.EMPTY, cachedZipFile = tempZipFile(zip(bundle()))))

            val threaded = database.messageDao().getMessageById("msg-threaded", LOCAL_IDENTITY)
            assertArrayEquals(THREAD_BYTES, threaded?.threadId)
            assertNull(database.messageDao().getMessageById("msg-root", LOCAL_IDENTITY)?.threadId)

            val thread = database.threadDao().getThread(LOCAL_IDENTITY, PEER, THREAD_BYTES)
            assertEquals("Trip budget", thread?.title)
            assertEquals("msg-root", thread?.rootMessageId)
            assertEquals(900L, thread?.archivedAt)
            assertEquals(200L, thread?.lastActivityAt)
        }

    @Test
    fun `reimport is idempotent and restores the bundle thread state`() =
        runTest {
            requireSuccess(importer.importData(Uri.EMPTY, cachedZipFile = tempZipFile(zip(bundle()))))
            requireSuccess(importer.importData(Uri.EMPTY, cachedZipFile = tempZipFile(zip(bundle()))))

            val thread = database.threadDao().getThread(LOCAL_IDENTITY, PEER, THREAD_BYTES)
            assertEquals("Trip budget", thread?.title)
            assertEquals(900L, thread?.archivedAt)
            assertArrayEquals(THREAD_BYTES, database.messageDao().getMessageById("msg-threaded", LOCAL_IDENTITY)?.threadId)
        }

    @Test
    fun `pre-v9 bundle without thread fields imports everything to Main`() =
        runTest {
            val manifest =
                json.parseToJsonElement(json.encodeToString(bundle())).jsonObject.toMutableMap()
            manifest["version"] = JsonPrimitive(8)
            manifest.remove("threads")
            manifest["messages"] =
                JsonArray(
                    manifest.getValue("messages").jsonArray.map { message ->
                        JsonObject(message.jsonObject.toMutableMap().apply { remove("threadId") })
                    },
                )
            requireSuccess(
                importer.importData(Uri.EMPTY, cachedZipFile = tempZipFile(zipManifest(JsonObject(manifest).toString()))),
            )

            assertNull(database.messageDao().getMessageById("msg-threaded", LOCAL_IDENTITY)?.threadId)
            assertNull(database.threadDao().getThread(LOCAL_IDENTITY, PEER, THREAD_BYTES))
        }

    private fun bundle() =
        MigrationBundle(
            identities = emptyList(),
            conversations =
                listOf(
                    ConversationExport(
                        peerHash = PEER,
                        identityHash = LOCAL_IDENTITY,
                        peerName = "Peer",
                        peerPublicKey = null,
                        lastMessage = "Root message",
                        lastMessageTimestamp = 100L,
                        unreadCount = 0,
                        lastSeenTimestamp = 0L,
                    ),
                ),
            messages =
                listOf(
                    MessageExport(
                        id = "msg-root",
                        conversationHash = PEER,
                        identityHash = LOCAL_IDENTITY,
                        content = "Root message",
                        timestamp = 100L,
                        isFromMe = true,
                        status = "sent",
                        isRead = true,
                        fieldsJson = null,
                    ),
                    MessageExport(
                        id = "msg-threaded",
                        conversationHash = PEER,
                        identityHash = LOCAL_IDENTITY,
                        content = "In the thread",
                        timestamp = 200L,
                        isFromMe = true,
                        status = "sent",
                        isRead = true,
                        fieldsJson = null,
                        threadId = THREAD_HEX,
                    ),
                ),
            threads =
                listOf(
                    ThreadExport(
                        threadId = THREAD_HEX,
                        conversationHash = PEER,
                        identityHash = LOCAL_IDENTITY,
                        rootMessageId = "msg-root",
                        title = "Trip budget",
                        createdAt = 150L,
                        lastActivityAt = 200L,
                        archivedAt = 900L,
                    ),
                ),
            contacts = emptyList(),
            settings = SettingsExport(),
        )

    private fun requireSuccess(result: ImportResult): ImportResult.Success =
        result as? ImportResult.Success
            ?: error("Expected successful import, got $result")

    private fun zip(bundle: MigrationBundle): ByteArray = zipManifest(json.encodeToString(bundle))

    private fun zipManifest(manifest: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun tempZipFile(bytes: ByteArray): File {
        val file = File.createTempFile("thread_transfer_", ".zip", context.cacheDir)
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }

    private companion object {
        const val LOCAL_IDENTITY = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PEER = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val THREAD_HEX = "0123456789abcdef0123456789abcdef"
        val THREAD_BYTES: ByteArray =
            ByteArray(16) {
                THREAD_HEX.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
    }
}
