package com.lxmf.messenger.ui.screens

import android.annotation.SuppressLint
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

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
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val contacts by viewModel.contacts.collectAsState()

    // Show permission sheet only if permission not granted and user hasn't dismissed it
    val showPermissionSheet =
        !state.hasLocationPermission &&
            !state.hasUserDismissedPermissionSheet
    var showShareLocationSheet by remember { mutableStateOf(false) }
    var selectedMarker by remember { mutableStateOf<ContactMarker?>(null) }
    val permissionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shareLocationSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val contactLocationSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Map state
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapStyleLoaded by remember { mutableStateOf(false) }
    var metersPerPixel by remember { mutableStateOf(1.0) }

    // Location client
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    // Permission launcher
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            val granted = permissions.values.all { it }
            viewModel.onPermissionResult(granted)
            if (granted) {
                startLocationUpdates(fusedLocationClient, viewModel)
            }
        }

    // Check permissions on first launch
    LaunchedEffect(Unit) {
        MapLibre.getInstance(context)
        if (LocationPermissionManager.hasPermission(context)) {
            viewModel.onPermissionResult(true)
            startLocationUpdates(fusedLocationClient, viewModel)
        }
        // Permission sheet visibility is now managed by ViewModel state
    }

    // Track whether we've done the initial center on user location
    var hasInitiallyCentered by remember { mutableStateOf(false) }

    // Center map on user location only on first location fix, not on every update
    LaunchedEffect(state.userLocation) {
        if (!hasInitiallyCentered && state.userLocation != null) {
            state.userLocation?.let { location ->
                mapLibreMap?.let { map ->
                    val cameraPosition =
                        CameraPosition.Builder()
                            .target(LatLng(location.latitude, location.longitude))
                            .zoom(15.0)
                            .build()
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
                    hasInitiallyCentered = true
                }
            }
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

    // Cleanup when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            // Disable location component before destroying map to prevent crashes
            mapLibreMap?.locationComponent?.let { locationComponent ->
                if (locationComponent.isLocationComponentActivated) {
                    locationComponent.isLocationComponentEnabled = false
                }
            }
            mapView?.onDestroy()
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

                        // Use OpenFreeMap tiles (free, no API key required)
                        // https://openfreemap.org - OpenStreetMap data with good detail
                        map.setStyle(
                            Style.Builder()
                                .fromUri("https://tiles.openfreemap.org/styles/liberty"),
                        ) { style ->
                            Log.d("MapScreen", "Map style loaded")

                            // Add dashed circle bitmaps for stale markers
                            val density = ctx.resources.displayMetrics.density
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

                            mapStyleLoaded = true

                            // Enable user location component (blue dot)
                            if (state.hasLocationPermission) {
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
                        }

                        // Add click listener for contact markers
                        map.addOnMapClickListener { latLng ->
                            val screenPoint = map.projection.toScreenLocation(latLng)
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
                                        Log.d("MapScreen", "Marker tapped: ${marker.displayName}")
                                    }
                                }
                            }
                            true
                        }

                        // Set initial camera position (use last known location if available)
                        val initialLat = state.userLocation?.latitude ?: 37.7749
                        val initialLng = state.userLocation?.longitude ?: -122.4194
                        val initialPosition =
                            CameraPosition.Builder()
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
            state.contactMarkers.forEach { marker ->
                val imageId = "marker-${marker.destinationHash}"
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
                    val imageId = "marker-${marker.destinationHash}"
                    Feature.fromGeometry(
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
                    CircleLayer(uncertaintyLayerId, sourceId).withProperties(
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
                        // Semi-transparent fill
                        PropertyFactory.circleColor(
                            Expression.color(android.graphics.Color.parseColor("#FF5722")), // Orange
                        ),
                        PropertyFactory.circleOpacity(
                            Expression.literal(0.15f),
                        ),
                        // Dashed stroke for the uncertainty boundary
                        PropertyFactory.circleStrokeWidth(
                            Expression.literal(2f),
                        ),
                        PropertyFactory.circleStrokeColor(
                            Expression.color(android.graphics.Color.parseColor("#FF5722")), // Orange
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
                        PropertyFactory.iconAnchor("top"), // Anchor at top (circle center)
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
                                Expression.literal(1.0f), // Default
                            ),
                        ),
                    ),
                )
            }
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
                    )
                    .align(Alignment.TopStart),
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
                    .padding(bottom = 80.dp), // Account for bottom navigation bar
        ) {
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
                                    CameraPosition.Builder()
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

            // Share/Stop Location button
            ExtendedFloatingActionButton(
                onClick = {
                    if (state.isSharing) {
                        viewModel.stopSharing()
                    } else {
                        showShareLocationSheet = true
                    }
                },
                icon = {
                    Icon(
                        if (state.isSharing) Icons.Default.Stop else Icons.Default.ShareLocation,
                        contentDescription = null,
                    )
                },
                text = { Text(if (state.isSharing) "Stop Sharing" else "Share Location") },
                containerColor =
                    if (state.isSharing) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                contentColor =
                    if (state.isSharing) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
            )
        }

        // Contact markers are shown directly on the map as circles
        // Tap a marker to open the contact detail bottom sheet

        // Show hint card if no location permission and not dismissed
        if (!state.hasLocationPermission && !state.isPermissionCardDismissed) {
            EmptyMapStateCard(
                contactCount = state.contactMarkers.size,
                onDismiss = { viewModel.dismissPermissionCard() },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(16.dp)
                        .padding(bottom = 180.dp), // Above FABs and nav bar
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
            onDismiss = { viewModel.dismissLocationPermissionSheet() },
            onRequestPermissions = {
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
            sheetState = contactLocationSheetState,
        )
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
 * Start location updates using FusedLocationProviderClient.
 */
@SuppressLint("MissingPermission")
private fun startLocationUpdates(
    fusedLocationClient: FusedLocationProviderClient,
    viewModel: MapViewModel,
) {
    val locationRequest =
        LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            30_000L, // 30 seconds
        ).apply {
            setMinUpdateIntervalMillis(15_000L) // 15 seconds
            setMaxUpdateDelayMillis(60_000L) // 1 minute
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
}

/**
 * Scale bar showing distance measurement that updates with zoom level.
 *
 * @param metersPerPixel Meters per screen pixel from MapLibre projection
 * @param modifier Modifier for positioning
 */
@Composable
private fun ScaleBar(
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
            5, 10, 20, 50, 100, 200, 500,
            1_000, 2_000, 5_000, 10_000, 20_000, 50_000,
            100_000, 200_000, 500_000, 1_000_000, 2_000_000, 5_000_000, 10_000_000,
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
