package network.columba.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Stable protocol-event IDs already admitted into peer activity. */
@Entity(tableName = "peer_activity_events")
data class PeerActivityEventEntity(
    @PrimaryKey
    val eventId: String,
)
