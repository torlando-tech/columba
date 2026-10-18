@file:Suppress("TooManyFunctions")

package network.columba.app.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import network.columba.app.R
import network.columba.app.rns.api.model.DiscoveredInterface
import network.columba.app.rns.api.model.InterfaceMode
import network.columba.app.ui.components.LocalCapabilities
import network.columba.app.ui.components.ServiceRestartBanner
import network.columba.app.ui.components.SortModeSelector
import network.columba.app.ui.theme.MaterialDesignIcons
import network.columba.app.util.LocationCompat
import network.columba.app.viewmodel.DiscoveredInterfaceTypeFilter
import network.columba.app.viewmodel.DiscoveredInterfacesViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Material Design Icons font family for custom icons like incognito.
 */
private val MdiFont = FontFamily(Font(R.font.materialdesignicons))

/**
 * Screen for displaying discovered interfaces from RNS 1.1.x discovery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveredInterfacesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTcpClientWizard: (host: String, port: Int, name: String, ifacNetname: String?, ifacNetkey: String?) -> Unit = { _, _, _, _, _ -> },
    onNavigateToMapWithInterface: (details: FocusInterfaceDetails) -> Unit = { _ -> },
    onNavigateToRNodeWizardWithParams: (
        frequency: Long?,
        bandwidth: Int?,
        spreadingFactor: Int?,
        codingRate: Int?,
    ) -> Unit = { _, _, _, _ -> },
    viewModel: DiscoveredInterfacesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Fetch user location for proximity sorting (if permission granted)
    LaunchedEffect(Unit) {
        val hasCoarsePermission =
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasFinePermission =
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasCoarsePermission || hasFinePermission) {
            if (LocationCompat.isPlayServicesAvailable(context)) {
                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                fusedClient
                    .getCurrentLocation(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        CancellationTokenSource().token,
                    ).addOnSuccessListener { location ->
                        location?.let {
                            viewModel.setUserLocation(it.latitude, it.longitude)
                        }
                    }
            } else {
                // Fallback to platform LocationManager (issue #456)
                LocationCompat.getCurrentLocation(context) { location ->
                    location?.let {
                        viewModel.setUserLocation(it.latitude, it.longitude)
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.iface_disc_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadDiscoveredInterfaces() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.iface_refresh))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Discovery settings card
                        item {
                            DiscoverySettingsCard(
                                isRuntimeEnabled = state.isDiscoveryEnabled,
                                isSettingEnabled = state.discoverInterfacesEnabled,
                                autoconnectCount = state.autoconnectCount,
                                autoconnectIfacOnly = state.autoconnectIfacOnly,
                                autoconnectInterfaceMode = state.autoconnectInterfaceMode,
                                bootstrapInterfaceNames = state.bootstrapInterfaceNames,
                                isRestarting = state.isRestarting,
                                pendingRestart = state.pendingDiscoveryRestart,
                                onToggleDiscovery = { viewModel.toggleDiscovery() },
                                onAutoconnectCountChange = { viewModel.setAutoconnectCount(it) },
                                onAutoconnectInterfaceModeChange = { viewModel.setAutoconnectInterfaceMode(it) },
                                onToggleAutoconnectIfacOnly = { viewModel.toggleAutoconnectIfacOnly() },
                                onApplyDiscoveryChanges = { viewModel.applyPendingDiscoveryRestart() },
                            )
                        }

                        // Status summary (counts reflect ALL discovered interfaces, not filtered)
                        if (state.originalInterfaces.isNotEmpty()) {
                            item {
                                DiscoveryStatusSummary(
                                    totalCount = state.originalInterfaces.size,
                                    availableCount = state.availableCount,
                                    unknownCount = state.unknownCount,
                                    staleCount = state.staleCount,
                                )
                            }

                            // Sort mode selector
                            item {
                                SortModeSelector(
                                    currentMode = state.sortMode,
                                    hasUserLocation = state.userLatitude != null && state.userLongitude != null,
                                    onModeSelected = { viewModel.setSortMode(it) },
                                )
                            }

                            // Search + type filters
                            item {
                                DiscoveredInterfaceSearchAndFilter(
                                    searchQuery = state.searchQuery,
                                    onSearchQueryChange = { viewModel.setSearchQuery(it) },
                                    typeFilters = state.typeFilters,
                                    onToggleTypeFilter = { viewModel.toggleTypeFilter(it) },
                                    ifacOnly = state.ifacOnly,
                                    onToggleIfacOnly = { viewModel.toggleIfacOnlyFilter() },
                                    onClearFilters = { viewModel.clearFilters() },
                                    filteredCount = state.interfaces.size,
                                    totalCount = state.originalInterfaces.size,
                                )
                            }
                        }

                        // Show empty state or interfaces
                        if (state.interfaces.isEmpty()) {
                            item {
                                if (state.originalInterfaces.isEmpty()) {
                                    EmptyDiscoveredCard()
                                } else {
                                    NoFilterMatchesCard(onClearFilters = { viewModel.clearFilters() })
                                }
                            }
                        } else {
                            items(
                                state.interfaces,
                                key = { iface ->
                                    // discoveryHash (hex SHA256 of transportId + name) is stable across re-announces;
                                    // fall back to a composite for announces that don't carry one. Including
                                    // name keeps radio interfaces (no reachableOn/port) unique.
                                    iface.discoveryHash
                                        ?: "${iface.networkId}:${iface.reachableOn ?: ""}:${iface.port ?: ""}:${iface.name}"
                                },
                            ) { iface ->
                                val reachableHost = iface.reachableOn
                                DiscoveredInterfaceCard(
                                    iface = iface,
                                    distanceKm = viewModel.calculateDistance(iface),
                                    isConnected = viewModel.isAutoconnected(iface),
                                    onAddToConfig = {
                                        if (iface.isTcpInterface && reachableHost != null) {
                                            onNavigateToTcpClientWizard(
                                                reachableHost,
                                                iface.port ?: 4242,
                                                iface.name,
                                                iface.ifacNetname,
                                                iface.ifacNetkey,
                                            )
                                        } else {
                                            Toast
                                                .makeText(
                                                    context,
                                                    context.getString(R.string.iface_disc_tcp_only),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                        }
                                    },
                                    onOpenLocation = {
                                        // Open in Columba's map with focus on this location
                                        val lat = iface.latitude ?: return@DiscoveredInterfaceCard
                                        val lon = iface.longitude ?: return@DiscoveredInterfaceCard
                                        val details =
                                            FocusInterfaceDetails(
                                                name = iface.name,
                                                type = iface.type,
                                                latitude = lat,
                                                longitude = lon,
                                                height = iface.height,
                                                reachableOn = iface.reachableOn,
                                                port = iface.port,
                                                frequency = iface.frequency,
                                                bandwidth = iface.bandwidth,
                                                spreadingFactor = iface.spreadingFactor,
                                                codingRate = iface.codingRate,
                                                modulation = iface.modulation,
                                                status = iface.status,
                                                lastHeard = iface.lastHeard,
                                                hops = iface.hops,
                                            )
                                        onNavigateToMapWithInterface(details)
                                    },
                                    onCopyLoraParams = {
                                        val params = formatLoraParamsForClipboard(context, iface)
                                        clipboardManager.setText(AnnotatedString(params))
                                        Toast
                                            .makeText(
                                                context,
                                                context.getString(R.string.iface_disc_lora_copied),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                    },
                                    onUseForNewRNode = {
                                        onNavigateToRNodeWizardWithParams(
                                            iface.frequency,
                                            iface.bandwidth,
                                            iface.spreadingFactor,
                                            iface.codingRate,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Card showing discovery settings and status.
 */
