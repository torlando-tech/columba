package com.lxmf.messenger.data.repository

import com.lxmf.messenger.data.db.dao.BlockedPeerDao
import com.lxmf.messenger.data.db.dao.LocalIdentityDao
import com.lxmf.messenger.data.db.entity.BlockedPeerEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
    ) {
        fun getBlockedPeers(): Flow<List<BlockedPeerEntity>> =
            localIdentityDao.getActiveIdentity().flatMapLatest { identity ->
                if (identity == null) {
                    emptyFlow()
                } else {
                    blockedPeerDao.getBlockedPeers(identity.identityHash)
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

        suspend fun updateBlackhole(
            peerHash: String,
            enabled: Boolean,
        ) {
            val identity = localIdentityDao.getActiveIdentitySync() ?: return
            blockedPeerDao.updateBlackholeEnabled(peerHash, identity.identityHash, enabled)
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
