package network.columba.app.data.repository

import network.columba.app.data.db.dao.ReceivedLocationDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for querying received contact locations.
 *
 * Centralizes hash normalization and expiry filtering so that
 * ViewModels do not duplicate this logic.
 *
 * The expiry check includes a grace period that matches MapViewModel's
 * marker visibility: a location remains available for "Locate on Map"
 * as long as its marker is still shown on the map.
 */
@Singleton
class ReceivedLocationRepository
    @Inject
    constructor(
        private val receivedLocationDao: ReceivedLocationDao,
    ) {
        companion object {
            /** Grace period matching MapViewModel.GRACE_PERIOD_MS (markers stay visible for 1 h past expiry). */
            internal const val GRACE_PERIOD_MS = 60 * 60 * 1000L
        }

        /**
         * Get the latest known location for a peer, including the grace period.
         * Returns a Pair(latitude, longitude) or null if the location is beyond the grace period.
         */
        suspend fun getContactLocation(destinationHash: String): Pair<Double, Double>? {
            val location = receivedLocationDao.getLatestLocationForSender(destinationHash.lowercase())
            val expires = location?.expiresAt
            val now = System.currentTimeMillis()
            return location?.takeIf { expires == null || now < expires + GRACE_PERIOD_MS }
                ?.let { Pair(it.latitude, it.longitude) }
        }

        /**
         * Observe whether a peer has a known location within the grace period (reactive Flow).
         */
        fun observeHasLocation(destinationHash: String): Flow<Boolean> =
            receivedLocationDao.observeLatestLocationForSender(destinationHash.lowercase())
                .map { loc ->
                    val expires = loc?.expiresAt
                    val now = System.currentTimeMillis()
                    loc != null && (expires == null || now < expires + GRACE_PERIOD_MS)
                }
    }
