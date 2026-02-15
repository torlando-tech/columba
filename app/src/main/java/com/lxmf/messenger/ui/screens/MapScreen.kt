package com.lxmf.messenger.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.lxmf.messenger.util.LocationCompat
import com.lxmf.messenger.map.MapStyleResult
import com.lxmf.messenger.map.MapTileSourceManager
import com.lxmf.messenger.ui.components.ContactLocationBottomSheet
import com.lxmf.messenger.ui.components.LocationPermissionBottomSheet
import com.lxmf.messenger.ui.components.ShareLocationBottomSheet
import com.lxmf.messenger.ui.components.SharingStatusChip
import com.lxmf.messenger.ui.util.MarkerBitmapFactory
import com.lxmf.messenger.util.LocationPermissionManager
import com.lxmf.messenger.viewmodel.ContactMarker
import com.lxmf.messenger.viewmodel.MapViewModel
import com.lxmf.messenger.viewmodel.MarkerState
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Empty MapLibre style JSON that shows a blank map with no tiles.
 * Used when HTTP map source is disabled and no offline maps are available.
 */
private const val EMPTY_MAP_STYLE = """
{
    "version": 8,
    "name": "Empty",
    "sources": {},
    "layers": [
        {
            "id": "background",
            "type": "background",
            "paint": {
                "background-color": "#f0f0f0"
            }
        }
    ]
}
"""

