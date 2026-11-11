package network.columba.app.test

import network.columba.app.data.db.dao.AnnounceDao
import network.columba.app.data.db.dao.ContactDao
import network.columba.app.data.db.dao.ConversationDao
import network.columba.app.data.db.dao.LocalIdentityDao
import network.columba.app.data.db.dao.MessageDao
import network.columba.app.data.db.dao.OfflineMapRegionDao
import network.columba.app.data.db.dao.PeerIconDao
import network.columba.app.data.db.dao.ReceivedLocationDao
import network.columba.app.data.db.entity.AnnounceEntity
import network.columba.app.data.db.entity.ContactEntity
import network.columba.app.data.db.entity.ContactStatus
import network.columba.app.data.db.entity.ConversationEntity
import network.columba.app.data.db.entity.MessageEntity
import network.columba.app.data.db.entity.OfflineMapRegionEntity
import network.columba.app.data.db.entity.PeerIconEntity
import network.columba.app.data.db.entity.ReceivedLocationEntity
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.RnsCore
import java.security.MessageDigest

/**
 * Debug-only deterministic demo dataset for Google Play marketing
 * screenshots (drives `store-assets/` in the repo root; see its README).
 *
 * Everything here is fictional: people names, node names, messages, and
 * coordinates are invented for the listing art. Hashes and keys are
 * derived from SHA-256 of stable labels, so repeated seeds are idempotent
 * (all DAOs insert with REPLACE/Upsert on deterministic primary keys).
 *
 * Compiled out of release builds (lives in `app/src/debug/`). Invoked via
 * the `network.columba.test.SEED_STORE_ART` broadcast on [TestReceiver].
 *
 * Deliberately does NOT touch interfaces: enabling one would let the live
 * stack discover real nodes and pollute the deterministic Network tab.
 */
object StoreArtSeeder {
    data class Counts(
        val contacts: Int,
        val announces: Int,
        val conversations: Int,
        val messages: Int,
        val locations: Int,
    )

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    /** The local identity the whole dataset is scoped to. */
    private val identityHash: String get() = hex("identity", 32)
    private val identityDestHash: String get() = hex("identity-dest", 32)

