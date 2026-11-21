package network.columba.app.ui.theme

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Edge-to-edge system bars that match the app's forced theme.
 *
 * The default `enableEdgeToEdge()` uses `SystemBarStyle.auto()`, which paints an
 * opaque scrim behind the bars on API 26-28 chosen from the *device* dark-mode
 * setting. The app can force a theme that differs from the device (settings
 * `ThemeMode`), so the scrim could sit under icons tinted from the app theme and
 * lose contrast - light navigation buttons on a light bar, for example.
 *
 * API 26+: transparent status and navigation bars over the themed Compose
 * content, with icon brightness applied from the app theme.
 *
 * API 24-25: API 24-25 cannot render dark navigation buttons at all, so a
 * transparent navigation bar would leave white buttons over light content in
 * the light theme. Those versions keep androidx's translucent dark scrim, which
 * keeps the white buttons legible on both light and dark app themes. The status
 * bar stays transparent because dark status-bar icons are available from API 23.
 */
fun ComponentActivity.enableTransparentEdgeToEdge() =
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
            } else {
                // Matches androidx's default dark scrim (0x801b1b1b).
                SystemBarStyle.dark(LEGACY_NAVIGATION_BAR_SCRIM)
            },
    )

private val LEGACY_NAVIGATION_BAR_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
