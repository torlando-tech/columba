package network.columba.app.data.repository

import network.columba.app.data.db.dao.AnnounceDao
import network.columba.app.data.db.dao.BlockedPeerDao
import network.columba.app.data.db.dao.ContactDao
import network.columba.app.data.db.dao.ConversationDao
import network.columba.app.data.db.dao.LocalIdentityDao
import network.columba.app.data.db.entity.AnnounceEntity
import network.columba.app.data.db.entity.BlockedPeerEntity
import network.columba.app.data.db.entity.ContactEntity
import network.columba.app.data.db.entity.ConversationEntity
import network.columba.app.data.model.BlockedIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class BlockedPeerRepository
    @Inject
    constructor(
        private val blockedPeerDao: BlockedPeerDao,
        private val localIdentityDao: LocalIdentityDao,
        private val announceDao: AnnounceDao,
        private val contactDao: ContactDao,
        private val conversationDao: ConversationDao,
    ) {
        fun getBlockedPeers(): Flow<List<BlockedPeerEntity>> =
            localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    emptyFlow()
                } else {
                    blockedPeerDao.getBlockedPeers(identity.identityHash)
                }
            }

        fun getBlockedIdentities(): Flow<List<BlockedIdentity>> =
            localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    emptyFlow()
                } else {
                    combine(
                        blockedPeerDao.getBlockedPeers(identity.identityHash),
                        announceDao.getAllAnnounces(),
                        contactDao.getAllContacts(identity.identityHash),
                        conversationDao.getAllConversations(identity.identityHash),
                    ) { rows, announces, contacts, conversations ->
                        rows.toBlockedIdentities(announces, contacts, conversations)
                    }
                }
            }

        fun isBlockedFlow(peerHash: String): Flow<Boolean> =
            localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    emptyFlow()
                } else {
                    blockedPeerDao.isBlockedFlow(peerHash, identity.identityHash)
                }
            }

        fun isIdentityBlockedFlow(remoteIdentityHash: String): Flow<Boolean> =
            localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    emptyFlow()
                } else {
                    blockedPeerDao.isIdentityBlockedFlow(remoteIdentityHash, identity.identityHash)
                }
            }

        suspend fun isIdentityBlocked(remoteIdentityHash: String): Boolean {
            val identity = localIdentityDao.getActiveIdentitySync() ?: return true
            return blockedPeerDao.isIdentityBlocked(remoteIdentityHash.lowercase(), identity.identityHash)
        }

        suspend fun getApprovedDestinationHashes(remoteIdentityHash: String): List<String> =
            announceDao
                .getApprovedPeerAnnounces(remoteIdentityHash.lowercase())
                .map { it.destinationHash }
                .distinct()

        suspend fun blockIdentity(
            remoteIdentityHash: String,
            displayName: String?,
            blackholeEnabled: Boolean,
            localIdentityHash: String,
        ) {
            val identity = localIdentityDao.getActiveIdentitySync() ?: error("No active local identity")
            check(identity.identityHash == localIdentityHash) { "Active local identity changed" }
            val canonicalRemoteIdentityHash = remoteIdentityHash.lowercase()
            val timestamp = System.currentTimeMillis()
            val approvedAnnounces = announceDao.getApprovedPeerAnnounces(canonicalRemoteIdentityHash)
            val destinations = approvedAnnounces.map { it.destinationHash }.distinct()
            val aspectsByDestination = approvedAnnounces.associate { it.destinationHash to it.aspect }
            val rows =
                (listOf(canonicalRemoteIdentityHash) + destinations)
                    .distinct()
                    .map { peerHash ->
                        BlockedPeerEntity(
                            peerHash = peerHash,
                            identityHash = identity.identityHash,
                            peerIdentityHash = canonicalRemoteIdentityHash,
                            displayName = displayName,
                            blockedTimestamp = timestamp,
                            isBlackholeEnabled = blackholeEnabled,
                            routingAspect = aspectsByDestination[peerHash],
                        )
                    }
            blockedPeerDao.insertIdentityBlockWithAliases(rows)
        }

        suspend fun getIdentityBlock(
            remoteIdentityHash: String,
            localIdentityHash: String,
        ): BlockedPeerEntity? {
            val identity = localIdentityDao.getActiveIdentitySync() ?: return null
            check(identity.identityHash == localIdentityHash) { "Active local identity changed" }
            return blockedPeerDao.getIdentityBlock(remoteIdentityHash, identity.identityHash)
        }

        suspend fun unblockIdentity(
            remoteIdentityHash: String,
            localIdentityHash: String,
        ) {
            val identity = localIdentityDao.getActiveIdentitySync() ?: error("No active local identity")
            check(identity.identityHash == localIdentityHash) { "Active local identity changed" }
            blockedPeerDao.deleteIdentityBlock(remoteIdentityHash, identity.identityHash)
        }

        suspend fun blockPeer(
            peerHash: String,
            peerIdentityHash: String?,
            displayName: String?,
            blackholeEnabled: Boolean,
        ) {
            val identity = localIdentityDao.getActiveIdentitySync() ?: return
            blockedPeerDao.insertBlockedPeer(
                BlockedPeerEntity(
                    peerHash = peerHash,
                    identityHash = identity.identityHash,
                    peerIdentityHash = peerIdentityHash,
                    displayName = displayName,
                    blockedTimestamp = System.currentTimeMillis(),
                    isBlackholeEnabled = blackholeEnabled,
                ),
            )
        }

        suspend fun unblockPeer(peerHash: String) {
            val identity = localIdentityDao.getActiveIdentitySync() ?: return
            blockedPeerDao.deleteBlockedPeer(peerHash, identity.identityHash)
        }

        suspend fun getBlockedPeer(peerHash: String): BlockedPeerEntity? {
            val identity = localIdentityDao.getActiveIdentitySync() ?: return null
            return blockedPeerDao.getBlockedPeer(peerHash, identity.identityHash)
        }

        suspend fun updateBlackhole(
            peerHash: String,
            enabled: Boolean,
        ) {
            val identity = localIdentityDao.getActiveIdentitySync() ?: return
            blockedPeerDao.updateBlackholeEnabled(peerHash, identity.identityHash, enabled)
        }

        suspend fun updateIdentityBlackhole(
            remoteIdentityHash: String,
            localIdentityHash: String,
            enabled: Boolean,
        ) {
            val identity = localIdentityDao.getActiveIdentitySync() ?: error("No active local identity")
            check(identity.identityHash == localIdentityHash) { "Active local identity changed" }
            blockedPeerDao.updateIdentityBlackholeEnabled(remoteIdentityHash, localIdentityHash, enabled)
        }

        suspend fun getBlockedPeerHashes(): List<String> {
            val identity = localIdentityDao.getActiveIdentitySync() ?: return emptyList()
            return blockedPeerDao.getBlockedPeerHashes(identity.identityHash)
        }

        suspend fun getBlackholedPeerIdentityHashes(): List<String> {
            val identity = localIdentityDao.getActiveIdentitySync() ?: return emptyList()
            return blockedPeerDao.getBlackholedPeerIdentityHashes(identity.identityHash)
        }

        fun getBlockedPeerCount(): Flow<Int> =
            localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    emptyFlow()
                } else {
                    blockedPeerDao.getBlockedPeerCount(identity.identityHash)
                }
            }
    }