@Composable
internal fun DiscoverySettingsCard(
    isRuntimeEnabled: Boolean,
    isSettingEnabled: Boolean,
    autoconnectCount: Int = 0,
    autoconnectIfacOnly: Boolean = false,
    autoconnectInterfaceMode: String? = null,
    bootstrapInterfaceNames: List<String> = emptyList(),
    isRestarting: Boolean = false,
    pendingRestart: Boolean = false,
    onToggleDiscovery: () -> Unit = {},
    onAutoconnectCountChange: (Int) -> Unit = {},
    onAutoconnectInterfaceModeChange: (String?) -> Unit = {},
    onToggleAutoconnectIfacOnly: () -> Unit = {},
    onApplyDiscoveryChanges: () -> Unit = {},
) {
    val isEnabled = isRuntimeEnabled || isSettingEnabled

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            // Discovery toggle row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        modifier = Modifier.size(12.dp),
                        shape = RoundedCornerShape(50),
                        color =
                            if (isRuntimeEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else if (isSettingEnabled) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                    ) {}
                    Column {
                        Text(
                            text = stringResource(R.string.iface_discovery_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color =
                                if (isEnabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                        Text(
                            text =
                                if (isRestarting) {
                                    stringResource(R.string.iface_restarting)
                                } else if (isRuntimeEnabled) {
                                    stringResource(R.string.iface_disc_active)
                                } else {
                                    stringResource(R.string.iface_disabled)
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (isEnabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                },
                        )
                    }
                }
                Switch(
                    checked = isSettingEnabled,
                    onCheckedChange = { onToggleDiscovery() },
                    enabled = !isRestarting,
                )
            }

            // Restarting message
            if (isRestarting) {
                Spacer(modifier = Modifier.height(8.dp))
                ServiceRestartBanner()
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Info text
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint =
                        if (isEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                )
                Text(
                    text =
                        if (isSettingEnabled) {
                            if (autoconnectCount > 0) {
                                stringResource(R.string.iface_disc_autoconnect_info, autoconnectCount)
                            } else {
                                stringResource(R.string.iface_disc_autoconnect_off)
                            }
                        } else {
                            stringResource(R.string.iface_disc_enable_info)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (isEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                )
            }

            // Autoconnect count slider (only shown when discovery is enabled)
            if (isSettingEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.iface_disc_autoconnect_limit),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = if (autoconnectCount == 0) stringResource(R.string.iface_off) else "$autoconnectCount",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    // Use a separate state to track slider changes and only commit on release
                    var sliderValue by remember { mutableStateOf(autoconnectCount.toFloat()) }
                    // Update local state when external state changes
                    LaunchedEffect(autoconnectCount) {
                        sliderValue = autoconnectCount.toFloat()
                    }
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            val newCount = sliderValue.toInt()
                            if (newCount != autoconnectCount) {
                                onAutoconnectCountChange(newCount)
                            }
                        },
                        valueRange = 0f..10f,
                        steps = 9,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isRestarting,
                    )

                    // Auto-connected interface mode selector. Visible whenever the
                    // backend honours the `autoconnect_interface_mode` config key -
                    // slim Python RNS 1.4+ does, reticulum-kt does not. Hiding it on
                    // the Kotlin flavor avoids a UI lie (mirrors the IFAC-only gate
                    // below). Shown regardless of the auto-connect count so the user
                    // can choose a mode even while auto-connect is at 0 (observe-only).
                    // "Default" (null) lets RNS pick its own mode (gateway when
                    // transport is enabled, full otherwise).
                    val modeSelectorSupported = LocalCapabilities.current.interfaces.autoconnectInterfaceMode
                    if (modeSelectorSupported) {
                        Spacer(modifier = Modifier.height(8.dp))
                        AutoconnectModeSelector(
                            selectedMode = autoconnectInterfaceMode,
                            onModeChange = onAutoconnectInterfaceModeChange,
                            enabled = !isRestarting,
                        )
                    }

                    // IFAC-only sub-toggle. Visible only when (a) auto-connect
                    // is on AND (b) the backend actually enforces the IFAC
                    // filter — reticulum-kt does (NativeRnsBackendImpl
                    // .createAutoconnectInterface skips when ifac netname is
                    // blank), upstream Python RNS does not. Hiding the toggle
                    // on the Python flavor avoids a UI lie. Mirrors the
                    // "hide entirely" capability-gate pattern from
                    // BatteryOptimizationCard. The DataStore value still
                    // persists across backend swaps; only the UI control is
                    // gated.
                    val ifacFilterSupported = LocalCapabilities.current.interfaces.autoconnectIfacOnlyFilter
                    if (autoconnectCount > 0 && ifacFilterSupported) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.iface_disc_ifac_only_title),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.iface_disc_ifac_only_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                )
                            }
                            Switch(
                                checked = autoconnectIfacOnly,
                                onCheckedChange = { onToggleAutoconnectIfacOnly() },
                                enabled = !isRestarting,
                            )
                        }
                    }
                }
            }

            // Bootstrap interfaces section
            if (bootstrapInterfaceNames.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.iface_disc_bootstrap),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color =
                        if (isEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Spacer(modifier = Modifier.height(4.dp))
                bootstrapInterfaceNames.forEach { name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(6.dp),
                            shape = RoundedCornerShape(50),
                            color =
                                if (isEnabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                        ) {}
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (isEnabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.iface_disc_bootstrap_note),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (isEnabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Apply button: shown when a discovery-settings change (autoconnect
            // count and/or interface mode) needs a Reticulum restart to take
            // effect. One tap restarts once and applies both at once.
            if (pendingRestart) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onApplyDiscoveryChanges,
                    enabled = !isRestarting,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                ) {
                    Text(stringResource(R.string.discovery_apply_changes))
                }
            }
        }
    }
}

