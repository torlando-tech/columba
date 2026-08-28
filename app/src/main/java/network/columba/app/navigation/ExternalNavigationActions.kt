package network.columba.app.navigation

import androidx.navigation.NavController
import network.columba.app.rns.api.model.CallState

/**
 * Navigates to an externally requested entity without stacking the same logical
 * entity repeatedly. A different entity remains a normal push so its history is
 * preserved.
 */
fun NavController.navigateToEntity(
    destination: AppDestination,
    route: String,
    identityArguments: Map<String, Any>,
) {
    require(destination.externalIdentityArguments.isNotEmpty()) {
        "${destination.name} must declare external identity arguments"
    }
    require(destination.externalNavigationPolicy != ExternalNavigationPolicy.NONE) {
        "${destination.name} must declare an external navigation policy"
    }
    require(identityArguments.keys == destination.externalIdentityArguments.keys) {
        "Identity arguments for ${destination.name} must be ${destination.externalIdentityArguments.keys}"
    }

    val currentEntry = currentBackStackEntry
    val isSameEntity =
        currentEntry?.destination?.route == destination.routePattern &&
            identityArguments.all { (name, expectedValue) ->
                val arguments = currentEntry.arguments ?: return@all false
                when (expectedValue) {
                    is String -> arguments.getString(name) == expectedValue
                    is Int -> arguments.getInt(name, Int.MIN_VALUE) == expectedValue
                    is Long -> arguments.getLong(name, Long.MIN_VALUE) == expectedValue
                    is Boolean -> arguments.containsKey(name) && arguments.getBoolean(name) == expectedValue
                    else -> error("Unsupported navigation identity type: ${expectedValue::class.simpleName}")
                }
            }

    val shouldReuseCurrentDestination =
        currentEntry?.destination?.route == destination.routePattern &&
            when (destination.externalNavigationPolicy) {
                ExternalNavigationPolicy.REUSE_SAME_ENTITY -> isSameEntity
                ExternalNavigationPolicy.REPLACE_DESTINATION -> true
                ExternalNavigationPolicy.NONE -> false
            }

    navigate(route) {
        launchSingleTop = shouldReuseCurrentDestination
    }
}

/**
 * Presents the one active incoming-call destination. Competing notification and
 * call-state producers replace the current incoming-call entry rather than
 * leaving stale call screens in history.
 *
 * Cold-start race (COLUMBA-8Y): the caller's `LaunchedEffect(callState)`
 * can observe an already-Incoming call before the NavHost attaches its navigation
 * graph. A null currentDestination means the start destination is not committed
 * yet, and NavController.navigate would throw "Navigation graph has not been
 * set for NavController". We skip the navigation here (never throw); the caller
 * retriggers the request once the graph becomes live, so the Incoming call is
 * not silently discarded.
 */
fun NavController.navigateToIncomingCall(route: String) {
    val current = currentDestination ?: return
    if (current.route == AppDestination.VOICE_CALL.routePattern) {
        return
    }
    navigate(route) {
        launchSingleTop = current.route == AppDestination.INCOMING_CALL.routePattern
    }
}

/**
 * Decides whether the incoming-call screen should be (re)presented for the given
 * call state and current route: the call is Incoming and we are not already on a
 * call screen nor mid-answer.
 *
 * Shared by the live `LaunchedEffect(callState)` observer and the graph-ready
 * retrigger in ColumbaNavigation so the cold-start retrigger (COLUMBA-8Y) uses
 * exactly the same predicate as the steady-state observer.
 */
internal fun shouldPresentIncomingCall(
    callState: CallState,
    currentRoute: String?,
    isAnsweringCall: Boolean,
): Boolean =
    callState is CallState.Incoming &&
        !(
            currentRoute?.startsWith("incoming_call/") == true ||
                currentRoute?.startsWith("voice_call/") == true
        ) &&
        !isAnsweringCall

/**
 * Replaces the incoming-call flow with the active voice call.
 *
 * If an incoming-call entry exists, it and everything above it are removed. If
 * the voice call is already visible, launchSingleTop updates/reuses that entry.
 */
fun NavController.navigateToAnsweredCall(route: String) {
    popBackStack(AppDestination.INCOMING_CALL.routePattern, inclusive = true)
    navigate(route) {
        launchSingleTop = true
    }
}
