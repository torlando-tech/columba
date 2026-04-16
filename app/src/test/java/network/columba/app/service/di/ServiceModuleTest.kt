package network.columba.app.service.di

import android.content.Context
import io.mockk.clearAllMocks
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ServiceModule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceModuleTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var context: Context

    @Before
    fun setup() {
        testScope = TestScope(testDispatcher)
        context = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `createManagers returns all required managers`() {
        val managers = ServiceModule.createManagers(context, testScope)

        assertNotNull("state should not be null", managers.state)
        assertNotNull("lockManager should not be null", managers.lockManager)
        assertNotNull("networkChangeManager should not be null", managers.networkChangeManager)
        assertNotNull("notificationManager should not be null", managers.notificationManager)
        assertNotNull("bleCoordinator should not be null", managers.bleCoordinator)
        assertNotNull("persistenceManager should not be null", managers.persistenceManager)
        assertNotNull("settingsAccessor should not be null", managers.settingsAccessor)
    }

    @Test
    fun `createBinder returns a non-null binder`() {
        val managers = ServiceModule.createManagers(context, testScope)

        val binder =
            ServiceModule.createBinder(
                managers = managers,
                onShutdown = {},
            )

        assertNotNull(binder)
    }
}