/**
 * Dropdown for the interface mode of auto-connected discovered interfaces.
 * "Default" (null) lets RNS use its own mode (gateway when transport enabled,
 * full otherwise). The remaining options come from the [InterfaceMode] enum.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoconnectModeSelector(
    selectedMode: String?,
    onModeChange: (String?) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    // "Default" + every InterfaceMode entry. null = let RNS decide.
    val options: List<Pair<String?, String>> =
        listOf(null to stringResource(R.string.autoconnect_mode_default)) +
            InterfaceMode.entries.map { mode ->
                mode.value to
                    stringResource(
                        when (mode) {
                            InterfaceMode.FULL -> R.string.interface_mode_full
                            InterfaceMode.GATEWAY -> R.string.interface_mode_gateway
                            InterfaceMode.ACCESS_POINT -> R.string.interface_mode_access_point
                            InterfaceMode.ROAMING -> R.string.interface_mode_roaming
                            InterfaceMode.BOUNDARY -> R.string.interface_mode_boundary
                            InterfaceMode.INTERNAL -> R.string.interface_mode_internal
                        },
                    )
            }

    val displayValue = options.find { it.first == selectedMode }?.second
        ?: options.first().second

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.autoconnect_mode_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onModeChange(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Card shown when no interfaces are discovered.
 */
