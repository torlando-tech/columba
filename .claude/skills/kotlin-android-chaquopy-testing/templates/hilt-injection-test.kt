// Hilt Dependency Injection Testing Template
// Copy this template for testing with Hilt DI

package com.lxmf.messenger

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.TestInstallIn
import dagger.hilt.components.SingletonComponent
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import kotlin.test.assertNotNull

/**
 * Hilt testing requires special setup:
 * 1. @HiltAndroidTest annotation on test class
 * 2. HiltAndroidRule to trigger injection
 * 3. Call hiltRule.inject() in @Before
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ExampleHiltTest {
    
    @get:Rule
    val hiltRule = HiltAndroidRule(this)
    
    // Inject real or test dependencies
    @Inject
    lateinit var repository: YourRepository
    
    @Before
    fun init() {
        // Trigger Hilt injection
        hiltRule.inject()
    }
    
    @Test
    fun testInjectedDependency() {
        // Verify dependency was injected
        assertNotNull(repository)
        
        // Test with real dependency
        val result = repository.someMethod()
        assertNotNull(result)
    }
}

// Optional: Create test module to replace production bindings
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [ProductionModule::class]
)
object TestModule {
    @Provides
    fun provideTestRepository(): YourRepository {
        return FakeRepository() // Or mock
    }
}
