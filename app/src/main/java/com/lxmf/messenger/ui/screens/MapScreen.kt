package com.lxmf.messenger.ui.screens

import android.annotation.SuppressLint
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.lxmf.messenger.ui.components.LocationPermissionBottomSheet
import com.lxmf.messenger.util.LocationPermissionManager
import com.lxmf.messenger.viewmodel.ContactMarker
import com.lxmf.messenger.viewmodel.MapViewModel
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
    @Suppress("UNUSED_PARAMETER") // Phase 2: Used when contact markers are tapped
    onContactClick: (destinationHash: String) -> Unit = {},
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    var showPermissionSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Map state
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Map") },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    ),
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
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

                // Share Location button (disabled in Phase 1)
                ExtendedFloatingActionButton(
                    onClick = {
                        // TODO: Phase 2 - Open share location bottom sheet
                    },
                    icon = { Icon(Icons.Default.ShareLocation, contentDescription = null) },
                    text = { Text("Share Location") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            // MapLibre MapView
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

            // Phase 2: Contact markers will be shown here when real location sharing is implemented
            // For now, just show a hint card if user location isn't available yet
            if (!state.hasLocationPermission) {
                EmptyMapStateCard(
                    contactCount = 0,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                            .padding(bottom = 100.dp),
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
            sheetState = sheetState,
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
 * Overlay showing contact markers as a list.
 *
 * Phase 2: Will be used when real location sharing is implemented.
 */
@Suppress("unused")
@Composable
private fun ContactMarkersOverlay(
    markers: List<ContactMarker>,
    onContactClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Text(
                text = "Contacts (${markers.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            markers.take(5).forEach { marker ->
                ContactMarkerItem(
                    marker = marker,
                    onClick = { onContactClick(marker.destinationHash) },
                )
                if (marker != markers.last() && markers.indexOf(marker) < 4) {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            if (markers.size > 5) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "+${markers.size - 5} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Individual contact marker item in the overlay.
 *
 * Phase 2: Will be used when real location sharing is implemented.
 */
@Suppress("unused")
@Composable
private fun ContactMarkerItem(
    marker: ContactMarker,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar placeholder
        Surface(
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Text(
                    text = marker.displayName.take(1).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = marker.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Test location", // Phase 1: static test positions
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
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
