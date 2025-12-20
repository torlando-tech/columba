package com.lxmf.messenger.ui.screens

import android.annotation.SuppressLint
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.lxmf.messenger.util.LocationPermissionManager
import org.maplibre.android.geometry.LatLng as MapLibreLatLng
import com.lxmf.messenger.viewmodel.ContactMarker
import com.lxmf.messenger.viewmodel.MapViewModel
import com.lxmf.messenger.viewmodel.MarkerState
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import com.lxmf.messenger.ui.util.MarkerBitmapFactory

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

    var showPermissionSheet by remember { mutableStateOf(false) }
    var showShareLocationSheet by remember { mutableStateOf(false) }
    var selectedMarker by remember { mutableStateOf<ContactMarker?>(null) }
    val permissionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shareLocationSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val contactLocationSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Map state
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapStyleLoaded by remember { mutableStateOf(false) }

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
        if (!LocationPermissionManager.hasPermission(context)) {
            showPermissionSheet = true
        } else {
            viewModel.onPermissionResult(true)
            startLocationUpdates(fusedLocationClient, viewModel)
        }
    }

    // Center map on user location when it updates
    LaunchedEffect(state.userLocation) {
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
                            val staleCircleBitmap = MarkerBitmapFactory.createDashedCircle(
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
                            val features = map.queryRenderedFeatures(
                                screenPoint,
                                "contact-markers-layer",
                            )
                            features.firstOrNull()?.let { feature ->
                                val hash = feature.getStringProperty("hash")
                                if (hash != null) {
                                    val marker = state.contactMarkers.find {
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
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Update contact markers on the map when they change
        LaunchedEffect(state.contactMarkers, mapStyleLoaded) {
            Log.d("MapScreen", "LaunchedEffect triggered: markers=${state.contactMarkers.size}, styleLoaded=$mapStyleLoaded")
            if (!mapStyleLoaded) return@LaunchedEffect
            val map = mapLibreMap ?: return@LaunchedEffect
            val style = map.style ?: return@LaunchedEffect

            val sourceId = "contact-markers-source"
            val layerId = "contact-markers-layer"

            // Create GeoJSON features from contact markers with state property
            val features = state.contactMarkers.map { marker ->
                Feature.fromGeometry(
                    Point.fromLngLat(marker.longitude, marker.latitude)
                ).apply {
                    addStringProperty("name", marker.displayName)
                    addStringProperty("hash", marker.destinationHash)
                    addStringProperty("state", marker.state.name) // FRESH, STALE, or EXPIRED_GRACE_PERIOD
                }
            }
            val featureCollection = FeatureCollection.fromFeatures(features)

            // Update or create the source
            val existingSource = style.getSourceAs<GeoJsonSource>(sourceId)
            if (existingSource != null) {
                existingSource.setGeoJson(featureCollection)
            } else {
                // Add new source and layers with data-driven styling based on marker state
                style.addSource(GeoJsonSource(sourceId, featureCollection))

                // CircleLayer for the filled circle
                style.addLayer(
                    CircleLayer(layerId, sourceId).withProperties(
                        PropertyFactory.circleRadius(12f),
                        // Data-driven color: Orange for fresh, Gray for stale/expired
                        PropertyFactory.circleColor(
                            Expression.match(
                                Expression.get("state"),
                                Expression.literal(MarkerState.FRESH.name),
                                Expression.color(android.graphics.Color.parseColor("#FF5722")), // Orange
                                Expression.literal(MarkerState.STALE.name),
                                Expression.color(android.graphics.Color.parseColor("#9E9E9E")), // Gray
                                Expression.literal(MarkerState.EXPIRED_GRACE_PERIOD.name),
                                Expression.color(android.graphics.Color.parseColor("#9E9E9E")), // Gray
                                Expression.color(android.graphics.Color.parseColor("#FF5722")), // Default: Orange
                            )
                        ),
                        // Data-driven opacity: 100% for fresh, 60% for stale/expired
                        PropertyFactory.circleOpacity(
                            Expression.match(
                                Expression.get("state"),
                                Expression.literal(MarkerState.FRESH.name),
                                Expression.literal(1.0f),
                                Expression.literal(MarkerState.STALE.name),
                                Expression.literal(0.6f),
                                Expression.literal(MarkerState.EXPIRED_GRACE_PERIOD.name),
                                Expression.literal(0.6f),
                                Expression.literal(1.0f), // Default
                            )
                        ),
                        // Solid stroke only for fresh markers (stale uses dashed overlay)
                        PropertyFactory.circleStrokeWidth(
                            Expression.match(
                                Expression.get("state"),
                                Expression.literal(MarkerState.FRESH.name),
                                Expression.literal(3f),
                                Expression.literal(0f), // No solid stroke for stale markers
                            )
                        ),
                        PropertyFactory.circleStrokeColor(
                            Expression.color(android.graphics.Color.WHITE)
                        ),
                    )
                )

                // SymbolLayer for dashed outline on stale/expired markers
                val dashedLayerId = "contact-markers-dashed-layer"
                style.addLayer(
                    SymbolLayer(dashedLayerId, sourceId).withProperties(
                        PropertyFactory.iconImage("stale-dashed-circle"),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                        // Only show for stale/expired markers
                        PropertyFactory.iconOpacity(
                            Expression.match(
                                Expression.get("state"),
                                Expression.literal(MarkerState.FRESH.name),
                                Expression.literal(0f), // Hidden for fresh
                                Expression.literal(MarkerState.STALE.name),
                                Expression.literal(0.7f),
                                Expression.literal(MarkerState.EXPIRED_GRACE_PERIOD.name),
                                Expression.literal(0.7f),
                                Expression.literal(0f), // Default: hidden
                            )
                        ),
                    )
                )

                // TODO: Add text labels for name/distance below markers
                // SymbolLayer for text was causing rendering issues - needs investigation
            }

            Log.d("MapScreen", "Updated ${state.contactMarkers.size} contact markers on map")
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
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp), // Below TopAppBar
            )
        }

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
                containerColor = if (state.isSharing) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                contentColor = if (state.isSharing) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
            )
        }

        // Contact markers are shown directly on the map as circles
        // Tap a marker to open the contact detail bottom sheet

        // Show hint card if no location permission
        if (!state.hasLocationPermission) {
            EmptyMapStateCard(
                contactCount = state.contactMarkers.size,
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
            onDismiss = { showPermissionSheet = false },
            onRequestPermissions = {
                showPermissionSheet = false
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
 */
@Composable
private fun EmptyMapStateCard(
    @Suppress("UNUSED_PARAMETER") contactCount: Int,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
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
