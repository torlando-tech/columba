package network.columba.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import network.columba.app.R
import network.columba.app.nomadnet.DEFAULT_PAGE_PATH
import network.columba.app.nomadnet.NomadNetAddressTarget
import network.columba.app.nomadnet.NomadNetSavedSite
import network.columba.app.nomadnet.NomadNetShortcutPinner
import network.columba.app.nomadnet.NomadNetShortcuts
import network.columba.app.nomadnet.parseNomadNetAddress
import network.columba.app.ui.components.ProfileIcon
import network.columba.app.viewmodel.NomadNetNodeSummary
import network.columba.app.viewmodel.NomadNetSitesViewModel

/**
 * The NomadNet tab home: an address field, the page the user last had open
 * ("Continue"), and the saved pages list.
 *
 * The tab lands here instead of auto-reopening the last site, so the tab is
 * always a list the user chooses from and Back from the browser is a plain pop
 * onto this screen. Nothing loads a page until the user picks one.
 */
@Composable
fun NomadNetHomeScreen(
    lastPageNodeHash: String?,
    lastPagePath: String?,
    onOpenPage: (nodeHash: String, path: String, requestData: String?) -> Unit,
    onContinuePage: (nodeHash: String, path: String) -> Unit,
    onOpenConversation: (String) -> Unit,
    viewModel: NomadNetSitesViewModel = hiltViewModel(),
) {
    val savedSites by viewModel.savedSites.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val topInset = topInsetHeight()
    val bottomBarHeight = bottomBarOverlayHeight()

    val pinToHomeScreen: (NomadNetSavedSite) -> Unit = { site ->
        val spec =
            NomadNetShortcuts.spec(
                nodeHash = site.nodeHash,
                persistPath = site.persistPath,
                pageTitle = site.label,
                requestData = site.requestData,
            )
        if (!NomadNetShortcutPinner.requestPin(context, spec)) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.nomadnet_add_to_home_screen_unsupported))
            }
        }
    }
    val removeSite: (NomadNetSavedSite) -> Unit = { site ->
        viewModel.removeSaved(site)
        coroutineScope.launch {
            val result =
                snackbarHostState.showSnackbar(
                    message = context.getString(R.string.nomadnet_saved_site_removed),
                    actionLabel = context.getString(R.string.nomadnet_undo),
                    // Undo makes the snackbar indefinite; without this the only way
                    // out of it is undoing the removal.
                    withDismissAction = true,
                )
            if (result == SnackbarResult.ActionPerformed) viewModel.restoreSaved(site)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .imePadding(),
            contentPadding =
                PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    // MainActivity draws edge-to-edge and ignores its own inner
                    // padding, so this screen must clear the status bar itself;
                    // 16.dp on top of it keeps the title off the top edge.
                    top = 16.dp + topInset,
                    // Clear the bottom navigation bar that overlays tab screens.
                    bottom = 16.dp + bottomBarHeight,
                ),
        ) {
            item(key = "header") {
                NomadNetAddressHeader(
                    onSubmit = { input ->
                        when (val target = parseNomadNetAddress(input)) {
                            null -> Unit
                            is NomadNetAddressTarget.Page -> onOpenPage(target.nodeHash, target.path, null)
                            is NomadNetAddressTarget.Conversation -> onOpenConversation(target.nodeHash)
                        }
                    },
                )
            }

            if (!lastPageNodeHash.isNullOrBlank()) {
                item(key = "continue") {
                    SectionTitle(stringResource(R.string.nomadnet_home_continue_title))
                    ContinueRow(
                        nodeHash = lastPageNodeHash,
                        path = lastPagePath ?: DEFAULT_PAGE_PATH,
                        viewModel = viewModel,
                        onClick = { onContinuePage(lastPageNodeHash, lastPagePath ?: DEFAULT_PAGE_PATH) },
                    )
                }
            }

            item(key = "saved-title") {
                SectionTitle(stringResource(R.string.nomadnet_saved_sites_title), count = savedSites.size)
            }
            if (savedSites.isEmpty()) {
                item(key = "saved-empty") {
                    Text(
                        text = stringResource(R.string.nomadnet_saved_sites_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(savedSites, key = { site -> site.key }) { site ->
                    SavedSiteRow(
                        site = site,
                        viewModel = viewModel,
                        onClick = { onOpenPage(site.nodeHash, site.persistPath, site.requestData) },
                        onPinToHomeScreen = { pinToHomeScreen(site) },
                        onRemove = { removeSite(site) },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    // Above the app's bottom bar, which the parent Scaffold draws
                    // over the tab content.
                    .padding(bottom = bottomBarHeight + 8.dp)
                    .padding(horizontal = 12.dp),
        )
    }
}

/**
 * Tab title plus the "enter a node address" field. Submits on the keyboard's
 * Go action or the forward arrow; no auto-focus, so opening the tab never pops
 * the keyboard.
 */
@Composable
private fun NomadNetAddressHeader(onSubmit: (String) -> Unit) {
    var address by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.nomadnet_home_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        BasicTextField(
            value = address,
            onValueChange = { address = it },
            singleLine = true,
            textStyle =
                TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit(address) }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp),
                            ).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (address.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.nomadnet_home_address_hint),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                        IconButton(onClick = { onSubmit(address) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = stringResource(R.string.nomadnet_home_go),
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(
    title: String,
    count: Int? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (count != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Last-opened page shortcut: name from the announce/contact, path beneath. */
@Composable
private fun ContinueRow(
    nodeHash: String,
    path: String,
    viewModel: NomadNetSitesViewModel,
    onClick: () -> Unit,
) {
    val summaryFlow = remember(nodeHash) { viewModel.nodeSummary(nodeHash) }
    val summary by summaryFlow.collectAsState(initial = null)
    NodeRow(
        nodeHash = nodeHash,
        headline = summary?.displayName ?: shortHash(nodeHash),
        supporting = path.substringBefore('`'),
        summary = summary,
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = onClick,
    )
}

/** One saved page: tap opens it, long-press or the menu pins/removes it. */
@Composable
private fun SavedSiteRow(
    site: NomadNetSavedSite,
    viewModel: NomadNetSitesViewModel,
    onClick: () -> Unit,
    onPinToHomeScreen: () -> Unit,
    onRemove: () -> Unit,
) {
    val summaryFlow = remember(site.nodeHash) { viewModel.nodeSummary(site.nodeHash) }
    val summary by summaryFlow.collectAsState(initial = null)
    var menuOpen by remember { mutableStateOf(false) }
    val supporting =
        listOfNotNull(site.label, site.persistPath.substringBefore('`'))
            .distinct()
            .joinToString(" · ")

    Box {
        NodeRow(
            nodeHash = site.nodeHash,
            headline = summary?.displayName ?: shortHash(site.nodeHash),
            supporting = supporting,
            summary = summary,
            trailing = {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.nomadnet_menu_cd),
                    )
                }
            },
            onClick = onClick,
            onLongClick = { menuOpen = true },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            NomadNetSavedSiteMenu(
                onOpen = {
                    menuOpen = false
                    onClick()
                },
                onPinToHomeScreen = {
                    menuOpen = false
                    onPinToHomeScreen()
                },
                onRemove = {
                    menuOpen = false
                    onRemove()
                },
            )
        }
    }
}

/**
 * Row menu for one saved page: open it, pin it to the launcher, or remove it
 * from the list. Dismissing is the caller's job (it owns the menu state).
 */
@Composable
internal fun NomadNetSavedSiteMenu(
    onOpen: () -> Unit,
    onPinToHomeScreen: () -> Unit,
    onRemove: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.nomadnet_saved_site_open)) },
        onClick = onOpen,
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.nomadnet_add_to_home_screen)) },
        onClick = onPinToHomeScreen,
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.nomadnet_saved_site_remove)) },
        onClick = onRemove,
    )
}

