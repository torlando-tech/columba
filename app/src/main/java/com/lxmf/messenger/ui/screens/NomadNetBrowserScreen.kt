package com.lxmf.messenger.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.ui.components.MicronPageContent
import com.lxmf.messenger.viewmodel.NomadNetBrowserViewModel
import com.lxmf.messenger.viewmodel.NomadNetBrowserViewModel.BrowserState
import com.lxmf.messenger.viewmodel.NomadNetBrowserViewModel.RenderingMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NomadNetBrowserScreen(
    destinationHash: String,
    onBackClick: () -> Unit,
    viewModel: NomadNetBrowserViewModel = hiltViewModel(),
) {
    val browserState by viewModel.browserState.collectAsState()
    val formFields by viewModel.formFields.collectAsState()
    val renderingMode by viewModel.renderingMode.collectAsState()
    val isIdentified by viewModel.isIdentified.collectAsState()
    val identifyInProgress by viewModel.identifyInProgress.collectAsState()
    val identifyError by viewModel.identifyError.collectAsState()
    val partialStates by viewModel.partialStates.collectAsState()
    val isDark = isSystemInDarkTheme()
    var showMenu by remember { mutableStateOf(false) }
    var showIdentifyConfirm by remember { mutableStateOf(false) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Load initial page
    LaunchedEffect(destinationHash) {
        if (browserState is BrowserState.Initial) {
            viewModel.loadPage(destinationHash)
        }
    }

    // Show identify errors via snackbar
    LaunchedEffect(identifyError) {
        identifyError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearIdentifyError()
        }
    }

    // Handle system back — go back in browser history first
    BackHandler(enabled = viewModel.canGoBack) {
        viewModel.goBack()
    }

    if (showIdentifyConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showIdentifyConfirm = false },
            icon = {
                Icon(
                    Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text("Identify to Node") },
            text = {
                Text(
                    "This will reveal your identity to the node operator. " +
                        "The page will refresh after identifying.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showIdentifyConfirm = false
                    viewModel.identifyToNode()
                }) {
                    Text("Identify")
                }
            },
            dismissButton = {
                TextButton(onClick = { showIdentifyConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "NomadNet Browser",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        val currentPath = (browserState as? BrowserState.PageLoaded)?.path
                        if (currentPath != null) {
                            Text(
                                text = currentPath,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!viewModel.goBack()) {
                            onBackClick()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (browserState is BrowserState.PageLoaded) {
                        IconButton(
                            onClick = { showIdentifyConfirm = true },
                            enabled = !isIdentified && !identifyInProgress,
                        ) {
                            Icon(
                                Icons.Default.Fingerprint,
                                contentDescription = if (isIdentified) "Identified" else "Identify to node",
                                tint =
                                    if (isIdentified) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                            )
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            RenderingMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        val label =
                                            when (mode) {
                                                RenderingMode.MONOSPACE_SCROLL -> "Monospace (scroll)"
                                                RenderingMode.MONOSPACE_ZOOM -> "Monospace (zoom)"
                                                RenderingMode.PROPORTIONAL_WRAP -> "Proportional (wrap)"
                                            }
                                        Text(label)
                                    },
                                    leadingIcon = {
                                        RadioButton(
                                            selected = renderingMode == mode,
                                            onClick = null,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
                                    onClick = {
                                        viewModel.setRenderingMode(mode)
                                        showMenu = false
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(if (isIdentified) "Identified" else "Identify to node")
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Fingerprint,
                                        contentDescription = null,
                                        tint =
                                            if (isIdentified) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                    )
                                },
                                enabled = !isIdentified && !identifyInProgress,
                                onClick = {
                                    showMenu = false
                                    showIdentifyConfirm = true
                                },
                            )
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        when (val state = browserState) {
            is BrowserState.Initial -> {
                // Nothing to show yet
            }

            is BrowserState.Loading -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = state.statusMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = { viewModel.cancelLoading() }) {
                        Text("Cancel")
                    }
                }
            }

            is BrowserState.PageLoaded -> {
                PullToRefreshBox(
                    isRefreshing = false,
                    onRefresh = { viewModel.refresh() },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                ) {
                    if (renderingMode == RenderingMode.MONOSPACE_SCROLL) {
                        // BoxWithConstraints captures viewport width before
                        // horizontalScroll unbounds it — needed for text centering
                        BoxWithConstraints(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .clipToBounds()
                                    .pointerInput(Unit) {
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            do {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                                if (event.changes.size >= 2) {
                                                    val zoom = event.calculateZoom()
                                                    if (zoom != 1f) {
                                                        zoomScale = (zoomScale * zoom).coerceIn(0.5f, 3f)
                                                        event.changes.forEach { it.consume() }
                                                    }
                                                }
                                            } while (event.changes.any { it.pressed })
                                        }
                                    },
                        ) {
                            // Subtract horizontal padding (8.dp * 2) so lines fill viewport
                            val viewportLineWidth = maxWidth - 16.dp
                            Column(
                                modifier =
                                    Modifier
                                        .verticalScroll(rememberScrollState())
                                        .horizontalScroll(rememberScrollState())
                                        .layout { measurable, constraints ->
                                            val placeable = measurable.measure(constraints)
                                            val scaledWidth = (placeable.width * zoomScale).roundToInt()
                                            val scaledHeight = (placeable.height * zoomScale).roundToInt()
                                            layout(scaledWidth, scaledHeight) {
                                                placeable.placeRelativeWithLayer(0, 0) {
                                                    scaleX = zoomScale
                                                    scaleY = zoomScale
                                                    transformOrigin = TransformOrigin(0f, 0f)
                                                }
                                            }
                                        }.padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                MicronPageContent(
                                    document = state.document,
                                    formFields = formFields,
                                    renderingMode = renderingMode,
                                    isDark = isDark,
                                    onLinkClick = { destination, fieldNames ->
                                        viewModel.navigateToLink(destination, fieldNames)
                                    },
                                    onFieldUpdate = { name, value ->
                                        viewModel.updateField(name, value)
                                    },
                                    minLineWidth = viewportLineWidth,
                                    partialStates = partialStates,
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            val lines = state.document.lines
                            items(
                                count = lines.size,
                            ) { index ->
                                MicronPageContent(
                                    document =
                                        com.lxmf.messenger.micron
                                            .MicronDocument(listOf(lines[index])),
                                    formFields = formFields,
                                    renderingMode = renderingMode,
                                    isDark = isDark,
                                    onLinkClick = { destination, fieldNames ->
                                        viewModel.navigateToLink(destination, fieldNames)
                                    },
                                    onFieldUpdate = { name, value ->
                                        viewModel.updateField(name, value)
                                    },
                                    partialStates = partialStates,
                                    lineIndexOffset = index,
                                )
                            }
                        }
                    }
                }
            }

            is BrowserState.Error -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Failed to load page",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { viewModel.loadPage(destinationHash) }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry")
                    }
                }
            }
        }
    }
}
