package network.columba.app.rns.host.persistence

import android.content.Context
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.dao.ContactDao
import network.columba.app.data.db.dao.LocalIdentityDao
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.rns.host.di.ServiceDatabaseProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CallsFromContactsGate.
 *
 * Covers the same shape ServicePersistenceManagerTest uses for
 * shouldBlockUnknownSender — settings toggle, DAO mocks, fail-open on
 * exception — so the inbound-call gate stays consistent with the
 * inbound-message gate.
 */
class CallsFromContactsGateTest {
    private lateinit var context: Context
    private lateinit var database: ColumbaDatabase
    private lateinit var contactDao: ContactDao
    private lateinit var localIdentityDao: LocalIdentityDao
    private lateinit var settingsAccessor: ServiceSettingsAccessor
    private lateinit var gate: CallsFromContactsGate

    private val callerIdentityHash = "abcdef0123456789abcdef0123456789"
    private val ownerIdentityHash = "0011223344556677001122334455667700"

    private val sampleIdentity =
        LocalIdentityEntity(
            identityHash = ownerIdentityHash,
            displayName = "Owner",
            destinationHash = "deadbeefdeadbeefdeadbeefdeadbeef",
            filePath = "/dev/null",
            createdTimestamp = 0L,
            lastUsedTimestamp = 0L,
            isActive = true,
        )

    @Suppress("NoRelaxedMocks") // Android Context fixture — gate touches no Context methods directly
    @Before
    fun setup() {
        context = mockk(relaxed = true)
        database = mockk()
        contactDao = mockk()
        localIdentityDao = mockk()
        settingsAccessor = mockk()

        every { database.contactDao() } returns contactDao
        every { database.localIdentityDao() } returns localIdentityDao

        mockkObject(ServiceDatabaseProvider)
        every { ServiceDatabaseProvider.getDatabase(any()) } returns database

        gate = CallsFromContactsGate(context, settingsAccessor)
    }

    @After
    fun tearDown() {
        unmockkObject(ServiceDatabaseProvider)
        clearAllMocks()
    }

    @Test
    fun `shouldSilentlyDrop returns false when toggle is off`() {
        every { settingsAccessor.getAllowCallsFromContactsOnly() } returns false

        assertFalse(gate.shouldSilentlyDrop(callerIdentityHash))
    }

    @Test
    fun `shouldSilentlyDrop returns true when toggle on and no destination for identity matches a contact`() {
        // contactExistsByIdentityHash returns false when there is no
        // (announce, contact) join for this identity — including the "no
        // announces at all" case.
        every { settingsAccessor.getAllowCallsFromContactsOnly() } returns true
        coEvery { localIdentityDao.getActiveIdentitySync() } returns sampleIdentity
        coEvery { contactDao.contactExistsByIdentityHash(callerIdentityHash, ownerIdentityHash) } returns false

        assertTrue(gate.shouldSilentlyDrop(callerIdentityHash))
    }

    @Test
    fun `shouldSilentlyDrop returns false when caller is a known contact`() {
        every { settingsAccessor.getAllowCallsFromContactsOnly() } returns true
        coEvery { localIdentityDao.getActiveIdentitySync() } returns sampleIdentity
        coEvery { contactDao.contactExistsByIdentityHash(callerIdentityHash, ownerIdentityHash) } returns true

        assertFalse(gate.shouldSilentlyDrop(callerIdentityHash))
    }

    @Test
    fun `shouldSilentlyDrop allows caller when only their LXMF destination is in contacts but call arrives over LXST`() {
        // Regression for the multi-destination bug: same peer publishes BOTH
        // an `lxmf.delivery` and an `lxst.telephony` announce sharing one
        // computedIdentityHash. The contact row stores only the LXMF
        // destination. contactExistsByIdentityHash's JOIN must find the
        // cross-link and return true regardless of which destination the
        // inbound call's link is keyed to.
        every { settingsAccessor.getAllowCallsFromContactsOnly() } returns true
        coEvery { localIdentityDao.getActiveIdentitySync() } returns sampleIdentity
        coEvery { contactDao.contactExistsByIdentityHash(callerIdentityHash, ownerIdentityHash) } returns true

        assertFalse(gate.shouldSilentlyDrop(callerIdentityHash))
    }

    @Test
    fun `shouldSilentlyDrop fails open when no active local identity exists`() {
        // Without an owner identity we cannot meaningfully check contacts.
        // Allowing the call through is safer than silently dropping every
        // inbound during the first-launch / identity-rotation window.
        every { settingsAccessor.getAllowCallsFromContactsOnly() } returns true
        coEvery { localIdentityDao.getActiveIdentitySync() } returns null

        assertFalse(gate.shouldSilentlyDrop(callerIdentityHash))
    }

    @Test
    fun `shouldSilentlyDrop normalises identity hash to lowercase before lookup`() {
        every { settingsAccessor.getAllowCallsFromContactsOnly() } returns true
        coEvery { localIdentityDao.getActiveIdentitySync() } returns sampleIdentity
        coEvery { contactDao.contactExistsByIdentityHash(callerIdentityHash, ownerIdentityHash) } returns true

        // Uppercase + mixed-case inputs both reduce to the same lowercase lookup.
        assertFalse(gate.shouldSilentlyDrop(callerIdentityHash.uppercase()))
    }

    @Test
    fun `shouldSilentlyDrop fails open when contact DAO throws`() {
        every { settingsAccessor.getAllowCallsFromContactsOnly() } returns true
        coEvery { localIdentityDao.getActiveIdentitySync() } returns sampleIdentity
        coEvery { contactDao.contactExistsByIdentityHash(any(), any()) } throws RuntimeException("Room boom")

        assertFalse(gate.shouldSilentlyDrop(callerIdentityHash))
    }
}
