package network.columba.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import network.columba.app.ui.screens.CallDetailsScreen

internal fun NavGraphBuilder.callDetailsDestination(
    navController: NavHostController,
    content: @Composable (
        onBack: () -> Unit,
        onCallAgain: (String, Int, String) -> Unit,
        onViewPeer: (String) -> Unit,
    ) -> Unit = { onBack, onCallAgain, onViewPeer ->
        CallDetailsScreen(onBack = onBack, onCallAgain = onCallAgain, onViewPeer = onViewPeer)
    },
) {
    appComposable(
        AppDestination.CALL_DETAILS,
        arguments = listOf(navArgument("callAttemptId") { type = NavType.StringType }),
    ) {
        content(
            { navController.popBackStack() },
            { remoteIdentityHash, profileCode, localIdentityHash ->
                navController.navigate(callAgainRoute(remoteIdentityHash, profileCode, localIdentityHash))
            },
            { destinationHash -> navController.navigate("announce_detail/${android.net.Uri.encode(destinationHash)}") },
        )
    }
}
