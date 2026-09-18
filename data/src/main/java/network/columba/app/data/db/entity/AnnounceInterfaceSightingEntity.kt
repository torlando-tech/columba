package network.columba.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Most recent accepted Reticulum path observation for one destination and
 * canonical interface type.
 *
 * Reticulum keeps one active path per destination. This table preserves the
 * recent history of interfaces which have won that path selection so the
 * user-facing interface filter does not flicker when the active path moves.
 */
@Entity(
    tableName = "announce_interface_sightings",
    primaryKeys = ["destinationHash", "interfaceType"],
    foreignKeys = [
        ForeignKey(
            entity = AnnounceEntity::class,
            parentColumns = ["destinationHash"],
            childColumns = ["destinationHash"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("destinationHash"),
        Index("interfaceType", "lastSeenTimestamp"),
        Index("lastSeenTimestamp"),
    ],
)
data class AnnounceInterfaceSightingEntity(
    val destinationHash: String,
    val interfaceType: String,
    val receivingInterface: String?,
    val lastSeenTimestamp: Long,
    val hops: Int,
)
