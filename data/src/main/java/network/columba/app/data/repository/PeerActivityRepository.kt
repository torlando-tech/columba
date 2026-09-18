package network.columba.app.data.repository

import kotlinx.coroutines.flow.Flow
import network.columba.app.data.db.dao.PeerActivityDao
import network.columba.app.data.db.entity.PeerActivityEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Persistent source of truth for the latest verified packet received from each peer. */
@Singleton
class PeerActivityRepository
    @Inject
    constructor(
        private val peerActivityDao: PeerActivityDao,
    ) {
        suspend fun recordActivity(
            destinationHash: String,
            receivedAt: Long,
            activityType: String,
        ) {
            require(receivedAt > 0) { "receivedAt must be positive" }
            peerActivityDao.recordActivity(destinationHash.lowercase(), receivedAt, activityType)
        }

        fun observeActivity(destinationHash: String): Flow<PeerActivityEntity?> =
            peerActivityDao.observeActivity(destinationHash.lowercase())
    }
