package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.testing.TestLifecycleOwner
import network.columba.app.test.RegisterComponentActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MapPollingLifecycleBindingTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    @Test
    fun `binding follows lifecycle until composition is disposed`() {
        val lifecycleOwner = TestLifecycleOwner(initialState = Lifecycle.State.RESUMED)
        val isBound = mutableStateOf(true)
        val events = mutableListOf<Pair<Any, Boolean>>()

        composeRule.setContent {
            if (isBound.value) {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    MapPollingLifecycleBinding { ownerId, resumed ->
                        events += ownerId to resumed
                    }
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(listOf(true), events.map { it.second })
            lifecycleOwner.currentState = Lifecycle.State.STARTED
        }
        composeRule.runOnIdle {
            assertEquals(listOf(true, false), events.map { it.second })
            lifecycleOwner.currentState = Lifecycle.State.RESUMED
        }
        composeRule.runOnIdle {
            assertEquals(listOf(true, false, true), events.map { it.second })
            isBound.value = false
        }
        composeRule.runOnIdle {
            assertEquals(listOf(true, false, true, false), events.map { it.second })
            events.forEach { assertSame(events.first().first, it.first) }
        }
    }

    @Test
    fun `overlapping bindings retain independent owner tokens`() {
        val firstLifecycleOwner = TestLifecycleOwner(initialState = Lifecycle.State.RESUMED)
        val secondLifecycleOwner = TestLifecycleOwner(initialState = Lifecycle.State.RESUMED)
        val firstEvents = mutableListOf<Pair<Any, Boolean>>()
        val secondEvents = mutableListOf<Pair<Any, Boolean>>()

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides firstLifecycleOwner) {
                MapPollingLifecycleBinding { ownerId, resumed ->
                    firstEvents += ownerId to resumed
                }
            }
            CompositionLocalProvider(LocalLifecycleOwner provides secondLifecycleOwner) {
                MapPollingLifecycleBinding { ownerId, resumed ->
                    secondEvents += ownerId to resumed
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(listOf(true), firstEvents.map { it.second })
            assertEquals(listOf(true), secondEvents.map { it.second })
            assertNotSame(firstEvents.single().first, secondEvents.single().first)
            firstLifecycleOwner.currentState = Lifecycle.State.STARTED
        }
        composeRule.runOnIdle {
            assertEquals(listOf(true, false), firstEvents.map { it.second })
            assertEquals(listOf(true), secondEvents.map { it.second })
        }
    }
}