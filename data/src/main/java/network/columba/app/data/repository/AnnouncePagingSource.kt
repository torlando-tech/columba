package network.columba.app.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState
import network.columba.app.data.db.dao.AnnounceDao
import network.columba.app.data.model.EnrichedAnnounce
import kotlinx.coroutines.CancellationException

/**
 * Position in the announce stream's total order: newest first, ties broken by hash.
 */
data class AnnounceCursor(
    val lastSeenTimestamp: Long,
    val destinationHash: String,
)

/**
 * Keyset ("seek") [PagingSource] over announces.
 *
 * Deliberately not a Room-generated `@Query` PagingSource: those subscribe to the
 * `InvalidationTracker`, so every announce write triggers the Pager to create a new
 * source and reload from the first row. A refresh replaces the whole presented page
 * store, which collapses a deeply scrolled list and loses the viewport (GH issue #1166).
 *
 * Here the stream only moves when the ViewModel starts a new generation (refresh
 * request, filter change), and within a generation pages are fetched relative to a
 * cursor, so rows that arrive at the top do not shift the page boundaries of anything
 * already loaded.
 */
class AnnouncePagingSource(
    private val announceDao: AnnounceDao,
    private val nodeTypes: List<String>,
    private val searchQuery: String,
    private val interfaceTypes: List<String>,
) : PagingSource<AnnounceCursor, EnrichedAnnounce>() {
    override suspend fun load(params: LoadParams<AnnounceCursor>): LoadResult<AnnounceCursor, EnrichedAnnounce> {
        val cursor = params.key
        return try {
            val rows =
                when (params) {
                    is LoadParams.Prepend ->
                        if (cursor == null) {
                            // A prepend above the newest row has nothing to load.
                            return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
                        } else {
                            announceDao.getAnnouncesNewerPage(
                                nodeTypes = nodeTypes,
                                nodeTypeCount = nodeTypes.size,
                                query = searchQuery,
                                interfaceTypes = interfaceTypes,
                                interfaceTypeCount = interfaceTypes.size,
                                cursorTimestamp = cursor.lastSeenTimestamp,
                                cursorHash = cursor.destinationHash,
                                limit = params.loadSize,
                            )
                        }
                    else ->
                        announceDao.getAnnouncesOlderPage(
                            nodeTypes = nodeTypes,
                            nodeTypeCount = nodeTypes.size,
                            query = searchQuery,
                            interfaceTypes = interfaceTypes,
                            interfaceTypeCount = interfaceTypes.size,
                            cursorTimestamp = cursor?.lastSeenTimestamp,
                            cursorHash = cursor?.destinationHash,
                            limit = params.loadSize,
                        )
                }

            // Older pages come back in list order; newer pages come back nearest-first.
            val pageRows = if (params is LoadParams.Prepend) rows.asReversed() else rows
            val hasMore = rows.size == params.loadSize
            val firstCursor = pageRows.firstOrNull()?.let { AnnounceCursor(it.lastSeenTimestamp, it.destinationHash) }
            val lastCursor = pageRows.lastOrNull()?.let { AnnounceCursor(it.lastSeenTimestamp, it.destinationHash) }
            LoadResult.Page(
                data = pageRows,
                prevKey =
                    when (params) {
                        // A refresh always starts at the newest row, so nothing is above it.
                        is LoadParams.Refresh -> null
                        // Prepend keeps going up only while it is still returning full pages.
                        is LoadParams.Prepend -> firstCursor.takeIf { hasMore }
                        // Prepending above an append page would re-return loaded rows.
                        else -> null
                    },
                nextKey = lastCursor.takeIf { hasMore },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    /**
     * A new generation always starts at the newest row: refreshes are user-triggered and
     * only happen while the viewport is at the top, so there is nothing to restore.
     */
    override fun getRefreshKey(state: PagingState<AnnounceCursor, EnrichedAnnounce>): AnnounceCursor? = null
}
