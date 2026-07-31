package network.columba.app.navigation

import androidx.navigation.NavController

/**
 * Completes the currently visible flow according to its declared contract.
 *
 * The flow entry is always removed regardless of which origin launched it. A
 * result flow then presents its result destination; a return flow reveals its
 * caller. Android Back can therefore never expose a completed flow.
 */
fun NavController.completeCurrentFlow(flow: AppDestination) {
    require(currentDestination?.route == flow.routePattern) {
        "Cannot complete ${flow.name} while ${currentDestination?.route} is visible"
    }
    when (flow.completionContract) {
        CompletionContract.NONE -> error("${flow.name} does not declare a completion contract")
        CompletionContract.RETURN_TO_CALLER -> popBackStack()
        CompletionContract.SHOW_RESULT -> {
            val resultRoute = requireNotNull(flow.completionTargetRoute) {
                "${flow.name} does not declare a completion target"
            }
            completeCurrentFlowTo(resultRoute)
        }
    }
}

internal fun NavController.completeCurrentFlowTo(resultRoute: String) {
    if (popBackStack(resultRoute, inclusive = false)) {
        return
    }

    val completedDestinationId = currentDestination?.id
    navigate(resultRoute) {
        if (completedDestinationId != null) {
            popUpTo(completedDestinationId) { inclusive = true }
        }
        launchSingleTop = true
    }
}