@Composable
internal fun NoFilterMatchesCard(onClearFilters: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.iface_disc_no_matches),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.iface_disc_no_matches_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onClearFilters) {
                Text(stringResource(R.string.iface_disc_clear_filters))
            }
        }
    }
}

/**
 * Search field + type-filter chip row for the discovered interfaces list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DiscoveredInterfaceSearchAndFilter(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    typeFilters: Set<DiscoveredInterfaceTypeFilter>,
    onToggleTypeFilter: (DiscoveredInterfaceTypeFilter) -> Unit,
    ifacOnly: Boolean,
    onToggleIfacOnly: () -> Unit,
    onClearFilters: () -> Unit,
    filteredCount: Int,
    totalCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.iface_disc_search_placeholder)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.iface_disc_clear_search),
                            )
                        }
                    }
                },
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DiscoveredInterfaceTypeFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = filter in typeFilters,
                        onClick = { onToggleTypeFilter(filter) },
                        label = { Text(stringResource(filter.labelRes)) },
                    )
                }
                FilterChip(
                    selected = ifacOnly,
                    onClick = onToggleIfacOnly,
                    label = { Text(stringResource(R.string.iface_disc_ifac_only_chip)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }

            val hasActiveFilters = searchQuery.isNotBlank() || typeFilters.isNotEmpty() || ifacOnly
            if (hasActiveFilters) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$filteredCount of $totalCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onClearFilters) {
                        Text(stringResource(R.string.iface_clear))
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmptyDiscoveredCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.iface_disc_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.iface_disc_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * Summary of discovered interface statuses.
 */
@Composable
internal fun DiscoveryStatusSummary(
    totalCount: Int,
    availableCount: Int,
    unknownCount: Int,
    staleCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$totalCount",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.iface_total),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$availableCount",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.iface_disc_available),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$unknownCount",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = stringResource(R.string.iface_disc_unknown),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$staleCount",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = stringResource(R.string.iface_disc_stale),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Card showing details for a single discovered interface.
 */
