package com.lxmf.messenger.data.repository

import com.lxmf.messenger.data.db.dao.ReceivedLocationDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for querying received contact locations.
 *
 * Centralizes hash normalization and expiry filtering so that
 * ViewModels do not duplicate this logic.
 */
@Singleton
class ReceivedLocationRepository
    @Inject
    constructor(
        private val receivedLocationDao: ReceivedLocationDao,
    ) {
        /**
         * Get the latest known, non-expired location for a peer.
         * Returns a Pair(latitude, longitude) or null if no valid location is known.
         */
        suspend fun getContactLocation(destinationHash: String): Pair<Double, Double>? {
            val location = receivedLocationDao.getLatestLocationForSender(destinationHash.lowercase())
            val expires = location?.expiresAt
            return location?.takeIf { expires == null || expires > System.currentTimeMillis() }
                ?.let { Pair(it.latitude, it.longitude) }
        }

        /**
         * Observe whether a peer has a known, non-expired location (reactive Flow).
         */
        fun observeHasLocation(destinationHash: String): Flow<Boolean> =
            receivedLocationDao.observeLatestLocationForSender(destinationHash.lowercase())
                .map { loc ->
                    val expires = loc?.expiresAt
                    loc != null && (expires == null || expires > System.currentTimeMillis())
                }
    }
