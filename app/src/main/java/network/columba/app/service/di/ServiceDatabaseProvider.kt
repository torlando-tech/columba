package network.columba.app.service.di

import android.content.Context
import androidx.room.Room
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.di.DatabaseModule

/**
 * Manual database provider for the :reticulum service process.
 *
 * Hilt doesn't work across process boundaries, so we create the database manually
 * in the service process. This allows the service to persist announces and messages
 * directly to the database even when the app process is killed.
 *
 * Uses [enableMultiInstanceInvalidation] to handle cross-process database access safely.
 */
object ServiceDatabaseProvider {
    @Volatile
    private var INSTANCE: ColumbaDatabase? = null

    fun getDatabase(context: Context): ColumbaDatabase =
        INSTANCE ?: synchronized(this) {
            INSTANCE ?: createDatabase(context).also { INSTANCE = it }
        }

    private fun createDatabase(context: Context): ColumbaDatabase =
        Room
            .databaseBuilder(
                context.applicationContext,
                ColumbaDatabase::class.java,
                DatabaseModule.DATABASE_NAME,
            ).fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade()
            .enableMultiInstanceInvalidation()
            .build()

    fun close() {
        synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
