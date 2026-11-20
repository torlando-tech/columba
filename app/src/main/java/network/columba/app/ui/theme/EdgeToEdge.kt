package network.columba.app.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Edge-to-edge with fully transparent system bars on every API level.
 *
 * The default `enableEdgeToEdge()` uses `SystemBarStyle.auto()`, which paints an
 * opaque scrim behind the bars on API 26-28 chosen from the *device* dark-mode
 * setting. The app can force a theme that differs from the device (settings
 * `ThemeMode`), so the scrim could sit under icons tinted from the app theme and
 * lose contrast - light navigation buttons on a light bar, for example.
 * Transparent scrims keep the bars over the themed Compose content on all API
 * levels, matching the icon brightness applied from the app theme.
 */
fun ComponentActivity.enableTransparentEdgeToEdge() =
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
    )