/** Shared row chrome: node icon, two-line text, optional node-type badge. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeRow(
    nodeHash: String,
    headline: String,
    supporting: String,
    summary: NomadNetNodeSummary?,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileIcon(
            iconName = summary?.iconName,
            foregroundColor = summary?.iconForegroundColor,
            backgroundColor = summary?.iconBackgroundColor,
            size = 40.dp,
            fallbackHash = remember(nodeHash) { NomadNetShortcutPinner.identiconBytes(nodeHash) },
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                summary?.nodeType?.let { nodeType ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = nodeType,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

/**
 * Status-bar inset the activity does not reserve for this screen.
 *
 * MainActivity draws edge-to-edge and its Scaffold ignores its inner padding,
 * so a tab screen without its own Scaffold starts at the top window edge: this
 * screen must clear the status bar (and any display cutout in it) itself or the
 * title sits under the clock.
 */
@Composable
private fun topInsetHeight(): Dp {
    val density = LocalDensity.current
    return with(density) { WindowInsets.statusBars.getTop(density).toDp() }
}

/**
 * Height the app's bottom navigation bar overlays this screen by.
 *
 * Activity's Scaffold draws the bar over its content (it ignores its own inner
 * padding), so this screen must clear the bar's full height plus the system
 * navigation-bar inset the bar reserves for itself. The bar is a Material 3
 * NavigationBar, whose content height is fixed at 80dp; [bottomBarClearance]
 * cannot be used here because it deliberately excludes the system inset, which
 * only screens with their own Scaffold already reserve.
 */
@Composable
private fun bottomBarOverlayHeight(): Dp {
    val density = LocalDensity.current
    val systemInset = with(density) { WindowInsets.systemBars.getBottom(density).toDp() }
    return APP_BOTTOM_BAR_HEIGHT + systemInset
}

/** Material 3 NavigationBar's fixed content height, excluding window insets. */
private val APP_BOTTOM_BAR_HEIGHT = 80.dp

/** First 8 characters of a destination hash, the app's convention for unnamed nodes. */
private fun shortHash(nodeHash: String): String = nodeHash.take(8)
