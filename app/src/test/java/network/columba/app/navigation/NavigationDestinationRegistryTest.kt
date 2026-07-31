package network.columba.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NavigationDestinationRegistryTest {
    @Test
    fun `destination and completion contracts are internally consistent`() {
        val routes = AppDestination.entries.map { it.routePattern }
        assertEquals("Destination route patterns must be unique", routes.size, routes.toSet().size)

        AppDestination.entries.forEach { destination ->
            destination.externalIdentityArguments.forEach { (argument, sampleValue) ->
                assertTrue(
                    "${destination.name} external identity argument $argument must exist in its route",
                    "{$argument}" in destination.routePattern,
                )
                assertTrue(
                    "${destination.name} has unsupported external identity value ${sampleValue::class.simpleName}",
                    sampleValue is String || sampleValue is Int || sampleValue is Long || sampleValue is Boolean,
                )
            }
            assertEquals(
                "${destination.name} external policy and identity metadata must be declared together",
                destination.externalIdentityArguments.isEmpty(),
                destination.externalNavigationPolicy == ExternalNavigationPolicy.NONE,
            )
            when (destination.completionContract) {
                CompletionContract.NONE,
                CompletionContract.RETURN_TO_CALLER -> assertEquals(
                    "${destination.name} must not declare an unused result route",
                    null,
                    destination.completionTargetRoute,
                )
                CompletionContract.SHOW_RESULT -> {
                    val resultRoute = requireNotNull(destination.completionTargetRoute)
                    assertTrue(
                        "${destination.name} result route must be a registered destination",
                        AppDestination.entries.any { it.routePattern == resultRoute },
                    )
                }
            }
        }
    }

    @Test
    fun `production graph registers every destination through the contract registry`() {
        val sources = mainKotlinSources()
        val combinedSource = sources.joinToString("\n") { it.readText() }
        val registeredNames =
            Regex("""appComposable\(\s*AppDestination\.([A-Z_]+)""")
                .findAll(combinedSource)
                .map { it.groupValues[1] }
                .toList()
        val registered = registeredNames.toSet()
        val expected = AppDestination.entries.map { it.name }.toSet()
        val entityNavigationNames =
            Regex("""navigateToEntity\(\s*destination\s*=\s*AppDestination\.([A-Z_]+)""")
                .findAll(combinedSource)
                .map { it.groupValues[1] }
                .toSet()
        val expectedEntityNavigationNames =
            AppDestination.entries
                .filter { it.externalIdentityArguments.isNotEmpty() }
                .map { it.name }
                .toSet()
        val incomingCallIngressCount =
            Regex("""navController\.navigateToIncomingCall\(""").findAll(combinedSource).count()
        val answerCallIngressCount =
            Regex("""navController\.navigateToAnsweredCall\(""").findAll(combinedSource).count()
        val directIncomingCallNavigation =
            Regex("""navController\.navigate\(\s*"incoming_call/""").containsMatchIn(combinedSource)
        val rawRegistrations =
            sources
                .filterNot { it.name == "AppNavigationGraph.kt" }
                .flatMap { source ->
                    source.readLines().mapIndexedNotNull { index, line ->
                        if (Regex("""^\s*composable\(""").containsMatchIn(line)) {
                            "${source.relativeTo(mainKotlinSourceRoot())}:${index + 1}"
                        } else {
                            null
                        }
                    }
                }

        assertEquals(
            "Every destination must be registered exactly once",
            expected.size,
            registeredNames.size,
        )
        assertEquals("Production graph and destination registry must stay in lockstep", expected, registered)
        assertEquals(
            "Every declared external entity destination must use navigateToEntity",
            expectedEntityNavigationNames,
            entityNavigationNames,
        )
        assertTrue("Intent and call-state incoming producers must use the call policy", incomingCallIngressCount >= 2)
        assertTrue("Notification and in-screen answer paths must use the call policy", answerCallIngressCount >= 2)
        assertTrue("Direct incoming-call navigation bypasses rebound protection", !directIncomingCallNavigation)
        assertTrue(
            "Raw composable registrations bypass global navigation contracts: $rawRegistrations",
            rawRegistrations.isEmpty(),
        )
    }

    private fun mainKotlinSources(): List<File> =
        mainKotlinSourceRoot()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()

    private fun mainKotlinSourceRoot(): File {
        val fromModule = File("src/main/java")
        if (fromModule.exists()) return fromModule
        return File("app/src/main/java")
    }
}
