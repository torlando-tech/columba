package network.columba.app.rns.host.di

import android.content.Context
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.di.DatabaseModule

/**
 * Manual database provider for the :reticulum service process.
 *
 * Hilt doesn't work across process boundaries, so we create the database manually
 * in the service process. This allows the service to persist announces and messages
 * directly to the database even when the app process is killed.
 *
 * Uses [DatabaseModule.buildColumbaDatabase] so the service process opens the file with the same
 * migrations, pragmas, multi-instance invalidation, and busy-retry behaviour as the app process.
 */
object ServiceDatabaseProvider {
    @Volatile
    private var INSTANCE: ColumbaDatabase? = null

    fun getDatabase(context: Context): ColumbaDatabase =
        INSTANCE ?: synchronized(this) {
            INSTANCE ?: DatabaseModule.buildColumbaDatabase(context).also { INSTANCE = it }
        }

    fun close() {
        synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
