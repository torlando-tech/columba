@file:Suppress("TooManyFunctions") // Composable UI file with multiple focused helpers

package com.lxmf.messenger.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.lxmf.messenger.util.LocationCompat
import com.lxmf.messenger.R
import com.lxmf.messenger.reticulum.protocol.DiscoveredInterface
import com.lxmf.messenger.ui.components.SortModeSelector
import com.lxmf.messenger.ui.theme.MaterialDesignIcons
import com.lxmf.messenger.viewmodel.DiscoveredInterfacesViewModel
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
    onNavigateToTcpClientWizard: (host: String, port: Int, name: String) -> Unit = { _, _, _ -> },
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
                title = { Text(stringResource(R.string.discovered_interfaces_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadDiscoveredInterfaces() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.discovered_interfaces_refresh))
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
                                bootstrapInterfaceNames = state.bootstrapInterfaceNames,
                                isRestarting = state.isRestarting,
                                onToggleDiscovery = { viewModel.toggleDiscovery() },
                                onAutoconnectCountChange = { viewModel.setAutoconnectCount(it) },
                            )
                        }

                        // Status summary (only if we have interfaces)
                        if (state.interfaces.isNotEmpty()) {
                            item {
                                DiscoveryStatusSummary(
                                    totalCount = state.interfaces.size,
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
                        }

                        // Show empty state or interfaces
                        if (state.interfaces.isEmpty()) {
                            item {
                                EmptyDiscoveredCard()
                            }
                        } else {
                            items(
                                state.interfaces,
                                key = { iface ->
                                    // networkId + endpoint + lastHeard timestamp for stable uniqueness across sorts
                                    "${iface.networkId}:${iface.reachableOn ?: ""}:${iface.port ?: ""}:${iface.lastHeard}"
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
                                            )
                                        } else {
                                            Toast
                                                .makeText(
                                                    context,
                                                    context.getString(R.string.discovered_interfaces_only_tcp_supported),
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
                                        val params =
                                            formatLoraParamsForClipboard(
                                                iface = iface,
                                                labels =
                                                    LoraClipboardLabels(
                                                        title = context.getString(R.string.discovered_interfaces_lora_params_from, iface.name),
                                                        separator = context.getString(R.string.discovered_interfaces_clipboard_separator),
                                                        frequency = context.getString(R.string.discovered_interfaces_frequency_label),
                                                        bandwidth = context.getString(R.string.discovered_interfaces_bandwidth_label),
                                                        spreadingFactor = context.getString(R.string.discovered_interfaces_spreading_factor_label),
                                                        codingRate = context.getString(R.string.discovered_interfaces_coding_rate_label),
                                                        modulation = context.getString(R.string.discovered_interfaces_modulation_label),
                                                    ),
                                            )
                                        clipboardManager.setText(AnnotatedString(params))
                                        Toast
                                            .makeText(
                                                context,
                                                context.getString(R.string.discovered_interfaces_lora_params_copied),
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
    bootstrapInterfaceNames: List<String> = emptyList(),
    isRestarting: Boolean = false,
    onToggleDiscovery: () -> Unit = {},
    onAutoconnectCountChange: (Int) -> Unit = {},
) {
    val isEnabled = isRuntimeEnabled || isSettingEnabled
    val enabledContentColor =
        if (isEnabled) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

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
                            text = stringResource(R.string.discovered_interfaces_discovery_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = enabledContentColor,
                        )
                        Text(
                            text =
                                if (isRestarting) {
                                    stringResource(R.string.discovered_interfaces_restarting)
                                } else if (isRuntimeEnabled) {
                                    stringResource(R.string.discovered_interfaces_active)
                                } else {
                                    stringResource(R.string.discovered_interfaces_disabled)
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
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            text = stringResource(R.string.discovered_interfaces_restarting_service),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
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
                                stringResource(R.string.discovered_interfaces_autoconnect_enabled, autoconnectCount)
                            } else {
                                stringResource(R.string.discovered_interfaces_autoconnect_disabled)
                            }
                        } else {
                            stringResource(R.string.discovered_interfaces_autoconnect_off)
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
                            text = stringResource(R.string.discovered_interfaces_autoconnect_limit),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = if (autoconnectCount == 0) stringResource(R.string.discovered_interfaces_off) else "$autoconnectCount",
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
                }
            }

            // Bootstrap interfaces section
            if (bootstrapInterfaceNames.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.discovered_interfaces_bootstrap_title),
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
                    text = stringResource(R.string.discovered_interfaces_bootstrap_hint),
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
        }
    }
}

/**
 * Card shown when no interfaces are discovered.
 */
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
                text = stringResource(R.string.discovered_interfaces_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.discovered_interfaces_empty_subtitle),
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
                    text = stringResource(R.string.discovered_interfaces_summary_total),
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
                    text = stringResource(R.string.discovered_interfaces_summary_available),
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
                    text = stringResource(R.string.discovered_interfaces_summary_unknown),
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
                    text = stringResource(R.string.discovered_interfaces_summary_stale),
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
    val yggdrasilLabel = stringResource(R.string.discovered_interfaces_yggdrasil)
    val neverLabel = stringResource(R.string.discovered_interfaces_never)
    val justNowLabel = stringResource(R.string.discovered_interfaces_just_now)
    val minAgoFormat = stringResource(R.string.discovered_interfaces_minutes_ago)
    val hoursAgoFormat = stringResource(R.string.discovered_interfaces_hours_ago)
    val daysAgoFormat = stringResource(R.string.discovered_interfaces_days_ago)
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
                    title = { Text(stringResource(R.string.discovered_interfaces_yggdrasil_network_title)) },
                    text = {
                        Text(
                            stringResource(R.string.discovered_interfaces_yggdrasil_network_message),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showNetworkInfoDialog = false }) {
                            Text(stringResource(R.string.my_identity_got_it))
                        }
                    },
                )
            }
            isI2p -> {
                AlertDialog(
                    onDismissRequest = { showNetworkInfoDialog = false },
                    title = { Text(stringResource(R.string.discovered_interfaces_i2p_network_title)) },
                    text = {
                        Text(
                            stringResource(R.string.discovered_interfaces_i2p_network_message),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showNetworkInfoDialog = false }) {
                            Text(stringResource(R.string.my_identity_got_it))
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
                                text = if (isYggdrasil) yggdrasilLabel else formatInterfaceTypeLabel(iface.type),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // Info icon for special networks (Yggdrasil, I2P)
                            if (isYggdrasil || isI2p) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = stringResource(R.string.discovered_interfaces_network_info),
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
                                    text = stringResource(R.string.discovered_interfaces_connected),
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
                                text = formatDiscoveredStatusLabel(iface.status),
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
                    text = stringResource(R.string.discovered_interfaces_transport, transportId.take(12)),
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
                    text =
                        stringResource(
                            R.string.discovered_interfaces_last_heard,
                            formatLastHeard(
                                timestamp = iface.lastHeard,
                                neverLabel = neverLabel,
                                justNowLabel = justNowLabel,
                                minAgoFormat = minAgoFormat,
                                hoursAgoFormat = hoursAgoFormat,
                                daysAgoFormat = daysAgoFormat,
                            ),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (iface.hops > 0) {
                    val hops = iface.hops
                    Text(
                        text =
                            if (hops == 1) {
                                stringResource(R.string.discovered_interfaces_hop_singular, hops)
                            } else {
                                stringResource(R.string.discovered_interfaces_hop_plural, hops)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

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
                    Text(stringResource(R.string.discovered_interfaces_add_to_config))
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
                        Text(stringResource(R.string.discovered_interfaces_copy_params))
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
                        Text(stringResource(R.string.discovered_interfaces_use_for_rnode))
                    }
                }
            }
        }
    }
}

/**
 * Details for TCP-based interfaces.
 */
@Composable
internal fun TcpInterfaceDetails(iface: DiscoveredInterface) {
    val portLabel = stringResource(R.string.discovered_interfaces_port)
    val hostPort =
        buildString {
            iface.reachableOn?.let { append(it) }
            iface.port?.let { port ->
                if (isNotEmpty()) append(":$port") else append("$portLabel $port")
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
    val openInMapsLabel = stringResource(R.string.discovered_interfaces_open_in_maps)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = openInMapsLabel,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        val locationText =
            buildString {
                append("%.4f, %.4f".format(latitude, longitude))
                height?.let { append(stringResource(R.string.discovered_interfaces_height_meters, it.toInt())) }
                distanceKm?.let { dist ->
                    append(" - ")
                    if (dist < 1.0) {
                        append(stringResource(R.string.discovered_interfaces_meters_away, (dist * 1000).toInt()))
                    } else {
                        append(stringResource(R.string.discovered_interfaces_km_away, String.format(Locale.getDefault(), "%.1f", dist)))
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
                    contentDescription = stringResource(R.string.discovered_interfaces_yggdrasil_network_icon),
                    modifier = modifier.size(size),
                    tint = tint,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = stringResource(R.string.discovered_interfaces_tcp_network_icon),
                    modifier = modifier.size(size),
                    tint = tint,
                )
            }
        }
        "I2PInterface" -> {
            // Use MDI incognito icon for I2P (anonymity network)
            val codepoint = MaterialDesignIcons.getCodepointOrNull("incognito")
            val i2pNetworkLabel = stringResource(R.string.discovered_interfaces_i2p_network_icon)
            if (codepoint != null) {
                Text(
                    text = codepoint,
                    fontFamily = MdiFont,
                    fontSize = (size.value * 1.2f).sp, // MDI icons render slightly smaller
                    color = tint,
                    modifier = modifier.semantics { contentDescription = i2pNetworkLabel },
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = i2pNetworkLabel,
                    modifier = modifier.size(size),
                    tint = tint,
                )
            }
        }
        "RNodeInterface", "WeaveInterface", "KISSInterface" -> {
            // Use Lucide Antenna for radio interfaces (matches PeerCard)
            Icon(
                imageVector = ImageVector.vectorResource(com.composables.icons.lucide.R.drawable.lucide_ic_antenna),
                contentDescription = stringResource(R.string.discovered_interfaces_radio_interface_icon),
                modifier = modifier.size(size),
                tint = tint,
            )
        }
        else -> {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.discovered_interfaces_unknown_interface_icon),
                modifier = modifier.size(size),
                tint = tint,
            )
        }
    }
}

