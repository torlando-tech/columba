package com.lxmf.messenger.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.ui.components.MicronPageContent
import com.lxmf.messenger.viewmodel.NomadNetBrowserViewModel
import com.lxmf.messenger.viewmodel.NomadNetBrowserViewModel.BrowserState
import com.lxmf.messenger.viewmodel.NomadNetBrowserViewModel.NavigationEvent
import com.lxmf.messenger.viewmodel.NomadNetBrowserViewModel.RenderingMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NomadNetBrowserScreen(
    destinationHash: String,
    initialPath: String = "/page/index.mu",
    onBackClick: () -> Unit,
    onOpenConversation: (String) -> Unit = {},
    viewModel: NomadNetBrowserViewModel = hiltViewModel(),
) {
    val browserState by viewModel.browserState.collectAsState()
    val formFields by viewModel.formFields.collectAsState()
    val renderingMode by viewModel.renderingMode.collectAsState()
    val isIdentified by viewModel.isIdentified.collectAsState()
    val identifyInProgress by viewModel.identifyInProgress.collectAsState()
    val identifyError by viewModel.identifyError.collectAsState()
    val partialStates by viewModel.partialStates.collectAsState()
    val isPullRefreshing by viewModel.isPullRefreshing.collectAsState()
    val canGoBack by viewModel.canGoBack.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    var showIdentifyConfirm by remember { mutableStateOf(false) }
    val currentPage =
        (browserState as? NomadNetBrowserViewModel.BrowserState.PageLoaded)
            ?.let { "${it.nodeHash}:${it.path}" }
    var zoomScale by remember(currentPage) { mutableFloatStateOf(1f) }
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current

    // URL bar state
    var isEditingUrl by remember { mutableStateOf(false) }
    var urlFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    val urlFocusRequester = remember { FocusRequester() }

    // Load initial page
    LaunchedEffect(destinationHash, initialPath) {
        if (browserState is BrowserState.Initial) {
            viewModel.loadPage(destinationHash, initialPath)
        }
    }

    // Collect navigation events (e.g., lxmf@ links)
    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { event ->
            when (event) {
                is NavigationEvent.OpenConversation -> onOpenConversation(event.destinationHash)
            }
        }
    }

    // Show identify errors via snackbar
    LaunchedEffect(identifyError) {
        identifyError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearIdentifyError()
        }
    }

    // Handle system back — cancel URL editing first, then browser history
    BackHandler(enabled = isEditingUrl || canGoBack) {
        if (isEditingUrl) {
            isEditingUrl = false
            focusManager.clearFocus()
        } else {
            viewModel.goBack()
        }
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
                    // Derive URL from compose state (not viewModel.getCurrentUrl())
                    // so Compose tracks the dependency and recomposes the title
                    val currentUrl =
                        (browserState as? BrowserState.PageLoaded)?.let {
                            "${it.nodeHash}:${it.path}"
                        }
                    if (currentUrl != null || isEditingUrl) {
                        // Address bar — rounded container with contrasting background
                        BasicTextField(
                            value = urlFieldValue,
                            onValueChange = { urlFieldValue = it },
                            singleLine = true,
                            textStyle =
                                TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions =
                                KeyboardActions(
                                    onGo = {
                                        viewModel.navigateToUrl(urlFieldValue.text)
                                        isEditingUrl = false
                                        focusManager.clearFocus()
                                    },
                                ),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(urlFocusRequester)
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused && !isEditingUrl) {
                                            isEditingUrl = true
                                            val text = currentUrl ?: ""
                                            urlFieldValue =
                                                TextFieldValue(
                                                    text = text,
                                                    selection = TextRange(0, text.length),
                                                )
                                        } else if (!focusState.isFocused && isEditingUrl) {
                                            isEditingUrl = false
                                        }
                                    },
                            decorationBox = { innerTextField ->
                                Box(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .background(
                                                MaterialTheme.colorScheme.surface,
                                                RoundedCornerShape(20.dp),
                                            ).padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    innerTextField()
                                }
                            },
                        )
                    } else {
                        Text(
                            text = "NomadNet Browser",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isEditingUrl) {
                            isEditingUrl = false
                            focusManager.clearFocus()
                        } else if (!viewModel.goBack()) {
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
                            // Copy URL — derive from compose state
                            val shareableUrl =
                                (browserState as? BrowserState.PageLoaded)?.let {
                                    "nomadnetwork://${it.nodeHash}:${it.path}"
                                }
                            if (shareableUrl != null) {
                                DropdownMenuItem(
                                    text = { Text("Copy URL") },
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(shareableUrl))
                                        showMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    onClick = {
                                        val intent =
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, shareableUrl)
                                            }
                                        context.startActivity(Intent.createChooser(intent, "Share NomadNet URL"))
                                        showMenu = false
                                    },
                                )
                                HorizontalDivider()
                            }

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
                // Update URL field when page changes (only if not editing)
                LaunchedEffect(state.nodeHash, state.path) {
                    if (!isEditingUrl) {
                        val url = "${state.nodeHash}:${state.path}"
                        urlFieldValue = TextFieldValue(url)
                    }
                }

                PullToRefreshBox(
                    isRefreshing = isPullRefreshing,
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
                                            .MicronDocument(
                                                lines = listOf(lines[index]),
                                                pageBackground = state.document.pageBackground,
                                                pageForeground = state.document.pageForeground,
                                            ),
                                    formFields = formFields,
                                    renderingMode = renderingMode,
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
                    Button(onClick = { viewModel.retry() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry")
                    }
                }
            }
        }
    }
}
