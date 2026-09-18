package network.columba.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Latest verified packet received from a peer, keyed by LXMF destination hash. */
@Entity(tableName = "peer_activity")
data class PeerActivityEntity(
    @PrimaryKey
    val destinationHash: String,
    val lastReceivedAt: Long,
    val activityType: String,
)

/** Stable values persisted in [PeerActivityEntity.activityType]. */
object PeerActivityType {
    const val ANNOUNCE = "ANNOUNCE"
    const val MESSAGE = "MESSAGE"
    const val PROOF = "PROOF"
    const val TELEMETRY = "TELEMETRY"
    const val LINK = "LINK"
}