/**
 * Map screen displaying user location and contact markers.
 *
 * Phase 1 (MVP):
 * - Shows user's current location
 * - Displays contact markers at static test positions
 * - Location permission handling
 *
 * Phase 2+ will add:
 * - Real contact locations via LXMF telemetry
 * - Share location functionality
 * - Contact detail bottom sheets
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel = hiltViewModel(),
    onNavigateToConversation: (destinationHash: String) -> Unit = {},
    onNavigateToOfflineMaps: () -> Unit = {},
    onNavigateToRNodeWizardWithParams: (
        frequency: Long?,
        bandwidth: Int?,
        spreadingFactor: Int?,
        codingRate: Int?,
    ) -> Unit = { _, _, _, _ -> },
    // Optional focus location - if provided, map will center here with a marker
    focusLatitude: Double? = null,
    focusLongitude: Double? = null,
    focusLabel: String? = null,
    // Optional full interface details for bottom sheet
    focusInterfaceDetails: FocusInterfaceDetails? = null,
    // Permission UI state - managed by parent to survive tab switches (issue #342)
    permissionSheetDismissed: Boolean = false,
    onPermissionSheetDismissed: () -> Unit = {},
    permissionCardDismissed: Boolean = false,
    onPermissionCardDismissed: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsState()
    val contacts by viewModel.contacts.collectAsState()

    // Debug logging for permission state
    Log.d(
        "MapScreen",
        "State: hasLocationPermission=${state.hasLocationPermission}, permissionSheetDismissed=$permissionSheetDismissed, permissionCardDismissed=$permissionCardDismissed",
    )

    // Show permission sheet only if permission not granted and user hasn't dismissed it
    // permissionSheetDismissed is managed by parent (MainActivity) to survive tab switches (issue #342)
    val showPermissionSheet =
        !state.hasLocationPermission &&
            !permissionSheetDismissed
    var showShareLocationSheet by remember { mutableStateOf(false) }
    var selectedMarker by remember { mutableStateOf<ContactMarker?>(null) }
    var showFocusInterfaceSheet by remember { mutableStateOf(false) }
    val permissionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shareLocationSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val contactLocationSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Map state
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapStyleLoaded by remember { mutableStateOf(false) }
    var metersPerPixel by remember { mutableStateOf(1.0) }
    // Track current marker image IDs per contact to remove stale bitmaps on appearance change
    val markerImageIds = remember { mutableMapOf<String, String>() }

    // Helper function to set up map style after it loads
    // Extracted to avoid duplication between factory and LaunchedEffect callbacks
    fun onMapStyleLoaded(
        map: MapLibreMap,
        style: Style,
        ctx: Context,
        hasLocationPermission: Boolean,
    ) {
        val density = ctx.resources.displayMetrics.density

        // Add dashed circle bitmap for stale markers (if not already present)
        if (style.getImage("stale-dashed-circle") == null) {
            val staleCircleBitmap =
                MarkerBitmapFactory.createDashedCircle(
                    sizeDp = 28f,
                    strokeWidthDp = 3f,
                    color = android.graphics.Color.parseColor("#E0E0E0"),
                    dashLengthDp = 4f,
                    gapLengthDp = 3f,
                    density = density,
                )
            style.addImage("stale-dashed-circle", staleCircleBitmap)
            Log.d("MapScreen", "Added stale-dashed-circle image to style")
        }

        // Enable user location component (blue dot)
        if (hasLocationPermission) {
            @SuppressLint("MissingPermission")
            map.locationComponent.apply {
                activateLocationComponent(
                    LocationComponentActivationOptions
                        .builder(ctx, style)
                        .build(),
                )
                isLocationComponentEnabled = true
                cameraMode = CameraMode.NONE
                renderMode = RenderMode.COMPASS
            }
        }

        mapStyleLoaded = true
    }

    // Location client - only create GMS client when Play Services is available (issue #456)
    val useGms = remember { LocationCompat.isPlayServicesAvailable(context) }
    val fusedLocationClient = remember {
        if (useGms) LocationServices.getFusedLocationProviderClient(context) else null
    }

    // Permission launcher
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            val granted = permissions.values.all { it }
            viewModel.onPermissionResult(granted)
            if (granted) {
                startLocationUpdates(context, fusedLocationClient, useGms, viewModel)
            }
        }

    // Check permissions on first launch
    LaunchedEffect(Unit) {
        MapLibre.getInstance(context)
        if (LocationPermissionManager.hasPermission(context)) {
            viewModel.onPermissionResult(true)
            startLocationUpdates(context, fusedLocationClient, useGms, viewModel)
        }
        // Permission sheet visibility is now managed by ViewModel state
    }

    // Center map on user location once when both map and location are ready
    // Key on both so we catch whichever becomes available last, but only center once
    var hasInitiallyCentered by remember { mutableStateOf(false) }

    // If focus coordinates are provided, center on them instead of user location
    LaunchedEffect(mapLibreMap, focusLatitude, focusLongitude) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val hasFocusCoordinates = focusLatitude != null && focusLongitude != null
        if (!hasInitiallyCentered && hasFocusCoordinates) {
            val cameraPosition =
                CameraPosition
                    .Builder()
                    .target(LatLng(focusLatitude!!, focusLongitude!!))
                    .zoom(14.0)
                    .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
            hasInitiallyCentered = true
        }
    }

    // Fall back to user location if no focus coordinates
    LaunchedEffect(mapLibreMap, state.userLocation != null) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val location = state.userLocation ?: return@LaunchedEffect
        if (!hasInitiallyCentered && focusLatitude == null) {
            val cameraPosition =
                CameraPosition
                    .Builder()
                    .target(LatLng(location.latitude, location.longitude))
                    .zoom(15.0)
                    .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
            hasInitiallyCentered = true
        }
    }

    // Enable location component when permission is granted
    @SuppressLint("MissingPermission")
    LaunchedEffect(state.hasLocationPermission, mapLibreMap) {
        if (state.hasLocationPermission) {
            mapLibreMap?.let { map ->
                map.style?.let { style ->
                    map.locationComponent.apply {
                        if (!isLocationComponentActivated) {
                            activateLocationComponent(
                                LocationComponentActivationOptions
                                    .builder(context, style)
                                    .build(),
                            )
                        }
                        isLocationComponentEnabled = true
                        cameraMode = CameraMode.NONE
                        renderMode = RenderMode.COMPASS
                    }
                }
            }
        }
    }

    // Reload map style when mapStyleResult changes (e.g., after offline map download or settings change)
    // Also triggers on tab restoration when mapLibreMap is restored
    LaunchedEffect(state.mapStyleResult, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val styleResult = state.mapStyleResult ?: return@LaunchedEffect

        // Control MapLibre's network connectivity based on style result
        // When Offline or Unavailable, prevent any network requests for tiles
        val allowNetwork = styleResult is MapStyleResult.Online || styleResult is MapStyleResult.Rmsp
        MapLibre.setConnected(allowNetwork)
        Log.d("MapScreen", "MapLibre network connectivity: $allowNetwork")

        val styleBuilder =
            when (styleResult) {
                is MapStyleResult.Online -> Style.Builder().fromUri(styleResult.styleUrl)
                is MapStyleResult.Offline -> Style.Builder().fromUri(styleResult.styleUrl)
                is MapStyleResult.OfflineWithLocalStyle -> {
                    try {
                        val styleJson = java.io.File(styleResult.localStylePath).readText()
                        Style.Builder().fromJson(styleJson)
                    } catch (e: Exception) {
                        Log.e("MapScreen", "Failed to read cached style JSON, falling back to HTTP", e)
                        Style.Builder().fromUri(MapTileSourceManager.DEFAULT_STYLE_URL)
                    }
                }
                is MapStyleResult.Rmsp -> Style.Builder().fromUri(MapTileSourceManager.DEFAULT_STYLE_URL)
                is MapStyleResult.Unavailable -> {
                    // Set an empty style to clear the map - don't load HTTP tiles
                    Log.d("MapScreen", "Style unavailable: ${styleResult.reason}, clearing map")
                    Style.Builder().fromJson(EMPTY_MAP_STYLE)
                }
            }
        Log.d("MapScreen", "Applying style: ${styleResult.javaClass.simpleName}")
        // Reset mapStyleLoaded before applying new style
        mapStyleLoaded = false
        map.setStyle(styleBuilder) { style ->
            Log.d("MapScreen", "Style loaded (from LaunchedEffect): ${styleResult.javaClass.simpleName}")
            onMapStyleLoaded(map, style, context, state.hasLocationPermission)
        }
    }

    // Proper MapView lifecycle management
    // This ensures the map survives tab switches while properly handling lifecycle events
    DisposableEffect(lifecycleOwner, mapView) {
        val observer =
            LifecycleEventObserver { _, event ->
                val view = mapView ?: return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_START -> view.onStart()
                    Lifecycle.Event.ON_RESUME -> view.onResume()
                    Lifecycle.Event.ON_PAUSE -> view.onPause()
                    Lifecycle.Event.ON_STOP -> view.onStop()
                    Lifecycle.Event.ON_DESTROY -> {
                        // Disable location component before destroying map to prevent crashes
                        mapLibreMap?.locationComponent?.let { locationComponent ->
                            if (locationComponent.isLocationComponentActivated) {
                                locationComponent.isLocationComponentEnabled = false
                            }
                        }
                        view.onDestroy()
                    }
                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Edge-to-edge layout with map filling entire screen
    Box(modifier = Modifier.fillMaxSize()) {
        // MapLibre MapView - fills entire screen (edge-to-edge)
        AndroidView(
            factory = { ctx ->
                // Initialize MapLibre before creating MapView
                MapLibre.getInstance(ctx)
                MapView(ctx).apply {
                    mapView = this

                    getMapAsync { map ->
                        mapLibreMap = map

                        // Enable attribution (required for OpenFreeMap/OSM license compliance)
                        // Position in bottom-left to avoid conflict with FABs on right
                        // Use post to ensure view is laid out and we can get proper insets
                        this.post {
                            val density = ctx.resources.displayMetrics.density
                            val marginPx = (8 * density).toInt()
                            val insets = ViewCompat.getRootWindowInsets(this)
                            val navBarHeight = insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
                            // 100dp for app bottom nav bar + system nav bar height
                            val bottomMarginPx = (100 * density).toInt() + navBarHeight
                            map.uiSettings.isAttributionEnabled = true
                            map.uiSettings.setAttributionGravity(Gravity.BOTTOM or Gravity.START)
                            map.uiSettings.setAttributionMargins(marginPx, 0, 0, bottomMarginPx)
                        }

                        // Load map style based on settings (offline, HTTP, or RMSP)
                        // When Unavailable, load an empty style to clear the map
                        val styleResult = state.mapStyleResult

                        // Control MapLibre's network connectivity based on style result
                        // When Offline or Unavailable, prevent any network requests for tiles
                        val allowNetwork =
                            styleResult is MapStyleResult.Online ||
                                styleResult is MapStyleResult.Rmsp ||
                                styleResult == null // Default to online if not yet resolved
                        MapLibre.setConnected(allowNetwork)
                        Log.d("MapScreen", "Initial MapLibre network connectivity: $allowNetwork")

                        val styleBuilder =
                            when (styleResult) {
                                is MapStyleResult.Online -> Style.Builder().fromUri(styleResult.styleUrl)
                                is MapStyleResult.Offline -> Style.Builder().fromUri(styleResult.styleUrl)
                                is MapStyleResult.OfflineWithLocalStyle -> {
                                    try {
                                        val styleJson = java.io.File(styleResult.localStylePath).readText()
                                        Style.Builder().fromJson(styleJson)
                                    } catch (e: Exception) {
                                        Log.e("MapScreen", "Failed to read cached style JSON, falling back to HTTP", e)
                                        Style.Builder().fromUri(MapTileSourceManager.DEFAULT_STYLE_URL)
                                    }
                                }
                                is MapStyleResult.Rmsp -> {
                                    // For RMSP, use default HTTP as fallback (RMSP rendering not yet implemented)
                                    Log.d("MapScreen", "RMSP style requested, using HTTP fallback")
                                    Style.Builder().fromUri(MapTileSourceManager.DEFAULT_STYLE_URL)
                                }
                                is MapStyleResult.Unavailable -> {
                                    Log.w("MapScreen", "No map source available: ${styleResult.reason}")
                                    // Load empty style to clear the map
                                    Style.Builder().fromJson(EMPTY_MAP_STYLE)
                                }
                                null -> Style.Builder().fromUri(MapTileSourceManager.DEFAULT_STYLE_URL)
                            }
                        map.setStyle(styleBuilder) { style ->
                            Log.d("MapScreen", "Map style loaded: ${styleResult?.javaClass?.simpleName ?: "default"}")
                            onMapStyleLoaded(map, style, ctx, state.hasLocationPermission)
                        }

                        // Add click listener for contact markers and focus marker
                        map.addOnMapClickListener { latLng ->
                            val screenPoint = map.projection.toScreenLocation(latLng)

                            // Check for focus marker click first
                            val focusFeatures =
                                map.queryRenderedFeatures(
                                    screenPoint,
                                    "focus-marker-layer",
                                )
                            if (focusFeatures.isNotEmpty() && focusInterfaceDetails != null) {
                                showFocusInterfaceSheet = true
                                Log.d("MapScreen", "Focus marker tapped: ${focusInterfaceDetails.name}")
                                return@addOnMapClickListener true
                            }

                            // Check for contact marker click
                            val features =
                                map.queryRenderedFeatures(
                                    screenPoint,
                                    "contact-markers-layer",
                                )
                            features.firstOrNull()?.let { feature ->
                                val hash = feature.getStringProperty("hash")
                                if (hash != null) {
                                    val marker =
                                        state.contactMarkers.find {
                                            it.destinationHash == hash
                                        }
                                    if (marker != null) {
                                        selectedMarker = marker
                                        Log.d("MapScreen", "Marker tapped: ${marker.destinationHash.take(16)}")
                                    }
                                }
                            }
                            true
                        }

                        // Set initial camera position (use last known location if available)
                        val initialLat = state.userLocation?.latitude ?: 37.7749
                        val initialLng = state.userLocation?.longitude ?: -122.4194
                        val initialPosition =
                            CameraPosition
                                .Builder()
                                .target(LatLng(initialLat, initialLng))
                                .zoom(if (state.userLocation != null) 15.0 else 12.0)
                                .build()
                        map.cameraPosition = initialPosition
                        metersPerPixel = map.projection.getMetersPerPixelAtLatitude(initialLat)

                        // Add camera move listener to update scale bar
                        // Measure actual distance between two screen points for accuracy
                        map.addOnCameraMoveListener {
                            val centerX = map.width / 2f
                            val centerY = map.height / 2f
                            val point1 = android.graphics.PointF(centerX - 50f, centerY)
                            val point2 = android.graphics.PointF(centerX + 50f, centerY)
                            val latLng1 = map.projection.fromScreenLocation(point1)
                            val latLng2 = map.projection.fromScreenLocation(point2)
                            val distance = latLng1.distanceTo(latLng2) // meters
                            metersPerPixel = distance / 100.0 // 100 pixels between points
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Update contact markers on the map when they change
        LaunchedEffect(state.contactMarkers, mapStyleLoaded) {
            if (!mapStyleLoaded) return@LaunchedEffect
            val map = mapLibreMap ?: return@LaunchedEffect
            val style = map.style ?: return@LaunchedEffect

            val sourceId = "contact-markers-source"
            val layerId = "contact-markers-layer"
            val screenDensity = context.resources.displayMetrics.density

            // Generate and register marker bitmaps for each contact
            val currentImageIds = mutableSetOf<String>()
            state.contactMarkers.forEach { marker ->
                // Include icon identity in cache key so bitmap refreshes when appearance changes
                val iconKey = "${marker.iconName}-${marker.iconForegroundColor}-${marker.iconBackgroundColor}"
                val imageId = "marker-${marker.destinationHash}-${iconKey.hashCode()}"
                currentImageIds.add(imageId)
                // Remove previous bitmap for this contact if appearance changed
                val previousId = markerImageIds[marker.destinationHash]
                if (previousId != null && previousId != imageId) {
                    style.removeImage(previousId)
                }
                markerImageIds[marker.destinationHash] = imageId
                if (style.getImage(imageId) == null) {
                    // Try profile icon first, fall back to initials
                    val bitmap =
                        if (marker.iconName != null &&
                            marker.iconForegroundColor != null &&
                            marker.iconBackgroundColor != null
                        ) {
                            MarkerBitmapFactory.createProfileIconMarker(
                                iconName = marker.iconName,
                                foregroundColor = marker.iconForegroundColor,
                                backgroundColor = marker.iconBackgroundColor,
                                displayName = marker.displayName,
                                density = screenDensity,
                                context = context,
                            )
                        } else {
                            null
                        } ?: run {
                            // Fallback to initials marker
                            val initial = marker.displayName.firstOrNull() ?: '?'
                            val color = MarkerBitmapFactory.hashToColor(marker.destinationHash)
                            MarkerBitmapFactory.createInitialMarker(
                                initial = initial,
                                displayName = marker.displayName,
                                backgroundColor = color,
                                density = screenDensity,
                            )
                        }
                    style.addImage(imageId, bitmap)
                }
            }

            // Create GeoJSON features from contact markers with state and approximateRadius properties
            val features =
                state.contactMarkers.map { marker ->
                    val iconKey = "${marker.iconName}-${marker.iconForegroundColor}-${marker.iconBackgroundColor}"
                    val imageId = "marker-${marker.destinationHash}-${iconKey.hashCode()}"
                    Feature
                        .fromGeometry(
                            Point.fromLngLat(marker.longitude, marker.latitude),
                        ).apply {
                            addStringProperty("name", marker.displayName)
                            addStringProperty("hash", marker.destinationHash)
                            addStringProperty("imageId", imageId) // Pre-computed image ID
                            addStringProperty("state", marker.state.name) // FRESH, STALE, or EXPIRED_GRACE_PERIOD
                            addNumberProperty("approximateRadius", marker.approximateRadius) // meters, 0 = precise
                        }
                }
            val featureCollection = FeatureCollection.fromFeatures(features)

            // Update or create the source
            val existingSource = style.getSourceAs<GeoJsonSource>(sourceId)
            if (existingSource != null) {
                Log.d("MapScreen", "Updating existing source with ${features.size} features")
                existingSource.setGeoJson(featureCollection)
            } else {
                Log.d("MapScreen", "Creating new source and layers with ${features.size} features")
                // Add new source and layers with data-driven styling based on marker state
                style.addSource(GeoJsonSource(sourceId, featureCollection))

                // Uncertainty circle layer for approximate locations (rendered behind main marker)
                // Only visible when approximateRadius > 0
                val uncertaintyLayerId = "contact-markers-uncertainty-layer"
                style.addLayer(
                    CircleLayer(uncertaintyLayerId, sourceId)
                        .withProperties(
                            // Circle radius scales with zoom - converts meters to screen pixels
                            // At zoom 15, 1 pixel ≈ 1 meter, so we scale accordingly
                            PropertyFactory.circleRadius(
                                Expression.interpolate(
                                    Expression.linear(),
                                    Expression.zoom(),
                                    // At lower zooms, show smaller radius (it's farther out)
                                    // Continue shrinking below zoom 10 so it doesn't stay constant
                                    Expression.stop(2, Expression.division(Expression.get("approximateRadius"), Expression.literal(500))),
                                    Expression.stop(5, Expression.division(Expression.get("approximateRadius"), Expression.literal(200))),
                                    Expression.stop(8, Expression.division(Expression.get("approximateRadius"), Expression.literal(60))),
                                    Expression.stop(10, Expression.division(Expression.get("approximateRadius"), Expression.literal(30))),
                                    Expression.stop(12, Expression.division(Expression.get("approximateRadius"), Expression.literal(10))),
                                    Expression.stop(15, Expression.division(Expression.get("approximateRadius"), Expression.literal(3))),
                                    Expression.stop(18, Expression.product(Expression.get("approximateRadius"), Expression.literal(0.8))),
                                ),
                            ),
                            // Semi-transparent fill (Orange)
                            PropertyFactory.circleColor(
                                Expression.color(android.graphics.Color.parseColor("#FF5722")),
                            ),
                            PropertyFactory.circleOpacity(
                                Expression.literal(0.15f),
                            ),
                            // Dashed stroke for the uncertainty boundary
                            PropertyFactory.circleStrokeWidth(
                                Expression.literal(2f),
                            ),
                            // Orange stroke
                            PropertyFactory.circleStrokeColor(
                                Expression.color(android.graphics.Color.parseColor("#FF5722")),
                            ),
                            PropertyFactory.circleStrokeOpacity(
                                Expression.literal(0.4f),
                            ),
                        ).withFilter(
                            // Only show for locations with approximateRadius > 0
                            Expression.gt(Expression.get("approximateRadius"), Expression.literal(0)),
                        ),
                )

                // SymbolLayer for custom marker icons (colored circle with initial + name)
                // Name is baked into bitmap to avoid font loading issues with tile server
                style.addLayer(
                    SymbolLayer(layerId, sourceId).withProperties(
                        PropertyFactory.iconImage(Expression.get("imageId")),
                        // Anchor at top (circle center)
                        PropertyFactory.iconAnchor("top"),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconSize(1f),
                        // Opacity based on marker state
                        PropertyFactory.iconOpacity(
                            Expression.match(
                                Expression.get("state"),
                                Expression.literal(MarkerState.FRESH.name),
                                Expression.literal(1.0f),
                                Expression.literal(MarkerState.STALE.name),
                                Expression.literal(0.5f),
                                Expression.literal(MarkerState.EXPIRED_GRACE_PERIOD.name),
                                Expression.literal(0.4f),
                                // Default
                                Expression.literal(1.0f),
                            ),
                        ),
                    ),
                )
            }
        }

        // Add focus marker for discovered interface location (if provided)
        LaunchedEffect(focusLatitude, focusLongitude, focusLabel, mapStyleLoaded) {
            if (!mapStyleLoaded) return@LaunchedEffect
            if (focusLatitude == null || focusLongitude == null) return@LaunchedEffect
            val map = mapLibreMap ?: return@LaunchedEffect
            val style = map.style ?: return@LaunchedEffect

            val sourceId = "focus-marker-source"
            val layerId = "focus-marker-layer"
            val screenDensity = context.resources.displayMetrics.density

            // Create a marker bitmap for the focus location
            val imageId = "focus-marker-image"
            if (style.getImage(imageId) == null) {
                val label = focusLabel ?: "Location"
                val initial = label.firstOrNull() ?: 'L'
                val bitmap =
                    MarkerBitmapFactory.createInitialMarker(
                        initial = initial,
                        displayName = label,
                        backgroundColor = android.graphics.Color.parseColor("#E91E63"), // Pink/Magenta for visibility
                        density = screenDensity,
                    )
                style.addImage(imageId, bitmap)
            }

            // Create GeoJSON feature for the focus marker
            val feature =
                Feature
                    .fromGeometry(
                        Point.fromLngLat(focusLongitude, focusLatitude),
                    ).apply {
                        addStringProperty("name", focusLabel ?: "Location")
                        addStringProperty("imageId", imageId)
                    }
            val featureCollection = FeatureCollection.fromFeatures(listOf(feature))

            // Update or create the source
            val existingSource = style.getSourceAs<GeoJsonSource>(sourceId)
            if (existingSource != null) {
                existingSource.setGeoJson(featureCollection)
            } else {
                style.addSource(GeoJsonSource(sourceId, featureCollection))

                // Add symbol layer for the focus marker
                style.addLayer(
                    SymbolLayer(layerId, sourceId).withProperties(
                        PropertyFactory.iconImage(Expression.get("imageId")),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    ),
                )
            }
            Log.d("MapScreen", "Added focus marker at $focusLatitude, $focusLongitude for $focusLabel")
        }

        // Gradient scrim behind TopAppBar for readability
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    Color.Black.copy(alpha = 0.4f),
                                    Color.Transparent,
                                ),
                        ),
                    ).align(Alignment.TopStart),
        )

        // TopAppBar overlays map (transparent background)
        TopAppBar(
            title = {
                Text(
                    text = "Map",
                    color = Color.White,
                )
            },
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            modifier =
                Modifier
                    .statusBarsPadding()
                    .align(Alignment.TopStart),
        )

        // Sharing status chip (shown when actively sharing)
        if (state.isSharing && state.activeSessions.isNotEmpty()) {
            SharingStatusChip(
                sharingWithCount = state.activeSessions.size,
                onStopAllClick = { viewModel.stopSharing() },
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 56.dp),
                // Below TopAppBar
            )
        }

        // Scale bar (bottom right, next to My Location button)
        ScaleBar(
            metersPerPixel = metersPerPixel,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 72.dp, bottom = 172.dp),
            // Left of My Location button
        )

        // FABs positioned above navigation bar
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    // Account for bottom navigation bar
                    .padding(bottom = 80.dp),
        ) {
            // Offline Maps button
            SmallFloatingActionButton(
                onClick = onNavigateToOfflineMaps,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Icon(Icons.Default.Download, contentDescription = "Offline Maps")
            }

            // My Location button
            SmallFloatingActionButton(
                onClick = {
                    if (!state.hasLocationPermission) {
                        // Request permission directly when user explicitly clicks My Location
                        permissionLauncher.launch(
                            LocationPermissionManager.getRequiredPermissions().toTypedArray(),
                        )
                    } else {
                        state.userLocation?.let { location ->
                            mapLibreMap?.let { map ->
                                val cameraPosition =
                                    CameraPosition
                                        .Builder()
                                        .target(LatLng(location.latitude, location.longitude))
                                        .zoom(15.0)
                                        .build()
                                map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
                            }
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "My location")
            }

            // Bottom row: optional Send/Request Now + Share/Stop Location
            // Group telemetry sending counts as "sharing" for the Stop button
            val isAnySharingActive = state.isSharing || state.isTelemetrySendEnabled
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Send Now button (only when collector configured AND send enabled)
                if (state.collectorAddress != null && state.isTelemetrySendEnabled) {
                    SmallFloatingActionButton(
                        onClick = {
                            if (!LocationPermissionManager.hasPermission(context)) {
                                permissionLauncher.launch(
                                    LocationPermissionManager.getRequiredPermissions().toTypedArray(),
                                )
                            } else {
                                viewModel.sendTelemetryNow()
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        if (state.isSendingTelemetry) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        } else {
                            Icon(Icons.Default.Send, contentDescription = "Send Now")
                        }
                    }
                }

                // Request Now button (only when collector configured AND request enabled)
                if (state.collectorAddress != null && state.isTelemetryRequestEnabled) {
                    SmallFloatingActionButton(
                        onClick = { viewModel.requestTelemetryNow() },
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) {
                        if (state.isRequestingTelemetry) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        } else {
                            Icon(Icons.Default.CloudDownload, contentDescription = "Request Now")
                        }
                    }
                }

                // Share/Stop Location button
                ExtendedFloatingActionButton(
                    onClick = {
                        if (isAnySharingActive) {
                            viewModel.stopSharing()
                        } else {
                            showShareLocationSheet = true
                        }
                    },
                    icon = {
                        Icon(
                            if (isAnySharingActive) Icons.Default.Stop else Icons.Default.ShareLocation,
                            contentDescription = null,
                        )
                    },
                    text = { Text(if (isAnySharingActive) "Stop Sharing" else "Share Location") },
                    containerColor =
                        if (isAnySharingActive) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                    contentColor =
                        if (isAnySharingActive) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        },
                )
            }
        }

        // Contact markers are shown directly on the map as circles
        // Tap a marker to open the contact detail bottom sheet

        // Show hint card if no location permission and not dismissed
        // permissionCardDismissed is managed by parent (MainActivity) to survive tab switches (issue #342)
        if (!state.hasLocationPermission && !permissionCardDismissed) {
            EmptyMapStateCard(
                contactCount = state.contactMarkers.size,
                onDismiss = {
                    onPermissionCardDismissed()
                    viewModel.dismissPermissionCard()
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(16.dp)
                        // Above FABs and nav bar
                        .padding(bottom = 180.dp),
            )
        }

        // Show overlay when no map source is available (HTTP disabled, no offline maps)
        // Track dismissal locally - resets when map style changes
        var isOverlayDismissed by remember(state.mapStyleResult) { mutableStateOf(false) }
        if (state.mapStyleResult is MapStyleResult.Unavailable && !isOverlayDismissed) {
            NoMapSourceOverlay(
                reason = (state.mapStyleResult as MapStyleResult.Unavailable).reason,
                onEnableHttp = { viewModel.enableHttp() },
                onNavigateToOfflineMaps = onNavigateToOfflineMaps,
                onDismiss = { isOverlayDismissed = true },
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
            )
        }

        // Loading indicator
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Loading map...",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }

    // Permission bottom sheet
    if (showPermissionSheet) {
        LocationPermissionBottomSheet(
            onDismiss = {
                onPermissionSheetDismissed()
                viewModel.dismissLocationPermissionSheet()
            },
            onRequestPermissions = {
                onPermissionSheetDismissed()
                viewModel.dismissLocationPermissionSheet()
                permissionLauncher.launch(
                    LocationPermissionManager.getRequiredPermissions().toTypedArray(),
                )
            },
            sheetState = permissionSheetState,
        )
    }

    // Share location bottom sheet
    if (showShareLocationSheet) {
        ShareLocationBottomSheet(
            contacts = contacts,
            onDismiss = { showShareLocationSheet = false },
            onStartSharing = { selectedContacts, duration ->
                viewModel.startSharing(selectedContacts, duration)
                showShareLocationSheet = false
            },
            sheetState = shareLocationSheetState,
        )
    }

    // Contact location bottom sheet (shown when marker is tapped)
    selectedMarker?.let { marker ->
        ContactLocationBottomSheet(
            marker = marker,
            userLocation = state.userLocation,
            onDismiss = { selectedMarker = null },
            onSendMessage = {
                onNavigateToConversation(marker.destinationHash)
                selectedMarker = null
            },
            onRemoveMarker = {
                viewModel.deleteMarker(marker.destinationHash)
                selectedMarker = null
            },
            sheetState = contactLocationSheetState,
        )
    }

    // Bottom sheet for focus interface details (discovered interface)
    if (showFocusInterfaceSheet && focusInterfaceDetails != null) {
        FocusInterfaceBottomSheet(
            details = focusInterfaceDetails,
            onDismiss = { showFocusInterfaceSheet = false },
            onCopyLoraParams = {
                val params = formatLoraParamsForClipboard(focusInterfaceDetails)
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("LoRa Parameters", params)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "LoRa parameters copied", Toast.LENGTH_SHORT).show()
            },
            onUseForNewRNode = {
                showFocusInterfaceSheet = false
                onNavigateToRNodeWizardWithParams(
                    focusInterfaceDetails.frequency,
                    focusInterfaceDetails.bandwidth,
                    focusInterfaceDetails.spreadingFactor,
                    focusInterfaceDetails.codingRate,
                )
            },
        )
    }
}

/**
 * Bottom sheet showing discovered interface details when the focus marker is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusInterfaceBottomSheet(
    details: FocusInterfaceDetails,
    onDismiss: () -> Unit,
    onCopyLoraParams: () -> Unit,
    onUseForNewRNode: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        FocusInterfaceContent(
            details = details,
            onCopyLoraParams = onCopyLoraParams,
            onUseForNewRNode = onUseForNewRNode,
        )
    }
}

/**
 * Content for the focus interface bottom sheet.
 * Extracted for testability since ModalBottomSheet is difficult to test in Robolectric.
 */
