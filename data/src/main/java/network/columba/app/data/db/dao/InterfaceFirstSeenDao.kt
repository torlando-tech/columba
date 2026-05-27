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

    /**
     * Internal: Room compiles a List-parameter @Insert into one prepared
     * statement per row, which Sentry's SQLite instrumentation surfaces
     * as a separate `db.sql.query` span per row and trips the N+1
     * detector (see issue COLUMBA-8W). Callers should prefer
     * [upsertAndFetch], which wraps the batch insert and the follow-up
     * read in a single @Transaction so the work shows up as one
     * parent span.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfNotExistsInternal(entities: List<InterfaceFirstSeenEntity>)

    @Query("SELECT * FROM interface_first_seen WHERE interfaceId IN (:ids)")
    suspend fun getFirstSeenBatch(ids: List<String>): List<InterfaceFirstSeenEntity>

    /**
     * Insert any missing first-seen rows and return the persisted rows for
     * the supplied entities (including pre-existing ones whose original
     * timestamp was preserved by `INSERT OR IGNORE`). Wrapped in a single
     * Room @Transaction so the underlying SQLite work surfaces as one
     * span instead of N (avoids tripping Sentry's N+1 detector).
     */
    @Transaction
    suspend fun upsertAndFetch(
        entities: List<InterfaceFirstSeenEntity>,
    ): List<InterfaceFirstSeenEntity> {
        if (entities.isEmpty()) return emptyList()
        insertAllIfNotExistsInternal(entities)
        return getFirstSeenBatch(entities.map { it.interfaceId })
    }
}