/**
 * Format interface type for display.
 */
@Composable
internal fun formatInterfaceTypeLabel(type: String): String =
    when (type) {
        "TCPServerInterface" -> stringResource(R.string.discovered_interfaces_type_tcp_server)
        "TCPClientInterface" -> stringResource(R.string.discovered_interfaces_type_tcp_client)
        "BackboneInterface" -> stringResource(R.string.discovered_interfaces_type_backbone)
        "I2PInterface" -> stringResource(R.string.discovered_interfaces_type_i2p)
        "RNodeInterface" -> stringResource(R.string.discovered_interfaces_type_rnode)
        "WeaveInterface" -> stringResource(R.string.discovered_interfaces_type_weave)
        "KISSInterface" -> stringResource(R.string.discovered_interfaces_type_kiss)
        else -> type
    }

@Composable
internal fun formatDiscoveredStatusLabel(status: String): String =
    when (status) {
        "available" -> stringResource(R.string.discovered_interfaces_summary_available)
        "unknown" -> stringResource(R.string.discovered_interfaces_summary_unknown)
        "stale" -> stringResource(R.string.discovered_interfaces_summary_stale)
        else -> status.replaceFirstChar { it.uppercase() }
    }

/**
 * Format last heard timestamp as relative time.
 */
internal fun formatLastHeard(
    timestamp: Long,
    neverLabel: String,
    justNowLabel: String,
    minAgoFormat: String,
    hoursAgoFormat: String,
    daysAgoFormat: String,
): String {
    if (timestamp == 0L) return neverLabel

    val now = System.currentTimeMillis() / 1000
    val diff = now - timestamp

    return when {
        diff < 60 -> justNowLabel
        diff < 3600 -> String.format(minAgoFormat, diff / 60)
        diff < 86400 -> String.format(hoursAgoFormat, diff / 3600)
        diff < 604800 -> String.format(daysAgoFormat, diff / 86400)
        else -> {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(Date(timestamp * 1000))
        }
    }
}

/**
 * Format LoRa parameters for clipboard.
 */
internal data class LoraClipboardLabels(
    val title: String,
    val separator: String,
    val frequency: String,
    val bandwidth: String,
    val spreadingFactor: String,
    val codingRate: String,
    val modulation: String,
)

internal fun formatLoraParamsForClipboard(
    iface: DiscoveredInterface,
    labels: LoraClipboardLabels,
): String =
    buildString {
        appendLine(labels.title)
        appendLine(labels.separator)
        iface.frequency?.let { freq ->
            appendLine("${labels.frequency} ${freq / 1_000_000.0} MHz")
        }
        iface.bandwidth?.let { bw ->
            appendLine("${labels.bandwidth} ${bw / 1000} kHz")
        }
        iface.spreadingFactor?.let { sf ->
            appendLine("${labels.spreadingFactor} SF$sf")
        }
        iface.codingRate?.let { cr ->
            appendLine("${labels.codingRate} 4/$cr")
        }
        iface.modulation?.let { mod ->
            appendLine("${labels.modulation} $mod")
        }
    }.trim()
