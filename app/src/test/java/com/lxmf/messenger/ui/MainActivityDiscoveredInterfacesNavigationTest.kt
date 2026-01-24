package com.lxmf.messenger.ui

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.screens.buildFocusInterfaceDetails
import com.lxmf.messenger.ui.screens.isValidCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Navigation tests for DiscoveredInterfaces and MapFocus routes in MainActivity.
 * Tests that the navigation routes are correctly configured and parameters are parsed properly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainActivityDiscoveredInterfacesNavigationTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== isValidCoordinate Tests ==========

    @Test
    fun `isValidCoordinate with null returns false`() {
        assertFalse(isValidCoordinate(null))
    }

    @Test
    fun `isValidCoordinate with zero returns false`() {
        assertFalse(isValidCoordinate(0.0))
    }

    @Test
    fun `isValidCoordinate with valid latitude returns true`() {
        assertTrue(isValidCoordinate(45.123))
    }

    @Test
    fun `isValidCoordinate with negative longitude returns true`() {
        assertTrue(isValidCoordinate(-122.456))
    }

    @Test
    fun `isValidCoordinate with very small non-zero returns true`() {
        assertTrue(isValidCoordinate(0.00001))
    }

    // ========== FocusInterfaceDetails Building Tests ==========

    @Test
    fun `buildFocusDetails with valid coordinates returns details`() {
        val details =
            buildFocusInterfaceDetails(
                lat = 45.123,
                lon = -122.456,
                label = "Test Interface",
                type = "TCPServerInterface",
                height = 100.0,
                reachableOn = "192.168.1.1",
                port = 4242,
                frequency = 915000000L,
                bandwidth = 125000,
                sf = 10,
                cr = 5,
                modulation = "LoRa",
                status = "online",
                lastHeard = 1234567890L,
                hops = 2,
            )

        assertNotNull(details)
        assertEquals("Test Interface", details!!.name)
        assertEquals("TCPServerInterface", details.type)
        assertEquals(45.123, details.latitude, 0.001)
        assertEquals(-122.456, details.longitude, 0.001)
        assertEquals(100.0, details.height!!, 0.001)
        assertEquals("192.168.1.1", details.reachableOn)
        assertEquals(4242, details.port)
        assertEquals(915000000L, details.frequency)
        assertEquals(125000, details.bandwidth)
        assertEquals(10, details.spreadingFactor)
        assertEquals(5, details.codingRate)
        assertEquals("LoRa", details.modulation)
        assertEquals("online", details.status)
        assertEquals(1234567890L, details.lastHeard)
        assertEquals(2, details.hops)
    }

    @Test
    fun `buildFocusDetails with zero coordinates returns null`() {
        val details =
            buildFocusInterfaceDetails(
                lat = 0.0,
                lon = 0.0,
                label = "Test",
                type = "TCP",
            )
        assertNull(details)
    }

    @Test
    fun `buildFocusDetails with null coordinates returns null`() {
        val details =
            buildFocusInterfaceDetails(
                lat = null,
                lon = null,
                label = "Test",
                type = "TCP",
            )
        assertNull(details)
    }

    @Test
    fun `buildFocusDetails converts default values to null`() {
        val details =
            buildFocusInterfaceDetails(
                lat = 45.0,
                lon = -122.0,
                label = "Test",
                type = "TCP",
                height = Double.NaN, // Default for missing height
                reachableOn = "", // Default empty string
                port = -1, // Default for missing port
                frequency = -1L, // Default for missing frequency
                bandwidth = -1,
                sf = -1,
                cr = -1,
                modulation = "",
                status = "",
                lastHeard = -1L,
                hops = -1,
            )

        assertNotNull(details)
        assertNull(details!!.height)
        assertNull(details.reachableOn)
        assertNull(details.port)
        assertNull(details.frequency)
        assertNull(details.bandwidth)
        assertNull(details.spreadingFactor)
        assertNull(details.codingRate)
        assertNull(details.modulation)
        assertNull(details.status)
        assertNull(details.lastHeard)
        assertNull(details.hops)
    }

    @Test
    fun `buildFocusDetails with empty label uses Unknown`() {
        val details =
            buildFocusInterfaceDetails(
                lat = 45.0,
                lon = -122.0,
                label = null,
                type = "TCP",
            )

        assertNotNull(details)
        assertEquals("Unknown", details!!.name)
    }

    @Test
    fun `buildFocusDetails with empty type uses Unknown`() {
        val details =
            buildFocusInterfaceDetails(
                lat = 45.0,
                lon = -122.0,
                label = "Test",
                type = "",
            )

        assertNotNull(details)
        assertEquals("Unknown", details!!.type)
    }

    // ========== Navigation Route Tests ==========

    @Test
    fun `discovered_interfaces route renders content`() {
        composeTestRule.setContent {
            NavHost(
                navController = rememberNavController(),
                startDestination = "discovered_interfaces",
            ) {
                composable("discovered_interfaces") {
                    Text("Discovered Interfaces Screen")
                }
            }
        }

        composeTestRule.onNodeWithText("Discovered Interfaces Screen").assertIsDisplayed()
    }

    @Test
    fun `map_focus route parses latitude and longitude`() {
        var capturedLat: Double? = null
        var capturedLon: Double? = null

        composeTestRule.setContent {
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = "map_focus?lat=45.5&lon=-122.6&label=Test&type=TCP",
            ) {
                composable(
                    route = "map_focus?lat={lat}&lon={lon}&label={label}&type={type}",
                    arguments =
                        listOf(
                            navArgument("lat") {
                                type = NavType.FloatType
                                defaultValue = 0f
                            },
                            navArgument("lon") {
                                type = NavType.FloatType
                                defaultValue = 0f
                            },
                            navArgument("label") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("type") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                        ),
                ) { backStackEntry ->
                    capturedLat = backStackEntry.arguments?.getFloat("lat")?.toDouble()
                    capturedLon = backStackEntry.arguments?.getFloat("lon")?.toDouble()
                    Text("Map Screen with lat=$capturedLat, lon=$capturedLon")
                }
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(45.5, capturedLat!!, 0.1)
        assertEquals(-122.6, capturedLon!!, 0.1)
    }

    @Test
    fun `map_focus route parses string parameters`() {
        var capturedLabel: String? = null
        var capturedType: String? = null

        composeTestRule.setContent {
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = "map_focus?lat=45.5&lon=-122.6&label=TestNode&type=TCPServer",
            ) {
                composable(
                    route = "map_focus?lat={lat}&lon={lon}&label={label}&type={type}",
                    arguments =
                        listOf(
                            navArgument("lat") {
                                type = NavType.FloatType
                                defaultValue = 0f
                            },
                            navArgument("lon") {
                                type = NavType.FloatType
                                defaultValue = 0f
                            },
                            navArgument("label") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("type") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                        ),
                ) { backStackEntry ->
                    capturedLabel = backStackEntry.arguments?.getString("label")
                    capturedType = backStackEntry.arguments?.getString("type")
                    Text("Map with label=$capturedLabel, type=$capturedType")
                }
            }
        }

        composeTestRule.waitForIdle()
        assertEquals("TestNode", capturedLabel)
        assertEquals("TCPServer", capturedType)
    }

    @Test
    fun `map_focus route uses defaults for missing parameters`() {
        var capturedPort: Int? = null
        var capturedFrequency: Long? = null

        composeTestRule.setContent {
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = "map_focus?lat=45.5&lon=-122.6",
            ) {
                composable(
                    route = "map_focus?lat={lat}&lon={lon}&port={port}&frequency={frequency}",
                    arguments =
                        listOf(
                            navArgument("lat") {
                                type = NavType.FloatType
                                defaultValue = 0f
                            },
                            navArgument("lon") {
                                type = NavType.FloatType
                                defaultValue = 0f
                            },
                            navArgument("port") {
                                type = NavType.IntType
                                defaultValue = -1
                            },
                            navArgument("frequency") {
                                type = NavType.LongType
                                defaultValue = -1L
                            },
                        ),
                ) { backStackEntry ->
                    capturedPort = backStackEntry.arguments?.getInt("port")
                    capturedFrequency = backStackEntry.arguments?.getLong("frequency")
                    Text("Map with port=$capturedPort, frequency=$capturedFrequency")
                }
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(-1, capturedPort)
        assertEquals(-1L, capturedFrequency)
    }

    // ========== TCP Client Wizard with Pre-filled Parameters Tests ==========

    @Test
    fun `tcp_client_wizard route with discovered interface parameters`() {
        var capturedHost: String? = null
        var capturedPort: Int? = null
        var capturedName: String? = null

        composeTestRule.setContent {
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = "tcp_client_wizard?host=192.168.1.1&port=4242&name=TestNode",
            ) {
                composable(
                    route = "tcp_client_wizard?host={host}&port={port}&name={name}",
                    arguments =
                        listOf(
                            navArgument("host") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("port") {
                                type = NavType.IntType
                                defaultValue = 4242
                            },
                            navArgument("name") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                        ),
                ) { backStackEntry ->
                    capturedHost = backStackEntry.arguments?.getString("host")
                    capturedPort = backStackEntry.arguments?.getInt("port")
                    capturedName = backStackEntry.arguments?.getString("name")
                    Text("Wizard with host=$capturedHost, port=$capturedPort, name=$capturedName")
                }
            }
        }

        composeTestRule.waitForIdle()
        assertEquals("192.168.1.1", capturedHost)
        assertEquals(4242, capturedPort)
        assertEquals("TestNode", capturedName)
    }

    // ========== RNode Wizard with LoRa Parameters Tests ==========

    @Test
    fun `rnode_wizard route with discovered LoRa parameters`() {
        var capturedFrequency: Long? = null
        var capturedBandwidth: Int? = null
        var capturedSf: Int? = null
        var capturedCr: Int? = null

        composeTestRule.setContent {
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = "rnode_wizard?loraFrequency=915000000&loraBandwidth=125000&loraSf=10&loraCr=5",
            ) {
                composable(
                    route = "rnode_wizard?loraFrequency={loraFrequency}&loraBandwidth={loraBandwidth}&loraSf={loraSf}&loraCr={loraCr}",
                    arguments =
                        listOf(
                            navArgument("loraFrequency") {
                                type = NavType.LongType
                                defaultValue = -1L
                            },
                            navArgument("loraBandwidth") {
                                type = NavType.IntType
                                defaultValue = -1
                            },
                            navArgument("loraSf") {
                                type = NavType.IntType
                                defaultValue = -1
                            },
                            navArgument("loraCr") {
                                type = NavType.IntType
                                defaultValue = -1
                            },
                        ),
                ) { backStackEntry ->
                    capturedFrequency = backStackEntry.arguments?.getLong("loraFrequency")
                    capturedBandwidth = backStackEntry.arguments?.getInt("loraBandwidth")
                    capturedSf = backStackEntry.arguments?.getInt("loraSf")
                    capturedCr = backStackEntry.arguments?.getInt("loraCr")
                    Text("RNode wizard with freq=$capturedFrequency, bw=$capturedBandwidth, sf=$capturedSf, cr=$capturedCr")
                }
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(915000000L, capturedFrequency)
        assertEquals(125000, capturedBandwidth)
        assertEquals(10, capturedSf)
        assertEquals(5, capturedCr)
    }
}
