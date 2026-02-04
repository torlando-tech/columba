// Room Database Testing Template
// Copy this template for testing Room DAOs

package com.lxmf.messenger.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Room database tests MUST be instrumented tests (androidTest).
 * Use in-memory database for testing - it's created in RAM and destroyed after tests.
 */
@RunWith(AndroidJUnit4::class)
class ExampleDaoTest {
    
    private lateinit var database: YourDatabase
    private lateinit var dao: YourDao
    
    @Before
    fun setup() {
        // Get application context
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        // Create in-memory database
        database = Room.inMemoryDatabaseBuilder(
            context,
            YourDatabase::class.java
        )
            // Allow main thread queries only for testing
            .allowMainThreadQueries()
            .build()
        
        // Get DAO
        dao = database.yourDao()
    }
    
    @After
    fun teardown() {
        // Close database after each test
        database.close()
    }
    
    @Test
    fun insertAndRetrieveEntity() = runTest {
        // Arrange
        val entity = YourEntity(
            id = "test-id",
            field1 = "value1",
            field2 = 42
        )
        
        // Act
        dao.insert(entity)
        val retrieved = dao.getById(entity.id).first()
        
        // Assert
        assertNotNull(retrieved)
        assertEquals(entity.id, retrieved.id)
        assertEquals(entity.field1, retrieved.field1)
        assertEquals(entity.field2, retrieved.field2)
    }
    
    @Test
    fun updateEntity() = runTest {
        // Arrange
        val entity = createTestEntity()
        dao.insert(entity)
        
        // Act
        val updated = entity.copy(field1 = "updated_value")
        dao.update(updated)
        val retrieved = dao.getById(entity.id).first()
        
        // Assert
        assertEquals("updated_value", retrieved.field1)
    }
    
    @Test
    fun deleteEntity() = runTest {
        // Arrange
        val entity = createTestEntity()
        dao.insert(entity)
        
        // Act
        dao.delete(entity)
        val all = dao.getAll().first()
        
        // Assert
        assertTrue(all.isEmpty())
    }
    
    @Test
    fun queryWithCondition() = runTest {
        // Arrange
        dao.insert(createTestEntity(id = "1", field2 = 10))
        dao.insert(createTestEntity(id = "2", field2 = 20))
        dao.insert(createTestEntity(id = "3", field2 = 30))
        
        // Act
        val results = dao.getAllWhereField2GreaterThan(15).first()
        
        // Assert
        assertEquals(2, results.size)
        assertTrue(results.all { it.field2 > 15 })
    }
    
    @Test
    fun flowEmitsUpdates() = runTest {
        // Arrange
        val entity1 = createTestEntity(id = "1")
        val entity2 = createTestEntity(id = "2")
        
        // Act & Assert
        dao.insert(entity1)
        var all = dao.getAll().first()
        assertEquals(1, all.size)
        
        dao.insert(entity2)
        all = dao.getAll().first()
        assertEquals(2, all.size)
    }
    
    // Helper function to create test entities
    private fun createTestEntity(
        id: String = "test-id",
        field1: String = "test-value",
        field2: Int = 42
    ) = YourEntity(id, field1, field2)
}
