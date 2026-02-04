// Threading Test Template
// Copy this template for new threading tests

package com.lxmf.messenger.threading

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.Assert.*

class ThreadingTest {
    
    @Test
    fun testConcurrentAccess() = runTest {
        // Test concurrent access to shared resource
        val results = (1..10).map { i ->
            async(Dispatchers.IO) {
                // Perform operation
                performOperation(i)
            }
        }.awaitAll()
        
        assertEquals(10, results.size)
        results.forEach { assertNotNull(it) }
    }
    
    @Test
    fun testThreadSafety() = runTest {
        // Test that operations are thread-safe
        val sharedState = mutableListOf<Int>()
        
        (1..100).map { i ->
            async {
                // Should not cause concurrent modification
                synchronized(sharedState) {
                    sharedState.add(i)
                }
            }
        }.awaitAll()
        
        assertEquals(100, sharedState.size)
    }
    
    private suspend fun performOperation(value: Int): Int {
        return withContext(Dispatchers.IO) {
            // Simulate work
            value * 2
        }
    }
}