    private fun sha(label: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(("columba-store-art:$label").toByteArray())

    private fun hex(label: String, chars: Int): String =
        sha(label).joinToString("") { "%02x".format(it) }.take(chars)

    /** Deterministic positive row id for tables whose PK is autoGenerate;
     * Room inserts an explicit non-zero PK verbatim, so repeated seeds
     * address the same row. */
    private fun stableId(label: String): Long {
        var v = 0L
        for (b in sha(label).take(8)) v = (v shl 8) or (b.toLong() and 0xff)
        return (v and Long.MAX_VALUE).coerceAtLeast(1L)
    }

    private fun pub(label: String): ByteArray = sha("pub:$label")
    /** Call-capture override: when the host runs a real Python RNS peer,
     * `SEED_STORE_ART` passes its announced destination hash + public key
     * so Maya's conversation dials an actually-reachable node. All other
     * seeded people stay deterministic fakes. */
    private var mayaDestOverride: String? = null
    private var mayaPubOverride: ByteArray? = null

    private fun destOf(p: Person): String =
        if (p === maya) mayaDestOverride ?: hex("dest:${p.label}", 32) else hex("dest:${p.label}", 32)

    private fun pubOf(p: Person): ByteArray =
        if (p === maya) mayaPubOverride ?: pub(p.label) else pub(p.label)

    /** SHA-256(publicKey)[:16] hex, matching AnnounceEntity.computedIdentityHash. */
    private fun computedIdentity(pubKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(pubKey)
            .take(16).joinToString("") { "%02x".format(it) }

    private data class Person(
        val label: String,
        val name: String,
        val icon: String,
        val fg: String,
        val bg: String,
    )

    private val maya = Person("maya", "Maya Okafor", "account-circle", "FFFFFF", "7C4DFF")
    private val theo = Person("theo", "Theo Lindqvist", "account-circle", "FFFFFF", "00897B")
    private val priya = Person("priya", "Priya Raman", "account-circle", "FFFFFF", "F4511E")
    private val diego = Person("diego", "Diego Fuentes", "account-circle", "FFFFFF", "3949AB")
    private val relay = Person("cascade", "Cascade Relay", "radio-tower", "FFFFFF", "546E7A")

    private val networkNodes = listOf(
        "Sunny Slope Relay" to "PROPAGATION_NODE",
        "Windward Node" to "NODE",
        "Bitterroot Hub" to "PROPAGATION_NODE",
        "Alder Relay" to "NODE",
        "Kestrel Node" to "NODE",
        "Marrow Point Hub" to "PROPAGATION_NODE",
        "Foxglove Relay" to "NODE",
        "Cinder Barrens Node" to "NODE",
        "Tidewater Hub" to "PROPAGATION_NODE",
        "High Desert Relay" to "NODE",
    )

    suspend fun seed(
        localIdentityDao: LocalIdentityDao,
        contactDao: ContactDao,
        announceDao: AnnounceDao,
        conversationDao: ConversationDao,
        messageDao: MessageDao,
        receivedLocationDao: ReceivedLocationDao,
        peerIconDao: PeerIconDao,
        offlineMapRegionDao: OfflineMapRegionDao,
        settingsRepository: SettingsRepository,
        identityRepository: IdentityRepository,
        rnsCore: RnsCore,
        mayaDestHash: String? = null,
        mayaPublicKey: ByteArray? = null,
    ): Counts {
        val now = System.currentTimeMillis()
        val me = identityHash
        mayaDestOverride = mayaDestHash?.takeIf { it.isNotEmpty() }
        mayaPubOverride = mayaPublicKey

        // Identity first: every other table FKs to it. Created through
        // IdentityRepository (the same path TestController.handleOnboard
        // uses) so the key is keystore-encrypted and MainActivity's
        // identity-unlock gate passes; the store-art flow never sends real
        // traffic from this identity.
        if (localIdentityDao.getIdentity(me) == null) {
            identityRepository.createIdentity(
                identityHash = me,
                displayName = "Columba",
                destinationHash = identityDestHash,
                filePath = "",
                keyData = rnsCore.getFullIdentityKey(),
            ).getOrThrow()
        }
        localIdentityDao.setActive(me)

        // Announces: contacts' peer records + the Network tab population.
        val contactPeople = listOf(maya, theo, priya, diego, relay)
        val announces = mutableListOf<AnnounceEntity>()
        contactPeople.forEachIndexed { i, p ->
            announces += announce(
                person = p,
                nodeType = if (p === relay) "PROPAGATION_NODE" else "PEER",
                aspect = if (p === relay) "lxmf.propagation" else "lxmf.delivery",
                hops = i + 1,
                lastSeen = now - (i + 1) * 17 * MIN,
            )
        }
        networkNodes.forEachIndexed { i, (name, type) ->
            announces += AnnounceEntity(
                destinationHash = hex("node:$name", 32),
                peerName = name,
                publicKey = pub("node:$name"),
                appData = null,
                hops = (i % 5) + 1,
                lastSeenTimestamp = now - (i * 41 + 7) * MIN,
                nodeType = type,
                receivingInterface = null,
                receivingInterfaceType = null,
                aspect = if (type == "PROPAGATION_NODE") "lxmf.propagation" else "nomadnetwork.node",
                computedIdentityHash = computedIdentity(pub("node:$name")),
            )
        }
        announceDao.upsertAnnounceRows(announces)

        // Contacts (My Contacts tab).
        contactPeople.forEachIndexed { i, p ->
            contactDao.insertContact(
                ContactEntity(
                    destinationHash = destOf(p),
                    identityHash = me,
                    publicKey = pubOf(p),
                    customNickname = p.name,
                    addedTimestamp = now - (28 - i * 4) * DAY,
                    addedVia = "ANNOUNCE",
                    lastInteractionTimestamp = now - (i + 1) * HOUR,
                    isPinned = p === maya,
                    status = ContactStatus.ACTIVE,
                    isMyRelay = p === relay,
                ),
            )
        }

        // Icons for every seeded peer (contacts + map markers).
        (contactPeople + listOf(maya, theo, priya)).distinctBy { it.label }.forEach { p ->
            peerIconDao.upsertIcon(
                PeerIconEntity(
                    destinationHash = destOf(p),
                    iconName = p.icon,
                    foregroundColor = p.fg,
                    backgroundColor = p.bg,
                    updatedTimestamp = now - DAY,
                ),
            )
        }

        // Conversations + messages (Chats tab).
        data class Msg(
            val fromMe: Boolean,
            val content: String,
            val age: Long,
            val replyTo: Int? = null,
            val reactions: String? = null,
        )
        val threads = listOf(
            maya to listOf(
                // (fromMe, content, ageMs, replyToIndex, reactionsJson)
                // reactionsJson is the flat per-target aggregation the UI
                // reads: {emoji: [reactor destination hash]}.
                Msg(true, "Trailhead lot still clear per the last ping", 55 * MIN),
                Msg(false, "Leaving after lunch, will check in from the pass", 48 * MIN),
                Msg(
                    false,
                    "Made it to the pass. Signal's thin but holding",
                    12 * MIN,
                    reactions = """{"👍":["$me"]}""",
                ),
                Msg(
                    true,
                    "Copy that. Send a photo when you can",
                    8 * MIN,
                    replyTo = 2,
                    reactions = """{"❤️":["${destOf(maya)}"]}""",
                ),
                Msg(false, "Coming through now. Full quiet up here", 4 * MIN),
            ),
            theo to listOf(
                Msg(false, "Stamped the first batch of boards", 3 * HOUR),
                Msg(true, "Latency on the stamped ones?", 2 * HOUR + 40 * MIN),
                Msg(false, "Same as unstamped. Nobody notices the difference", 2 * HOUR + 10 * MIN),
            ),
            priya to listOf(
                Msg(false, "Demo page loads now. Partial, but it loads", 5 * HOUR),
                Msg(true, "Ship it to the node, I'll try from the other side", 4 * HOUR + 40 * MIN),
            ),
        )
        var messageCount = 0
        threads.forEach { (person, msgs) ->
            val peerHash = destOf(person)
            val last = msgs.minBy { it.age }
            val unread = msgs.count { !it.fromMe && it.age <= 15 * MIN }
            conversationDao.insertConversation(
                ConversationEntity(
                    peerHash = peerHash,
                    identityHash = me,
                    peerName = person.name,
                    peerPublicKey = pubOf(person),
                    lastMessage = last.content,
                    lastMessageTimestamp = now - last.age,
                    unreadCount = unread,
                    lastSeenTimestamp = now - HOUR,
                ),
            )
            msgs.forEachIndexed { i, m ->
                messageDao.insertMessage(
                    MessageEntity(
                        id = hex("msg:${person.label}:$i", 32),
                        conversationHash = peerHash,
                        identityHash = me,
                        content = m.content,
                        timestamp = now - m.age,
                        isFromMe = m.fromMe,
                        status = "delivered",
                        isRead = m.fromMe || m.age > 15 * MIN,
                        receivedAt = now - m.age,
                        receivedHopCount = if (m.fromMe) null else 2,
                        replyToMessageId =
                            m.replyTo?.let { hex("msg:${person.label}:$it", 32) },
                        reactionsJson = m.reactions,
                    ),
                )
                messageCount++
            }
        }

        // Map markers (received_locations; no FKs, senderHash = peer dest hash).
        receivedLocationDao.insertAll(
            listOf(
                // Clustered around the Hood River GPS fix capture.sh
                // injects, so all three markers are in frame at default zoom.
                location(maya, 45.7186, -121.5145, now - 4 * MIN),
                location(theo, 45.7352, -121.4896, now - 45 * MIN),
                location(priya, 45.6988, -121.5462, now - 3 * HOUR),
            ),
        )

        // Default map region: the map camera centers here when there is no
        // GPS fix (MapViewModel.defaultRegionCenter), which keeps the map
        // capture deterministic without faking a location. maxZoom doubles
        // as the initial camera zoom (clamped 2..14 in MapViewModel). No
        // tile file is attached, so the style stays online.
        val region = OfflineMapRegionEntity(
            id = stableId("region:store-art"),
            name = "Store Art Region",
            centerLatitude = 45.7175,
            centerLongitude = -121.5168,
            radiusKm = 25,
            minZoom = 8,
            // doubles as initial camera zoom; 11 keeps all markers in frame
            maxZoom = 11,
            status = OfflineMapRegionEntity.STATUS_COMPLETE,
            mbtilesPath = null,
            tileCount = 0,
            sizeBytes = 0,
            downloadProgress = 1f,
            errorMessage = null,
            createdAt = now - DAY,
            completedAt = now - DAY,
        )
        // The PK is autoGenerate, so a fresh insert would append another
        // region on every rerun; address the row by its stable id instead.
        if (offlineMapRegionDao.getRegionById(region.id) == null) {
            offlineMapRegionDao.insert(region)
        } else {
            offlineMapRegionDao.update(region)
        }
        offlineMapRegionDao.setDefaultRegion(region.id)

        settingsRepository.markOnboardingCompleted()

        return Counts(
            contacts = contactPeople.size,
            announces = announces.size,
            conversations = threads.size,
            messages = messageCount,
            locations = 3,
        )
    }

    private fun announce(
        person: Person,
        nodeType: String,
        aspect: String,
        hops: Int,
        lastSeen: Long,
    ): AnnounceEntity {
        val key = pubOf(person)
        return AnnounceEntity(
            destinationHash = destOf(person),
            peerName = person.name,
            publicKey = key,
            appData = null,
            hops = hops,
            lastSeenTimestamp = lastSeen,
            nodeType = nodeType,
            receivingInterface = null,
            receivingInterfaceType = null,
            aspect = aspect,
            computedIdentityHash = computedIdentity(key),
        )
    }

    private fun location(
        person: Person,
        lat: Double,
        lon: Double,
        timestamp: Long,
    ): ReceivedLocationEntity =
        ReceivedLocationEntity(
            id = hex("loc:${person.label}", 32),
            senderHash = destOf(person),
            latitude = lat,
            longitude = lon,
            accuracy = 12f,
            timestamp = timestamp,
            expiresAt = null,
            receivedAt = timestamp,
            appearanceJson =
                """{"icon_name":"${person.icon}","foreground_color":"${person.fg}",""" +
                    """"background_color":"${person.bg}"}""",
        )
}