@Composable
internal fun FocusInterfaceContent(
    details: FocusInterfaceDetails,
    onCopyLoraParams: () -> Unit = {},
    onUseForNewRNode: () -> Unit = {},
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header with name and type
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = details.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = details.type,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            details.status?.let { status ->
                Surface(
                    color =
                        when (status.lowercase()) {
                            "available" -> MaterialTheme.colorScheme.primaryContainer
                            "unknown" -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        text = status.replaceFirstChar { it.uppercase() },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color =
                            when (status.lowercase()) {
                                "available" -> MaterialTheme.colorScheme.onPrimaryContainer
                                "unknown" -> MaterialTheme.colorScheme.onTertiaryContainer
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }

        HorizontalDivider()

        // Location info
        InterfaceDetailRow(
            label = "Location",
            value = "%.4f, %.4f".format(details.latitude, details.longitude),
        )
        details.height?.let { height ->
            InterfaceDetailRow(
                label = "Altitude",
                value = "${height.toInt()} m",
            )
        }

        // Radio parameters (if LoRa interface)
        if (details.frequency != null) {
            HorizontalDivider()
            Text(
                text = "Radio Parameters",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            InterfaceDetailRow(
                label = "Frequency",
                value = "%.3f MHz".format(details.frequency / 1_000_000.0),
            )
            details.bandwidth?.let { bw ->
                InterfaceDetailRow(
                    label = "Bandwidth",
                    value = "$bw kHz",
                )
            }
            details.spreadingFactor?.let { sf ->
                InterfaceDetailRow(
                    label = "Spreading Factor",
                    value = "SF$sf",
                )
            }
            details.codingRate?.let { cr ->
                InterfaceDetailRow(
                    label = "Coding Rate",
                    value = "4/$cr",
                )
            }
            details.modulation?.let { mod ->
                InterfaceDetailRow(
                    label = "Modulation",
                    value = mod,
                )
            }
        }

        // TCP parameters (if TCP interface)
        if (details.reachableOn != null) {
            HorizontalDivider()
            Text(
                text = "Network",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            InterfaceDetailRow(
                label = "Host",
                value = details.reachableOn,
            )
            details.port?.let { port ->
                InterfaceDetailRow(
                    label = "Port",
                    value = port.toString(),
                )
            }
        }

        // Status details
        if (details.lastHeard != null || details.hops != null) {
            HorizontalDivider()
            Text(
                text = "Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            details.lastHeard?.let { timestamp ->
                val timeAgo = formatTimeAgo(timestamp)
                InterfaceDetailRow(
                    label = "Last Heard",
                    value = timeAgo,
                )
            }
            details.hops?.let { hops ->
                InterfaceDetailRow(
                    label = "Hops",
                    value = hops.toString(),
                )
            }
        }

        // LoRa params buttons (only for radio interfaces with frequency info)
        if (details.frequency != null) {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Copy button
                OutlinedButton(
                    onClick = onCopyLoraParams,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Params")
                }
                // Use for New RNode button
                Button(
                    onClick = onUseForNewRNode,
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Use for RNode")
                }
            }
        }
    }
}

/**
 * Format LoRa parameters for clipboard.
 */
internal fun formatLoraParamsForClipboard(details: FocusInterfaceDetails): String =
    buildString {
        appendLine("LoRa Parameters from: ${details.name}")
        appendLine("---")
        details.frequency?.let { freq ->
            appendLine("Frequency: ${freq / 1_000_000.0} MHz")
        }
        details.bandwidth?.let { bw ->
            appendLine("Bandwidth: ${bw / 1000} kHz")
        }
        details.spreadingFactor?.let { sf ->
            appendLine("Spreading Factor: SF$sf")
        }
        details.codingRate?.let { cr ->
            appendLine("Coding Rate: 4/$cr")
        }
        details.modulation?.let { mod ->
            appendLine("Modulation: $mod")
        }
    }.trim()

@Composable
internal fun InterfaceDetailRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

internal fun formatTimeAgo(timestamp: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - timestamp
    return when {
        diff < 60 -> "Just now"
        diff < 3600 -> "${diff / 60} min ago"
        diff < 86400 -> "${diff / 3600} hours ago"
        else -> "${diff / 86400} days ago"
    }
}

/**
 * Card shown when location permission is not granted.
 * Can be dismissed by the user via the close button.
 */
@Composable
internal fun EmptyMapStateCard(
    @Suppress("UNUSED_PARAMETER") contactCount: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Box {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .padding(end = 32.dp),
                // Leave space for dismiss button
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Location permission required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Enable location access to see your position on the map.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Overlay shown when no map source is available (HTTP disabled, no offline maps covering area).
 * Provides options to enable HTTP or download offline maps, and can be dismissed.
 */
@Composable
internal fun NoMapSourceOverlay(
    reason: String,
    onEnableHttp: () -> Unit,
    onNavigateToOfflineMaps: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Box {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .padding(top = 8.dp), // Extra top padding for close button
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No Map Source Enabled",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onEnableHttp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Enable HTTP Map Source")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "or",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.TextButton(
                    onClick = onNavigateToOfflineMaps,
                ) {
                    Text("Download Offline Maps First")
                }
            }
            // Close button in top-right corner
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Start location updates using FusedLocationProviderClient when available,
 * falling back to Android LocationManager when Google Play Services is not installed (issue #456).
 */
@SuppressLint("MissingPermission")
private fun startLocationUpdates(
    context: Context,
    fusedLocationClient: FusedLocationProviderClient?,
    useGms: Boolean,
    viewModel: MapViewModel,
) {
    if (useGms && fusedLocationClient != null) {
        val locationRequest =
            LocationRequest
                .Builder(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    // 30 seconds
                    30_000L,
                ).apply {
                    setMinUpdateIntervalMillis(15_000L) // min interval
                    setMaxUpdateDelayMillis(60_000L) // max delay
                }.build()

        val locationCallback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        viewModel.updateUserLocation(location)
                    }
                }
            }

        try {
            // Get last known location first for faster initial display
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let { viewModel.updateUserLocation(it) }
            }

            // Then start continuous updates
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper(),
            )
        } catch (e: SecurityException) {
            Log.e("MapScreen", "Location permission not granted", e)
        }
    } else {
        // Fallback to platform LocationManager
        try {
            LocationCompat.getLastKnownLocation(context)?.let { location ->
                viewModel.updateUserLocation(location)
            }
            LocationCompat.requestLocationUpdates(context, 30_000L) { location ->
                viewModel.updateUserLocation(location)
            }
        } catch (e: SecurityException) {
            Log.e("MapScreen", "Location permission not granted", e)
        }
    }
}

/**
 * Scale bar showing distance measurement that updates with zoom level.
 *
 * @param metersPerPixel Meters per screen pixel from MapLibre projection
 * @param modifier Modifier for positioning
 */
@Composable
internal fun ScaleBar(
    metersPerPixel: Double,
    modifier: Modifier = Modifier,
) {
    // Target bar width in dp
    val minBarWidthDp = 80f
    val maxBarWidthDp = 140f
    // Calculate meters range for the target bar widths
    val density = LocalDensity.current.density
    val minBarWidthPx = minBarWidthDp * density
    val maxBarWidthPx = maxBarWidthDp * density
    val minMeters = metersPerPixel * minBarWidthPx
    val maxMeters = metersPerPixel * maxBarWidthPx

    // Nice distance values in meters (up to 10,000 km for very zoomed out views)
    val niceDistances =
        listOf(
            5,
            10,
            20,
            50,
            100,
            200,
            500,
            1_000,
            2_000,
            5_000,
            10_000,
            20_000,
            50_000,
            100_000,
            200_000,
            500_000,
            1_000_000,
            2_000_000,
            5_000_000,
            10_000_000,
        )

    // Find the best nice distance that fits in our range
    // Fall back to the largest distance if zoomed out extremely far
    val selectedDistance =
        niceDistances.findLast { it >= minMeters && it <= maxMeters }
            ?: niceDistances.firstOrNull { it >= minMeters }
            ?: niceDistances.last()

    // Calculate bar width in pixels
    val barWidthPx = (selectedDistance / metersPerPixel).toFloat()

    // Format the distance text
    val distanceText =
        when {
            selectedDistance >= 1_000_000 -> "${selectedDistance / 1_000_000} km"
            selectedDistance >= 1_000 -> "${selectedDistance / 1_000} km"
            else -> "$selectedDistance m"
        }

    // Google Maps style scale bar: |——————| with text
    val barWidth = with(LocalDensity.current) { barWidthPx.toDp() }
    val lineColor = Color.Black
    val lineThickness = 2.dp
    val endCapHeight = 8.dp

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
    ) {
        // Distance text with shadow for readability
        Text(
            text = distanceText,
            style = MaterialTheme.typography.labelSmall,
            color = lineColor,
            modifier =
                Modifier
                    .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
        )
        Spacer(modifier = Modifier.height(2.dp))
        // Scale bar with end caps: |——————|
        Box(
            modifier =
                Modifier
                    .width(barWidth)
                    .height(endCapHeight),
        ) {
            // Left end cap
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(lineThickness)
                        .fillMaxHeight()
                        .background(lineColor),
            )
            // Horizontal line
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(lineThickness)
                        .background(lineColor),
            )
            // Right end cap
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(lineThickness)
                        .fillMaxHeight()
                        .background(lineColor),
            )
        }
    }
}
