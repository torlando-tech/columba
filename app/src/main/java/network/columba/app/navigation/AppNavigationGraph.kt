package network.columba.app.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/** Registers a production destination through the canonical [AppDestination] registry. */
fun NavGraphBuilder.appComposable(
    destination: AppDestination,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable(
        route = destination.routePattern,
        arguments = arguments,
        content = content,
    )
}
