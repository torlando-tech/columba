package network.columba.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A propagation node the user has pinned to a prioritized "preferred" list.
 *
 * The list is the candidate pool for transport-aware relay selection. Exactly one
 * entry in the list becomes the active relay (the contact flagged
 * [ContactEntity.isMyRelay]) at a time — the LXMF router holds a single outbound
 * propagation node, so this is a selection problem, not a concurrency one.
 *
 * [networkRestriction] is stored as the raw [NetworkRestriction] name string. The `:data`
 * module has no dependency on `:rns-api` (where `NetworkRestriction` lives), so the
 * entity persists the plain string and the `:app`-side repository/policy maps it to the
 * enum. Keeping it a String here also makes the column trivially queryable in SQL and
 * keeps this entity a plain data class (no custom equals/hashCode for a ByteArray).
 */
@Entity(
    tableName = "preferred_propagation_nodes",
    indices = [
        Index(value = ["destinationHash"], unique = true),
        Index("priority"),
    ],
)
data class PreferredPropagationNodeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Hex (32-char) destination hash of the propagation node. Unique. */
    val destinationHash: String,
    /** Lower number = higher priority. 0-indexed list position. */
    val priority: Int = 0,
    /** Raw `NetworkRestriction` name: "ANY" (default), "WIFI_ONLY", or "CELLULAR_ONLY". */
    val networkRestriction: String = NetworkRestrictionColumn.ANY,
    /** Disabled nodes are skipped by the selection policy (kept for restore). */
    val enabled: Boolean = true,
    /** Denormalized display name (announces can expire; the row should still render). */
    val displayName: String? = null,
    /**
     * Denormalized hex public key so the contact can be created on selection without a
     * re-fetch of the announce. Null for hash-only (pending) entries.
     */
    val publicKey: String? = null,
) {
    /** Column-level constants for the `networkRestriction` string. */
    object NetworkRestrictionColumn {
        const val ANY = "ANY"
        const val WIFI_ONLY = "WIFI_ONLY"
        const val CELLULAR_ONLY = "CELLULAR_ONLY"
    }
}
