package network.columba.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import network.columba.app.data.db.entity.InterfaceFirstSeenEntity

@Dao
interface InterfaceFirstSeenDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(entity: InterfaceFirstSeenEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfNotExists(entities: List<InterfaceFirstSeenEntity>)

    @Query("SELECT * FROM interface_first_seen WHERE interfaceId IN (:ids)")
    suspend fun getFirstSeenBatch(ids: List<String>): List<InterfaceFirstSeenEntity>

    /**
     * Inserts all entities (ignoring duplicates) and returns the first-seen
     * timestamps for the given IDs in a single Room transaction, preventing
     * N+1 individual INSERT spans from being emitted.
     */
    @Transaction
    suspend fun insertAndGetFirstSeen(entities: List<InterfaceFirstSeenEntity>): List<InterfaceFirstSeenEntity> {
        insertAllIfNotExists(entities)
        return getFirstSeenBatch(entities.map { it.interfaceId })
    }
}