@Composable
internal fun DiscoveredInterfaceCard(
    iface: DiscoveredInterface,
    distanceKm: Double?,
    isConnected: Boolean,
    onAddToConfig: () -> Unit,
    onOpenLocation: () -> Unit,
    onCopyLoraParams: () -> Unit,
    onUseForNewRNode: () -> Unit,
) {
    val context = LocalContext.current
    val statusColor =
        when (iface.status) {
            "available" -> MaterialTheme.colorScheme.primary
            "unknown" -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.outline
        }

    // Check if this is a special network type that needs explanation
    val isYggdrasil = iface.isTcpInterface && isYggdrasilAddress(iface.reachableOn)
    val isI2p = iface.type == "I2PInterface"
    var showNetworkInfoDialog by remember { mutableStateOf(false) }

    // Network info dialogs
    if (showNetworkInfoDialog) {
        when {
            isYggdrasil -> {
                AlertDialog(
                    onDismissRequest = { showNetworkInfoDialog = false },
                    title = { Text(stringResource(R.string.iface_disc_yggdrasil_title)) },
                    text = {
                        Text(stringResource(R.string.iface_disc_yggdrasil_body))
                    },
                    confirmButton = {
                        TextButton(onClick = { showNetworkInfoDialog = false }) {
                            Text(stringResource(R.string.iface_ok))
                        }
                    },
                )
            }
            isI2p -> {
                AlertDialog(
                    onDismissRequest = { showNetworkInfoDialog = false },
                    title = { Text(stringResource(R.string.iface_disc_i2p_title)) },
                    text = {
                        Text(stringResource(R.string.iface_disc_i2p_body))
                    },
                    confirmButton = {
                        TextButton(onClick = { showNetworkInfoDialog = false }) {
                            Text(stringResource(R.string.iface_ok))
                        }
                    },
                )
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            // Header: Name and Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    InterfaceTypeIcon(
                        type = iface.type,
                        host = iface.reachableOn,
                        size = 20.dp,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Column {
                        Text(
                            text = iface.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = if (isYggdrasil) stringResource(R.string.iface_disc_type_yggdrasil) else formatInterfaceType(context, iface.type),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Info icon for special networks (Yggdrasil, I2P)
                            if (isYggdrasil || isI2p) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = stringResource(R.string.iface_disc_network_info),
                                    modifier =
                                        Modifier
                                            .size(14.dp)
                                            .clickable { showNetworkInfoDialog = true },
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                // Badges row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Connected badge (shown when auto-connected)
                    if (isConnected) {
                        val connectedColor = MaterialTheme.colorScheme.primary
                        Surface(
                            color = connectedColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Surface(
                                    modifier = Modifier.size(8.dp),
                                    shape = RoundedCornerShape(50),
                                    color = connectedColor,
                                ) {}
                                Text(
                                    text = stringResource(R.string.iface_disc_connected),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = connectedColor,
                                )
                            }
                        }
                    }
                    // Status badge
                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Surface(
                                modifier = Modifier.size(8.dp),
                                shape = RoundedCornerShape(50),
                                color = statusColor,
                            ) {}
                            Text(
                                text = iface.status.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                            )
                        }
                    }
                }
            }

            // Transport ID (truncated)
            iface.transportId?.let { transportId ->
                Text(
                    text = stringResource(R.string.iface_disc_transport_id, transportId.take(12)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            // Type-specific details
            when {
                iface.type == "I2PInterface" -> {
                    I2pInterfaceDetails(iface)
                }
                iface.isTcpInterface -> {
                    TcpInterfaceDetails(iface)
                }
                iface.isRadioInterface -> {
                    RadioInterfaceDetails(iface)
                }
            }

            // IFAC indicator — the remote is publishing its IFAC network identity,
            // so connecting to it requires the matching network_name / passphrase
            // (which will be auto-filled into the Add flow).
            iface.ifacNetname?.takeIf { it.isNotBlank() }?.let { ifacNet ->
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.iface_disc_ifac_prefix),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = ifacNet,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (!iface.ifacNetkey.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "🔑",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Location if available
            val lat = iface.latitude
            val lon = iface.longitude
            if (lat != null && lon != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LocationDetails(
                    latitude = lat,
                    longitude = lon,
                    height = iface.height,
                    distanceKm = distanceKm,
                    onClick = onOpenLocation,
                )
            }

            // Last heard and hops
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.iface_disc_last_heard, formatLastHeard(context, iface.lastHeard)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (iface.hops > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.iface_disc_hops, iface.hops, iface.hops),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Expandable "all announced fields" section
            Spacer(modifier = Modifier.height(8.dp))
            DiscoveredInterfaceAllFieldsSection(iface = iface)

            // Add to Config button (only for TCP interfaces with host info)
            if (iface.isTcpInterface && iface.reachableOn != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onAddToConfig,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.iface_disc_add_to_config))
                }
            }

            // LoRa params buttons (only for radio interfaces with frequency info)
            if (iface.isRadioInterface && iface.frequency != null) {
                Spacer(modifier = Modifier.height(12.dp))
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
                        Text(stringResource(R.string.iface_disc_copy_params))
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
                            imageVector = ImageVector.vectorResource(com.composables.icons.lucide.R.drawable.lucide_ic_antenna),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.iface_disc_use_for_rnode))
                    }
                }
            }
        }
    }
}

