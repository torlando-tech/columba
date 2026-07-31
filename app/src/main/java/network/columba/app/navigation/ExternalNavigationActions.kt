package network.columba.app.navigation

import androidx.navigation.NavController

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
 */
fun NavController.navigateToIncomingCall(route: String) {
    if (currentDestination?.route == AppDestination.VOICE_CALL.routePattern) {
        return
    }
    navigate(route) {
        launchSingleTop = currentDestination?.route == AppDestination.INCOMING_CALL.routePattern
    }
}

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
