package com.lxmf.messenger.service.di

import android.content.Context
import androidx.room.Room
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.di.DatabaseModule

/**
 * Manual database provider for the :reticulum service process.
 *
 * Hilt doesn't work across process boundaries, so we create the database manually
 * in the service process. This allows the service to persist announces and messages
 * directly to the database even when the app process is killed.
 *
 * Uses [DatabaseModule.ALL_MIGRATIONS] to ensure migrations stay in sync.
 * Uses [enableMultiInstanceInvalidation] to handle cross-process database access safely.
 */
object ServiceDatabaseProvider {
    @Volatile
    private var INSTANCE: ColumbaDatabase? = null

    /**
     * Gets the database instance for the service process.
     * Creates it lazily on first access with thread-safe double-checked locking.
     */
    fun getDatabase(context: Context): ColumbaDatabase {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: createDatabase(context).also { INSTANCE = it }
        }
    }

    @Suppress("SpreadOperator") // Spread is required by Room API; called once at initialization
    private fun createDatabase(context: Context): ColumbaDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            ColumbaDatabase::class.java,
            DatabaseModule.DATABASE_NAME,
        )
            .addMigrations(*DatabaseModule.ALL_MIGRATIONS)
            .enableMultiInstanceInvalidation()
            .build()
    }

    /**
     * Closes the database connection.
     * Should be called when the service is destroyed.
     */
    fun close() {
        synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