internal fun List<BlockedPeerEntity>.toBlockedIdentities(
    announces: List<AnnounceEntity> = emptyList(),
    contacts: List<ContactEntity> = emptyList(),
    conversations: List<ConversationEntity> = emptyList(),
): List<BlockedIdentity> =
    groupBy { it.peerIdentityHash ?: it.peerHash }
        .map { (remoteIdentityHash, rows) ->
            val peerHashes = rows.map(BlockedPeerEntity::peerHash).toSet() + remoteIdentityHash
            val liveName =
                contacts
                    .filter { it.destinationHash in peerHashes && !it.customNickname.isNullOrBlank() }
                    .maxByOrNull(ContactEntity::lastInteractionTimestamp)
                    ?.customNickname
                    ?: announces
                        .filter { it.destinationHash in peerHashes || it.computedIdentityHash == remoteIdentityHash }
                        .maxByOrNull(AnnounceEntity::lastSeenTimestamp)
                        ?.peerName
                        ?.takeIf(String::isNotBlank)
                    ?: conversations
                        .filter { it.peerHash in peerHashes && it.peerName.isNotBlank() }
                        .maxByOrNull(ConversationEntity::lastMessageTimestamp)
                        ?.peerName
            val destinationHashes =
                rows.map(BlockedPeerEntity::peerHash)
                    .filter { it != remoteIdentityHash || rows.none { row -> row.peerIdentityHash != null } }
                    .distinct()
                    .sorted()
            BlockedIdentity(
                remoteIdentityHash = remoteIdentityHash,
                localIdentityHash = rows.first().identityHash,
                displayName = liveName ?: rows.firstNotNullOfOrNull(BlockedPeerEntity::displayName),
                destinationHashes = destinationHashes,
                blockedTimestamp = rows.minOf(BlockedPeerEntity::blockedTimestamp),
                isBlackholeEnabled = rows.any(BlockedPeerEntity::isBlackholeEnabled),
                identityAuthoritative = rows.any { it.peerIdentityHash != null },
                destinationAspects =
                    destinationHashes.associateWith { destinationHash ->
                        announces
                            .filter { it.destinationHash == destinationHash }
                            .maxByOrNull(AnnounceEntity::lastSeenTimestamp)
                            ?.aspect
                            ?: rows.firstOrNull { it.peerHash == destinationHash }?.routingAspect
                    },
            )
        }.sortedByDescending(BlockedIdentity::blockedTimestamp)
