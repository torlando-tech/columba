package network.columba.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import network.columba.app.data.db.entity.InterfaceFirstSeenEntity

@Dao
interface InterfaceFirstSeenDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(entity: InterfaceFirstSeenEntity)

    @Query("SELECT * FROM interface_first_seen WHERE interfaceId IN (:ids)")
    suspend fun getFirstSeenBatch(ids: List<String>): List<InterfaceFirstSeenEntity>
}