/**
 * Collapsible "all announced fields" section. Shows every field the remote
 * published in its discovery announce as a key/value list — useful for
 * diagnosing unexpected interfaces and confirming radio parameters.
 */
@Composable
internal fun DiscoveredInterfaceAllFieldsSection(iface: DiscoveredInterface) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector =
                    if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                contentDescription = stringResource(if (expanded) R.string.iface_disc_collapse_details else R.string.iface_disc_expand_details),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(if (expanded) R.string.iface_disc_hide_fields else R.string.iface_disc_show_fields),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val transportYes = stringResource(R.string.iface_yes)
                    val transportNo = stringResource(R.string.iface_no)
                    val rows =
                        buildList<Pair<Int, String?>> {
                            add(R.string.iface_field_name to iface.name)
                            add(R.string.iface_field_type to iface.type)
                            add(R.string.iface_field_status to iface.status)
                            add(R.string.iface_field_transport_node to if (iface.transport) transportYes else transportNo)
                            add(R.string.iface_field_hops to iface.hops.toString())
                            add(R.string.iface_field_heard_count to iface.heardCount.toString())
                            add(R.string.iface_field_stamp_value to iface.stampValue.toString())
                            add(R.string.iface_field_network_id to iface.networkId)
                            add(R.string.iface_field_transport_id to iface.transportId)
                            add(R.string.iface_field_discovery_hash to iface.discoveryHash)
                            add(R.string.iface_field_reachable_on to iface.reachableOn)
                            add(R.string.iface_field_port to iface.port?.toString())
                            add(R.string.iface_field_frequency to iface.frequency?.let { "$it Hz" })
                            add(R.string.iface_field_bandwidth to iface.bandwidth?.let { "$it Hz" })
                            add(R.string.iface_field_spreading_factor to iface.spreadingFactor?.toString())
                            add(R.string.iface_field_coding_rate to iface.codingRate?.toString())
                            add(R.string.iface_field_modulation to iface.modulation)
                            add(R.string.iface_field_channel to iface.channel?.toString())
                            add(R.string.iface_field_latitude to iface.latitude?.toString())
                            add(R.string.iface_field_longitude to iface.longitude?.toString())
                            add(R.string.iface_field_height to iface.height?.let { "$it m" })
                            add(R.string.iface_field_ifac_network_name to iface.ifacNetname)
                            // The passphrase was sent in the cleartext announce — anyone on
                            // the discovery network already has it — so there's nothing to
                            // obfuscate here and showing the value is useful for diagnostics.
                            add(R.string.iface_field_ifac_passphrase to iface.ifacNetkey?.takeIf { it.isNotBlank() })
                            add(R.string.iface_field_received_at to iface.receivedAt.takeIf { it > 0 }?.let { formatUnixSeconds(it) })
                            add(R.string.iface_field_discovered_at to iface.discoveredAt.takeIf { it > 0 }?.let { formatUnixSeconds(it) })
                            add(R.string.iface_field_last_heard to iface.lastHeard.takeIf { it > 0 }?.let { formatUnixSeconds(it) })
                        }
                    rows.forEach { (key, value) ->
                        if (value.isNullOrBlank()) return@forEach
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(key),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(120.dp),
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatUnixSeconds(seconds: Long): String {
    val date = Date(seconds * 1000L)
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    return fmt.format(date)
}

/**
 * Details for TCP-based interfaces.
 */
@Composable
internal fun TcpInterfaceDetails(iface: DiscoveredInterface) {
    val context = LocalContext.current
    val hostPort =
        buildString {
            iface.reachableOn?.let { append(it) }
            iface.port?.let { port ->
                if (isNotEmpty()) append(":$port") else append(context.getString(R.string.iface_disc_port, port))
            }
        }
    if (hostPort.isNotEmpty()) {
        Text(
            text = hostPort,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Details for I2P interfaces showing the b32 address.
 */
@Composable
internal fun I2pInterfaceDetails(iface: DiscoveredInterface) {
    iface.reachableOn?.let { b32Address ->
        Text(
            text = "$b32Address.b32.i2p",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Details for radio-based interfaces (RNode, Weave, KISS).
 */
@Composable
internal fun RadioInterfaceDetails(iface: DiscoveredInterface) {
    val parts = mutableListOf<String>()

    iface.frequency?.let { freq ->
        val mhz = freq / 1_000_000.0
        parts.add("$mhz MHz")
    }
    iface.bandwidth?.let { bw ->
        val khz = bw / 1000
        parts.add("$khz kHz")
    }
    iface.spreadingFactor?.let { sf ->
        parts.add("SF$sf")
    }
    iface.codingRate?.let { cr ->
        parts.add("CR 4/$cr")
    }
    iface.modulation?.let { mod ->
        parts.add(mod)
    }
    iface.channel?.let { ch ->
        parts.add("CH$ch")
    }

    if (parts.isNotEmpty()) {
        Text(
            text = parts.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Location details with optional distance. Tappable to open in maps.
 */
@Composable
internal fun LocationDetails(
    latitude: Double,
    longitude: Double,
    height: Double?,
    distanceKm: Double?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = stringResource(R.string.iface_disc_open_in_maps),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        val context = LocalContext.current
        val locationText =
            buildString {
                append("%.4f, %.4f".format(latitude, longitude))
                height?.let { append(" (${it.toInt()}m)") }
                distanceKm?.let { dist ->
                    append(" - ")
                    if (dist < 1.0) {
                        append(context.getString(R.string.iface_disc_distance_meters, (dist * 1000).toInt()))
                    } else {
                        append(context.getString(R.string.iface_disc_distance_km, dist))
                    }
                }
            }
        Text(
            text = locationText,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    textDecoration = TextDecoration.Underline,
                ),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Renders the appropriate icon for an interface type.
 * Uses Lucide Antenna for radio interfaces, MDI incognito for I2P, and Material icons for others.
 */

/**
 * Check if a host address is a Yggdrasil network address (IPv6 in 0200::/7 space).
 * Yggdrasil uses addresses starting with 02xx or 03xx.
 */
internal fun isYggdrasilAddress(host: String?): Boolean {
    // Early exit for null
    if (host == null) return false

    // Clean host, check IPv6, parse first segment, and validate range in one chain
    val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
    val firstSegment = cleanHost.takeIf { it.contains(":") }?.split(":")?.firstOrNull()
    val value = firstSegment?.toIntOrNull(16)

    // 0200::/7 means first 7 bits are 0000001, covering 0x0200-0x03FF
    return value != null && value in 0x0200..0x03FF
}

/**
 * Renders the appropriate icon for an interface type.
 * Uses Lucide Antenna for radio interfaces, MDI incognito for I2P,
 * TreePine for Yggdrasil, and Material icons for others.
 */
@Composable
internal fun InterfaceTypeIcon(
    type: String,
    host: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    when (type) {
        "TCPServerInterface", "TCPClientInterface", "BackboneInterface" -> {
            if (isYggdrasilAddress(host)) {
                // Use TreePine for Yggdrasil network addresses
                Icon(
                    imageVector = ImageVector.vectorResource(com.composables.icons.lucide.R.drawable.lucide_ic_tree_pine),
                    contentDescription = stringResource(R.string.iface_disc_icon_yggdrasil),
                    modifier = modifier.size(size),
                    tint = tint,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = stringResource(R.string.iface_disc_icon_tcp),
                    modifier = modifier.size(size),
                    tint = tint,
                )
            }
        }
        "I2PInterface" -> {
            // Use MDI incognito icon for I2P (anonymity network)
            val i2pDescription = stringResource(R.string.iface_disc_icon_i2p)
            val codepoint = MaterialDesignIcons.getCodepointOrNull("incognito")
            if (codepoint != null) {
                Text(
                    text = codepoint,
                    fontFamily = MdiFont,
                    fontSize = (size.value * 1.2f).sp, // MDI icons render slightly smaller
                    color = tint,
                    modifier = modifier.semantics { contentDescription = i2pDescription },
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.iface_disc_icon_i2p),
                    modifier = modifier.size(size),
                    tint = tint,
                )
            }
        }
        "RNodeInterface", "WeaveInterface", "KISSInterface" -> {
            // Use Lucide Antenna for radio interfaces (matches PeerCard)
            Icon(
                imageVector = ImageVector.vectorResource(com.composables.icons.lucide.R.drawable.lucide_ic_antenna),
                contentDescription = stringResource(R.string.iface_disc_icon_radio),
                modifier = modifier.size(size),
                tint = tint,
            )
        }
        else -> {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.iface_disc_icon_unknown),
                modifier = modifier.size(size),
                tint = tint,
            )
        }
    }
}

/**
 * Format interface type for display.
 */
internal fun formatInterfaceType(context: Context, type: String): String =
    when (type) {
        "TCPServerInterface" -> context.getString(R.string.iface_cfg_type_tcp_server)
        "TCPClientInterface" -> context.getString(R.string.iface_cfg_type_tcp_client)
        "BackboneInterface" -> context.getString(R.string.iface_disc_type_backbone)
        "I2PInterface" -> context.getString(R.string.discovered_filter_i2p)
        "RNodeInterface" -> context.getString(R.string.iface_disc_type_rnode)
        "WeaveInterface" -> context.getString(R.string.iface_disc_type_weave)
        "KISSInterface" -> context.getString(R.string.iface_disc_type_kiss)
        else -> type
    }

/**
 * Format last heard timestamp as relative time.
 */
internal fun formatLastHeard(context: Context, timestamp: Long): String {
    if (timestamp == 0L) return context.getString(R.string.iface_never)

    val now = System.currentTimeMillis() / 1000
    val diff = now - timestamp

    return when {
        diff < 60 -> context.getString(R.string.iface_just_now)
        diff < 3600 -> context.getString(R.string.iface_min_ago, diff / 60)
        diff < 86400 -> context.getString(R.string.iface_hours_ago, diff / 3600)
        diff < 604800 -> context.getString(R.string.iface_days_ago, diff / 86400)
        else -> {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(Date(timestamp * 1000))
        }
    }
}

/**
 * Format LoRa parameters for clipboard.
 */
internal fun formatLoraParamsForClipboard(context: Context, iface: DiscoveredInterface): String =
    buildString {
        appendLine(context.getString(R.string.iface_clip_lora_header, iface.name))
        appendLine("---")
        iface.frequency?.let { freq ->
            appendLine(context.getString(R.string.iface_clip_frequency, freq / 1_000_000.0))
        }
        iface.bandwidth?.let { bw ->
            appendLine(context.getString(R.string.iface_clip_bandwidth, bw / 1000))
        }
        iface.spreadingFactor?.let { sf ->
            appendLine(context.getString(R.string.iface_clip_spreading_factor, sf))
        }
        iface.codingRate?.let { cr ->
            appendLine(context.getString(R.string.iface_clip_coding_rate, cr))
        }
        iface.modulation?.let { mod ->
            appendLine(context.getString(R.string.iface_clip_modulation, mod))
        }
    }.trim()
