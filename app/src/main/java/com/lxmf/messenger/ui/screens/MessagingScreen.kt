package com.lxmf.messenger.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lxmf.messenger.service.SyncProgress
import com.lxmf.messenger.service.SyncResult
import com.lxmf.messenger.ui.components.FileAttachmentCard
import com.lxmf.messenger.ui.components.FileAttachmentOptionsSheet
import com.lxmf.messenger.ui.components.FileAttachmentPreviewRow
import com.lxmf.messenger.ui.components.FullEmojiPickerDialog
import com.lxmf.messenger.ui.components.ImageQualitySelectionDialog
import com.lxmf.messenger.ui.components.LocationPermissionBottomSheet
import com.lxmf.messenger.ui.components.QuickShareLocationBottomSheet
import com.lxmf.messenger.ui.components.ReactionDisplayRow
import com.lxmf.messenger.ui.components.ReactionModeOverlay
import com.lxmf.messenger.ui.components.ReplyInputBar
import com.lxmf.messenger.ui.components.ReplyPreviewBubble
import com.lxmf.messenger.ui.components.StarToggleButton
import com.lxmf.messenger.ui.components.SwipeableMessageBubble
import com.lxmf.messenger.ui.components.SyncStatusBottomSheet
import com.lxmf.messenger.ui.model.LocationSharingState
import com.lxmf.messenger.ui.theme.MeshConnected
import com.lxmf.messenger.ui.theme.MeshOffline
import com.lxmf.messenger.util.AnimatedImageLoader
import com.lxmf.messenger.util.FileAttachment
import com.lxmf.messenger.util.FileUtils
import com.lxmf.messenger.util.ImageUtils
import com.lxmf.messenger.util.LocationPermissionManager
import com.lxmf.messenger.util.formatRelativeTime
import com.lxmf.messenger.util.validation.ValidationConstants
import com.lxmf.messenger.viewmodel.ContactToggleResult
import com.lxmf.messenger.viewmodel.MessagingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MessagingScreen(
    destinationHash: String,
    peerName: String,
    onBackClick: () -> Unit,
    onPeerClick: () -> Unit = {},
    onViewMessageDetails: (messageId: String) -> Unit = {},
    viewModel: MessagingViewModel = hiltViewModel(),
) {
    val pagingItems = viewModel.messages.collectAsLazyPagingItems()
    val announceInfo by viewModel.announceInfo.collectAsStateWithLifecycle()
    val conversationLinkState by viewModel.conversationLinkState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var messageText by remember { mutableStateOf("") }

    // Image selection state
    val context = androidx.compose.ui.platform.LocalContext.current
    val selectedImageData by viewModel.selectedImageData.collectAsStateWithLifecycle()
    val selectedImageFormat by viewModel.selectedImageFormat.collectAsStateWithLifecycle()
    val selectedImageIsAnimated by viewModel.selectedImageIsAnimated.collectAsStateWithLifecycle()
    val isProcessingImage by viewModel.isProcessingImage.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val isContactSaved by viewModel.isContactSaved.collectAsStateWithLifecycle()
    var showSyncStatusSheet by remember { mutableStateOf(false) }
    val syncStatusSheetState = rememberModalBottomSheetState()

    // File attachment state
    val selectedFileAttachments by viewModel.selectedFileAttachments.collectAsStateWithLifecycle()
    val totalAttachmentSize by viewModel.totalAttachmentSize.collectAsStateWithLifecycle()
    val isProcessingFile by viewModel.isProcessingFile.collectAsStateWithLifecycle()
    val isSending by viewModel.isSending.collectAsStateWithLifecycle()

    // Observe loaded image IDs to trigger recomposition when images become available
    val loadedImageIds by viewModel.loadedImageIds.collectAsStateWithLifecycle()
    // Map of decoded images (includes raw bytes for animated GIFs)
    val decodedImages by viewModel.decodedImages.collectAsStateWithLifecycle()

    // Location sharing state
    val locationSharingState by viewModel.locationSharingState.collectAsStateWithLifecycle()
    var showShareLocationSheet by remember { mutableStateOf(false) }
    val shareLocationSheetState = rememberModalBottomSheetState()
    var showLocationPermissionSheet by remember { mutableStateOf(false) }
    val locationPermissionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showStopSharingDialog by remember { mutableStateOf(false) }

    // Location permission launcher
    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            val granted = permissions.values.all { it }
            if (granted) {
                // Permission granted, now show the share location sheet
                showShareLocationSheet = true
            }
        }

    // Reply state
    val pendingReplyTo by viewModel.pendingReplyTo.collectAsStateWithLifecycle()

    // Reply preview cache - maps message ID to its loaded reply preview
    val replyPreviewCache by viewModel.replyPreviewCache.collectAsStateWithLifecycle()

    // Reaction picker state (myIdentityHash is still used for highlighting own reactions)
    val myIdentityHash by viewModel.myIdentityHash.collectAsStateWithLifecycle()

    // Reaction mode state (for overlay display)
    val reactionModeState by viewModel.reactionModeState.collectAsStateWithLifecycle()

    // Track message positions for jump-to-original functionality
    val messagePositions = remember { mutableStateMapOf<String, Int>() }

    // Lifecycle-aware coroutine scope for image and file processing
    val scope = rememberCoroutineScope()

    // Image quality selection dialog state
    val qualitySelectionState by viewModel.qualitySelectionState.collectAsStateWithLifecycle()

    // Current link state for showing path info in quality dialog
    val currentLinkState by viewModel.currentLinkState.collectAsStateWithLifecycle()

    // Observe manual sync results and show Toast
    LaunchedEffect(Unit) {
        viewModel.manualSyncResult.collect { result ->
            val message =
                when (result) {
                    is SyncResult.Success ->
                        if (result.messagesReceived > 0) {
                            "Sync complete: ${result.messagesReceived} new messages"
                        } else {
                            "Sync complete"
                        }
                    is SyncResult.Error -> "Sync failed: ${result.message}"
                    is SyncResult.NoRelay -> "No relay configured"
                    is SyncResult.Timeout -> "Sync timed out"
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Observe contact toggle results and show Toast
    LaunchedEffect(Unit) {
        viewModel.contactToggleResult.collect { result ->
            val message =
                when (result) {
                    is ContactToggleResult.Added -> "Saved $peerName to Contacts"
                    is ContactToggleResult.Removed -> "Removed $peerName from Contacts"
                    is ContactToggleResult.Error -> result.message
                }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // Observe file attachment errors and show Toast
    LaunchedEffect(Unit) {
        viewModel.fileAttachmentError.collect { errorMessage ->
            Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
        }
    }

    // Image picker launcher - uses adaptive compression with warning dialog
    val imageLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
        ) { uri: android.net.Uri? ->
            uri?.let {
                viewModel.processImageWithCompression(context, it)
            }
        }

    // File picker launcher
    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            uris.forEach { uri ->
                viewModel.setProcessingFile(true)
                scope.launch(Dispatchers.IO) {
                    val result = FileUtils.readFileFromUriWithResult(context, uri)
                    withContext(Dispatchers.Main) {
                        when (result) {
                            is FileUtils.FileReadResult.Success -> {
                                viewModel.addFileAttachment(result.attachment)
                            }
                            is FileUtils.FileReadResult.FileTooLarge -> {
                                val maxSizeKb = result.maxSize / 1024
                                val actualSizeKb = result.actualSize / 1024
                                Toast.makeText(
                                    context,
                                    "File too large (${actualSizeKb}KB). Max size is ${maxSizeKb}KB.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            is FileUtils.FileReadResult.Error -> {
                                Toast.makeText(
                                    context,
                                    "Failed to attach file: ${result.message}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                        viewModel.setProcessingFile(false)
                    }
                }
            }
        }

    // State for file attachment options bottom sheet
    var showFileOptionsSheet by remember { mutableStateOf(false) }
    var selectedFileInfo by remember { mutableStateOf<Triple<String, Int, String>?>(null) }

    // State for saving received file attachments
    var pendingFileSave by remember { mutableStateOf<Triple<String, Int, String>?>(null) }

    // File save launcher (CreateDocument)
    val fileSaveLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("*/*"),
        ) { uri ->
            uri?.let { destinationUri ->
                pendingFileSave?.let { (messageId, fileIndex, _) ->
                    scope.launch(Dispatchers.IO) {
                        val success =
                            viewModel.saveReceivedFileAttachment(
                                context,
                                messageId,
                                fileIndex,
                                destinationUri,
                            )
                        withContext(Dispatchers.Main) {
                            val message = if (success) "File saved" else "Failed to save file"
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            pendingFileSave = null
        }

    // Clipboard for copy functionality
    val clipboardManager = LocalClipboardManager.current

    // Keyboard controller for dismissing keyboard when entering reaction mode
    val keyboardController = LocalSoftwareKeyboardController.current

    // Track IME (keyboard) visibility
    val density = LocalDensity.current
    val imeBottomInset = WindowInsets.ime.getBottom(density)

    // Track if we've done the initial scroll for this conversation
    // Resets when switching to a different conversation
    var hasScrolledToBottom by remember(destinationHash) { mutableStateOf(false) }

    // Detect if user is at the bottom (index 0 with reverseLayout)
    // Wrapped in derivedStateOf to avoid recomposition on every scroll
    // Note: Use <= 1 instead of == 0 because when a new message arrives, it takes index 0
    // and the previously-newest message shifts to index 1 BEFORE the scroll effect runs
    val isAtBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex <= 1 }
    }

    // PERFORMANCE FIX: Track the ID of the newest message (at index 0) instead of itemCount
    // This only changes when a NEW message arrives, NOT when pagination loads old messages
    // Fixes: (1) scroll-to-beginning bug, (2) unnecessary LaunchedEffect triggers during scroll
    val newestMessageId by remember {
        derivedStateOf {
            pagingItems.itemSnapshotList.firstOrNull()?.id
        }
    }

    // Initial scroll to bottom when messages first load for a conversation
    // This ensures opening a conversation always shows the most recent messages
    // Uses newestMessageId instead of itemCount to avoid triggering on pagination
    LaunchedEffect(destinationHash, newestMessageId) {
        if (newestMessageId != null && !hasScrolledToBottom) {
            listState.scrollToItem(0) // Instant scroll to index 0 (newest message)
            hasScrolledToBottom = true
        }
    }

    // Smart auto-scroll when new messages arrive:
    // - Always scroll when YOU send a message (to show your sent message)
    // - Only scroll on received messages if you're already at bottom (don't interrupt reading)
    // CRITICAL FIX: Uses newestMessageId instead of itemCount
    // - itemCount changes when loading old messages during scroll → caused snap-back bug
    // - newestMessageId only changes when a NEW message arrives → correct behavior
    LaunchedEffect(newestMessageId) {
        if (newestMessageId != null && hasScrolledToBottom) {
            // Check if we should auto-scroll for this new message
            val newestMessage = pagingItems.itemSnapshotList.firstOrNull()
            val shouldScroll =
                if (newestMessage?.isFromMe == true) {
                    // Always scroll to show your own sent messages
                    true
                } else {
                    // Only scroll for received messages if already at bottom
                    isAtBottom
                }

            if (shouldScroll) {
                listState.scrollToItem(0) // Instant scroll to index 0 (newest message)
            }
        }
    }

    // Auto-scroll to bottom when keyboard appears - only if user is at bottom
    // Instant scroll for responsive feel when focusing the input field
    LaunchedEffect(imeBottomInset, newestMessageId) {
        if (imeBottomInset > 0 && newestMessageId != null && isAtBottom) {
            listState.scrollToItem(0) // Instant scroll to index 0 (newest message)
        }
    }

    // Load messages for this peer
    LaunchedEffect(destinationHash) {
        viewModel.loadMessages(destinationHash, peerName)
    }

    // Mark conversation as read continuously while viewing
    // Uses newestMessageId to only trigger on new messages, not pagination
    LaunchedEffect(destinationHash, newestMessageId) {
        if (newestMessageId != null) {
            viewModel.markAsRead(destinationHash)
        }
    }

    // Handle back button when reaction mode is active
    BackHandler(enabled = reactionModeState != null) {
        viewModel.exitReactionMode()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.clickable(onClick = onPeerClick),
                    ) {
                        Text(
                            text = peerName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        // Online status indicator - considers active link OR recent announce
                        val lastSeen = announceInfo?.lastSeenTimestamp
                        val hasActiveLink = conversationLinkState?.isActive == true
                        val isEstablishing = conversationLinkState?.isEstablishing == true
                        val hasRecentAnnounce =
                            lastSeen != null &&
                                System.currentTimeMillis() - lastSeen < (5 * 60 * 1000L) // 5 minutes
                        val isOnline = hasActiveLink || hasRecentAnnounce

                        // Debug logging
                        android.util.Log.d(
                            "MessagingScreen",
                            "Online indicator: hasActiveLink=$hasActiveLink, isEstablishing=$isEstablishing, hasRecentAnnounce=$hasRecentAnnounce, linkState=$conversationLinkState",
                        )

                        if (lastSeen != null || hasActiveLink || isEstablishing) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                // Status dot - color and animation based on state
                                if (isEstablishing) {
                                    // Pulsing dot when establishing
                                    val infiniteTransition = rememberInfiniteTransition(label = "establishing")
                                    val alpha by infiniteTransition.animateFloat(
                                        initialValue = 0.3f,
                                        targetValue = 1f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(700, easing = LinearEasing),
                                            repeatMode = RepeatMode.Reverse,
                                        ),
                                        label = "pulse",
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Circle,
                                        contentDescription = null,
                                        tint = MeshConnected.copy(alpha = alpha),
                                        modifier = Modifier.size(8.dp),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Circle,
                                        contentDescription = null,
                                        tint = if (isOnline) MeshConnected else MeshOffline,
                                        modifier = Modifier.size(8.dp),
                                    )
                                }

                                // Status text
                                val statusText = when {
                                    isEstablishing -> "Connecting..."
                                    hasActiveLink -> "Online"
                                    hasRecentAnnounce -> "Online"
                                    lastSeen != null -> formatRelativeTime(lastSeen)
                                    else -> ""
                                }
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                // Link indicator icon when link is active
                                if (hasActiveLink) {
                                    Icon(
                                        imageVector = Icons.Default.Link,
                                        contentDescription = "Active link",
                                        tint = MeshConnected,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    // Location sharing button
                    IconButton(
                        onClick = {
                            // Check if we're actively sharing with this peer
                            val isSharingWithPeer =
                                locationSharingState == LocationSharingState.SHARING_WITH_THEM ||
                                    locationSharingState == LocationSharingState.MUTUAL

                            if (isSharingWithPeer) {
                                // Show confirmation to stop sharing
                                showStopSharingDialog = true
                            } else if (LocationPermissionManager.hasPermission(context)) {
                                showShareLocationSheet = true
                            } else {
                                showLocationPermissionSheet = true
                            }
                        },
                    ) {
                        Icon(
                            imageVector =
                                if (locationSharingState != LocationSharingState.NONE) {
                                    Icons.Default.LocationOn
                                } else {
                                    Icons.Outlined.LocationOn
                                },
                            contentDescription = "Share location",
                            tint =
                                if (locationSharingState != LocationSharingState.NONE) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }

                    // Star toggle button for contact status
                    StarToggleButton(
                        isStarred = isContactSaved,
                        onClick = { viewModel.toggleContact() },
                    )

                    // Sync button - shows spinner during sync, tapping opens status sheet
                    IconButton(
                        onClick = {
                            if (isSyncing) {
                                showSyncStatusSheet = true
                            } else {
                                viewModel.syncFromPropagationNode()
                            }
                        },
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Sync messages",
                            )
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            )

            // Messages + Input area using Google's official pattern
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Apply IME padding to container
                        .imePadding(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                ) {
                    if (pagingItems.itemCount == 0) {
                        EmptyMessagesState()
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding =
                                PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 16.dp,
                                    // Space for input bar
                                    bottom = 16.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            // Messages anchored to bottom (industry standard)
                            reverseLayout = true,
                        ) {
                            // Paging3 infinite scroll: loads 30 messages initially,
                            // then loads more as user scrolls up
                            // DB returns DESC (newest first), reverseLayout shows newest at bottom
                            items(
                                count = pagingItems.itemCount,
                                key = pagingItems.itemKey { message -> message.id },
                                // All items are message bubbles
                                contentType = { "message" },
                            ) { index ->
                                val message = pagingItems[index]
                                if (message != null) {
                                    // Track message position for jump-to-original
                                    messagePositions[message.id] = index

                                    // Async image loading: check if this message has an uncached image
                                    // Using loadedImageIds in the key triggers recomposition when
                                    // the image is decoded and cached
                                    val needsImageLoading =
                                        message.hasImageAttachment &&
                                            message.decodedImage == null &&
                                            !loadedImageIds.contains(message.id)

                                    // Trigger async image loading if needed
                                    LaunchedEffect(message.id, needsImageLoading) {
                                        if (needsImageLoading && message.fieldsJson != null) {
                                            viewModel.loadImageAsync(message.id, message.fieldsJson)
                                        }
                                    }

                                    // Async reply preview loading: check if this message has a reply
                                    // that needs loading
                                    val needsReplyPreviewLoading =
                                        message.replyToMessageId != null &&
                                            !replyPreviewCache.containsKey(message.id)

                                    // Trigger async loading if needed
                                    LaunchedEffect(message.id, needsReplyPreviewLoading) {
                                        if (needsReplyPreviewLoading) {
                                            message.replyToMessageId?.let { replyToId ->
                                                viewModel.loadReplyPreviewAsync(message.id, replyToId)
                                            }
                                        }
                                    }

                                    // Get decoded image result (includes animated GIF data)
                                    val decodedResult = decodedImages[message.id]
                                    val cachedImage =
                                        decodedResult?.bitmap
                                            ?: if (message.decodedImage == null && loadedImageIds.contains(message.id)) {
                                                com.lxmf.messenger.ui.model.ImageCache.get(message.id)
                                            } else {
                                                message.decodedImage
                                            }

                                    // Get cached reply preview if it was loaded after initial render
                                    val cachedReplyPreview = replyPreviewCache[message.id]

                                    // Create updated message with cached image, animated data, and reply preview
                                    val displayMessage =
                                        message.copy(
                                            decodedImage = cachedImage ?: message.decodedImage,
                                            imageData = decodedResult?.rawBytes,
                                            isAnimatedImage = decodedResult?.isAnimated ?: false,
                                            replyPreview = cachedReplyPreview ?: message.replyPreview,
                                        )

                                    // Wrap in SwipeableMessageBubble for swipe-to-reply
                                    SwipeableMessageBubble(
                                        isFromMe = displayMessage.isFromMe,
                                        onReply = { viewModel.setReplyTo(message.id) },
                                        modifier =
                                            Modifier
                                                // Hide message when it's being shown in reaction overlay
                                                // isMessageHidden controls visibility separately from overlay lifecycle
                                                .alpha(
                                                    if (reactionModeState?.messageId == message.id && reactionModeState?.isMessageHidden == true) 0f else 1f,
                                                ),
                                    ) {
                                        MessageBubble(
                                            message = displayMessage,
                                            isFromMe = displayMessage.isFromMe,
                                            clipboardManager = clipboardManager,
                                            myIdentityHash = myIdentityHash,
                                            peerName = peerName,
                                            syncProgress = syncProgress,
                                            onViewDetails = onViewMessageDetails,
                                            onRetry = { viewModel.retryFailedMessage(message.id) },
                                            onFileAttachmentTap = { messageId, fileIndex, filename ->
                                                selectedFileInfo = Triple(messageId, fileIndex, filename)
                                                showFileOptionsSheet = true
                                            },
                                            onReply = { viewModel.setReplyTo(message.id) },
                                            onReplyPreviewClick = { replyToId ->
                                                // Jump to original message
                                                messagePositions[replyToId]?.let { position ->
                                                    scope.launch {
                                                        listState.animateScrollToItem(position)
                                                    }
                                                }
                                            },
                                            onReact = { emoji -> viewModel.sendReaction(message.id, emoji) },
                                            onFetchPendingFile = { fileSizeBytes ->
                                                viewModel.fetchPendingFile(fileSizeBytes)
                                            },
                                            onLongPress = { msgId, fromMe, failed, bitmap, x, y, width, height ->
                                                // Dismiss keyboard before entering reaction mode
                                                keyboardController?.hide()
                                                viewModel.enterReactionMode(msgId, index, fromMe, failed, bitmap, x, y, width, height)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Reply input bar (shown when replying to a message)
                pendingReplyTo?.let { replyPreview ->
                    ReplyInputBar(
                        replyPreview = replyPreview,
                        onCancelReply = { viewModel.clearReplyTo() },
                    )
                }

                // Message Input Bar - at bottom of Column
                MessageInputBar(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding(),
                    // Only navigation bar padding, IME is handled by parent
                    messageText = messageText,
                    onMessageTextChange = { messageText = it },
                    selectedImageData = selectedImageData,
                    selectedImageIsAnimated = selectedImageIsAnimated,
                    isProcessingImage = isProcessingImage,
                    onImageAttachmentClick = {
                        // Link is already established by ConversationLinkManager when entering conversation
                        imageLauncher.launch("image/*")
                    },
                    onImageContentReceived = { data, format, isAnimated ->
                        viewModel.selectImage(data, format, isAnimated)
                    },
                    onClearImage = { viewModel.clearSelectedImage() },
                    selectedFileAttachments = selectedFileAttachments,
                    totalAttachmentSize = totalAttachmentSize,
                    isProcessingFile = isProcessingFile,
                    onFileAttachmentClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    onRemoveFileAttachment = { index -> viewModel.removeFileAttachment(index) },
                    onSendClick = {
                        if (messageText.isNotBlank() || selectedImageData != null || selectedFileAttachments.isNotEmpty()) {
                            viewModel.sendMessage(destinationHash, messageText.trim())
                            messageText = ""
                        }
                    },
                    isSending = isSending,
                )
            }
        }

        // Reaction mode overlay - appears above entire screen with dimmed background
        // key() ensures each overlay is a unique composition - when instanceId changes,
        // the old composition is disposed (cancelling its coroutines) and a new one starts
        reactionModeState?.let { state ->
            key(state.instanceId) {
                var showFullEmojiPicker by remember { mutableStateOf(false) }

                // Full emoji picker dialog (shown when "+" is tapped in inline bar)
                if (showFullEmojiPicker) {
                    FullEmojiPickerDialog(
                        onEmojiSelected = { emoji ->
                            viewModel.sendReaction(state.messageId, emoji)
                            showFullEmojiPicker = false
                            // Direct exit, no animation needed for full picker
                            viewModel.exitReactionMode()
                        },
                        onDismiss = { showFullEmojiPicker = false },
                    )
                }

                ReactionModeOverlay(
                    messageId = state.messageId,
                    isFromMe = state.isFromMe,
                    isFailed = state.isFailed,
                    messageBitmap = state.messageBitmap,
                    messageX = state.messageX,
                    messageY = state.messageY,
                    messageWidth = state.messageWidth,
                    messageHeight = state.messageHeight,
                    onReactionSelected = { emoji ->
                        viewModel.sendReaction(state.messageId, emoji)
                    },
                    onShowFullPicker = { showFullEmojiPicker = true },
                    onReply = {
                        viewModel.setReplyTo(state.messageId)
                    },
                    onCopy = {
                        val message =
                            pagingItems.itemSnapshotList
                                .find { it?.id == state.messageId }
                        message?.let {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(it.content))
                        }
                    },
                    onViewDetails =
                        if (state.isFromMe) {
                            { onViewMessageDetails(state.messageId) }
                        } else {
                            null
                        },
                    onRetry =
                        if (state.isFailed) {
                            { viewModel.retryFailedMessage(state.messageId) }
                        } else {
                            null
                        },
                    onDismissStarted = {
                        // Show original message immediately when dismiss animation starts
                        viewModel.showOriginalMessage()
                    },
                    onDismiss = {
                        // Clean up after animation completes
                        // key() handles race condition - if user long-pressed another message,
                        // this composition was disposed and this callback never runs
                        viewModel.exitReactionMode()
                    },
                )
            }
        }
    }

    // File attachment options bottom sheet
    if (showFileOptionsSheet && selectedFileInfo != null) {
        val (messageId, fileIndex, filename) = selectedFileInfo!!
        FileAttachmentOptionsSheet(
            filename = filename,
            onOpenWith = {
                showFileOptionsSheet = false
                scope.launch {
                    val result = viewModel.getFileAttachmentUri(context, messageId, fileIndex)
                    if (result != null) {
                        val (uri, mimeType) = result
                        val intent =
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        @Suppress("SwallowedException") // User is notified via Toast
                        try {
                            context.startActivity(Intent.createChooser(intent, null))
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Failed to load file", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onSaveToDevice = {
                showFileOptionsSheet = false
                pendingFileSave = Triple(messageId, fileIndex, filename)
                fileSaveLauncher.launch(filename)
            },
            onDismiss = {
                showFileOptionsSheet = false
                selectedFileInfo = null
            },
        )
    }

    // Location permission bottom sheet
    if (showLocationPermissionSheet) {
        LocationPermissionBottomSheet(
            onDismiss = { showLocationPermissionSheet = false },
            onRequestPermissions = {
                showLocationPermissionSheet = false
                locationPermissionLauncher.launch(
                    LocationPermissionManager.getRequiredPermissions().toTypedArray(),
                )
            },
            sheetState = locationPermissionSheetState,
            primaryActionLabel = "Allow Location",
        )
    }

    // Share location bottom sheet
    if (showShareLocationSheet) {
        QuickShareLocationBottomSheet(
            contactName = peerName,
            onDismiss = { showShareLocationSheet = false },
            onStartSharing = { duration ->
                viewModel.startSharingWithPeer(destinationHash, peerName, duration)
                showShareLocationSheet = false
            },
            sheetState = shareLocationSheetState,
        )
    }

    // Stop sharing confirmation dialog
    if (showStopSharingDialog) {
        AlertDialog(
            onDismissRequest = { showStopSharingDialog = false },
            icon = {
                Icon(
                    Icons.Default.LocationOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Stop Sharing Location?") },
            text = { Text("Stop sharing your location with $peerName?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.stopSharingWithPeer(destinationHash)
                        showStopSharingDialog = false
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text("Stop Sharing")
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopSharingDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Sync status bottom sheet - shows real-time propagation sync progress
    if (showSyncStatusSheet) {
        SyncStatusBottomSheet(
            syncProgress = syncProgress,
            onDismiss = { showSyncStatusSheet = false },
            sheetState = syncStatusSheetState,
        )
    }

    // Image quality selection dialog
    qualitySelectionState?.let { state ->
        ImageQualitySelectionDialog(
            recommendedPreset = state.recommendedPreset,
            linkState = currentLinkState,
            transferTimeEstimates = state.transferTimeEstimates,
            onSelect = { preset -> viewModel.selectImageQuality(preset) },
            onDismiss = { viewModel.dismissQualitySelection() },
        )
    }
}

@Suppress("UnusedParameter") // Params kept for API consistency; actions handled by overlay
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: com.lxmf.messenger.ui.model.MessageUi,
    isFromMe: Boolean,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    myIdentityHash: String? = null,
    peerName: String = "",
    syncProgress: SyncProgress = SyncProgress.Idle,
    onViewDetails: (messageId: String) -> Unit = {},
    onRetry: () -> Unit = {},
    onFileAttachmentTap: (messageId: String, fileIndex: Int, filename: String) -> Unit = { _, _, _ -> },
    onReply: () -> Unit = {},
    onReplyPreviewClick: (replyToMessageId: String) -> Unit = {},
    onReact: (emoji: String) -> Unit = {},
    onFetchPendingFile: (fileSizeBytes: Long) -> Unit = {},
    onLongPress: (
        messageId: String,
        isFromMe: Boolean,
        isFailed: Boolean,
        bitmap: androidx.compose.ui.graphics.ImageBitmap,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
    ) -> Unit = { _, _, _, _, _, _, _, _ -> },
) {
    val hapticFeedback = LocalHapticFeedback.current
    val graphicsLayer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    var bubbleX by remember { mutableStateOf(0f) }
    var bubbleY by remember { mutableStateOf(0f) }
    var bubbleWidth by remember { mutableStateOf(0) }
    var bubbleHeight by remember { mutableStateOf(0) }

    // Signal-style press feedback: scale down to 0.95f after 100ms
    val interactionSource = remember { MutableInteractionSource() }
    var isPressed by remember { mutableStateOf(false) }
    var shouldScale by remember { mutableStateOf(false) }

    // Monitor press state
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    isPressed = true
                    // Delay before scaling (Signal uses 100ms)
                    kotlinx.coroutines.delay(100)
                    if (isPressed) {
                        shouldScale = true
                    }
                }
                is PressInteraction.Release,
                is PressInteraction.Cancel,
                -> {
                    isPressed = false
                    shouldScale = false
                }
            }
        }
    }

    // Animate scale
    val scale by animateFloatAsState(
        targetValue = if (shouldScale) 0.95f else 1f,
        animationSpec =
            tween(
                durationMillis = 200,
                easing = FastOutSlowInEasing,
            ),
        label = "bubble_scale",
    )

    val context = LocalContext.current
    var showFullscreenImage by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isFromMe) Alignment.End else Alignment.Start,
    ) {
        // Handle pending file notifications (system messages for files arriving via relay)
        if (message.isPendingFileNotification) {
            if (message.isSuperseded) {
                // Notification superseded by actual file arrival - don't render
                return
            }
            // Render notification bubble with sync progress
            message.pendingFileInfo?.let { info ->
                PendingFileNotificationBubble(
                    pendingFileInfo = info,
                    peerName = peerName,
                    syncProgress = syncProgress,
                    onClick = { onFetchPendingFile(info.totalSize) },
                )
            }
            return
        }

        if (message.isMediaOnlyMessage) {
            // GIF-only message: render large GIF without bubble, like Signal
            Box(
                modifier =
                    Modifier
                        .widthIn(max = 280.dp)
                        .drawWithCache {
                            val width = this.size.width.toInt()
                            val height = this.size.height.toInt()
                            onDrawWithContent {
                                graphicsLayer.record(size = androidx.compose.ui.unit.IntSize(width, height)) {
                                    this@onDrawWithContent.drawContent()
                                }
                                drawContent()
                            }
                        }
                        .onGloballyPositioned { coordinates ->
                            bubbleX = coordinates.positionInRoot().x
                            bubbleY = coordinates.positionInRoot().y
                            bubbleWidth = coordinates.size.width
                            bubbleHeight = coordinates.size.height
                        }
                        .scale(scale)
                        .combinedClickable(
                            onClick = { showFullscreenImage = true },
                            onLongClick = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    val bitmap = graphicsLayer.toImageBitmap()
                                    onLongPress(message.id, isFromMe, message.status == "failed", bitmap, bubbleX, bubbleY, bubbleWidth, bubbleHeight)
                                }
                            },
                            indication = null,
                            interactionSource = interactionSource,
                        ),
            ) {
                // Large GIF without bubble background
                AsyncImage(
                    model =
                        ImageRequest.Builder(context)
                            .data(message.imageData)
                            .crossfade(true)
                            .build(),
                    imageLoader = AnimatedImageLoader.getInstance(context),
                    contentDescription = "Animated GIF",
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.FillWidth,
                )

                // Timestamp overlay at bottom-right corner
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formatTimestamp(message.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                        if (isFromMe) {
                            Text(
                                text =
                                    when (message.status) {
                                        "pending" -> "○"
                                        "sent", "retrying_propagated", "propagated" -> "✓"
                                        "delivered" -> "✓✓"
                                        "failed" -> "!"
                                        else -> ""
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                            )
                        }
                    }
                }
            }

            // Fullscreen dialog for GIF-only messages
            if (showFullscreenImage && message.imageData != null) {
                FullscreenAnimatedImageDialog(
                    imageData = message.imageData,
                    onDismiss = { showFullscreenImage = false },
                )
            }
        } else {
            // Regular message with bubble
            Box {
                Surface(
                    shape =
                        RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = if (isFromMe) 20.dp else 4.dp,
                            bottomEnd = if (isFromMe) 4.dp else 20.dp,
                        ),
                    color =
                        if (isFromMe) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    shadowElevation = 1.dp,
                    modifier =
                        Modifier
                            .widthIn(max = 300.dp)
                            .drawWithCache {
                                val width = this.size.width.toInt()
                                val height = this.size.height.toInt()
                                onDrawWithContent {
                                    graphicsLayer.record(size = androidx.compose.ui.unit.IntSize(width, height)) {
                                        this@onDrawWithContent.drawContent()
                                    }
                                    drawContent()
                                }
                            }
                            .onGloballyPositioned { coordinates ->
                                bubbleX = coordinates.positionInRoot().x
                                bubbleY = coordinates.positionInRoot().y
                                bubbleWidth = coordinates.size.width
                                bubbleHeight = coordinates.size.height
                            }
                            .scale(scale) // Apply scale animation after bitmap capture
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scope.launch {
                                        val bitmap = graphicsLayer.toImageBitmap()
                                        onLongPress(message.id, isFromMe, message.status == "failed", bitmap, bubbleX, bubbleY, bubbleWidth, bubbleHeight)
                                    }
                                },
                                // Disable ripple - we use scale animation instead
                                indication = null,
                                interactionSource = interactionSource,
                            ),
                ) {
                    // PERFORMANCE: Use pre-decoded image from MessageUi to avoid expensive
                    // decoding during composition (critical for smooth scrolling)
                    val imageBitmap = message.decodedImage
                    val imageData = message.imageData
                    val isAnimated = message.isAnimatedImage

                    Column(
                        modifier =
                            Modifier.padding(
                                horizontal = 16.dp,
                                vertical = 10.dp,
                            ),
                    ) {
                        // Display reply preview if this message is a reply
                        message.replyPreview?.let { replyPreview ->
                            ReplyPreviewBubble(
                                replyPreview = replyPreview,
                                isFromMe = isFromMe,
                                onClick = { onReplyPreviewClick(replyPreview.messageId) },
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Display image attachment if present (LXMF field 6 = IMAGE)
                        // Use Coil AsyncImage for animated GIFs, static Image for regular images
                        if (isAnimated && imageData != null) {
                            // Animated GIF - use Coil for animated rendering
                            AsyncImage(
                                model =
                                    ImageRequest.Builder(context)
                                        .data(imageData)
                                        .crossfade(true)
                                        .build(),
                                imageLoader = AnimatedImageLoader.getInstance(context),
                                contentDescription = "Animated image attachment",
                                modifier =
                                    Modifier
                                        .widthIn(max = 268.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { showFullscreenImage = true },
                                contentScale = ContentScale.FillWidth,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        } else if (imageBitmap != null) {
                            // Static image - use pre-decoded bitmap for efficiency
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = "Image attachment",
                                modifier =
                                    Modifier
                                        .widthIn(max = 268.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { showFullscreenImage = true },
                                contentScale = ContentScale.FillWidth,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        } else if (message.hasImageAttachment) {
                            // Image is loading - show placeholder
                            Box(
                                modifier =
                                    Modifier
                                        .size(100.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Fullscreen image dialog - show when clicked and image is available
                        val hasDisplayableImage = imageBitmap != null || (isAnimated && imageData != null)
                        if (showFullscreenImage && hasDisplayableImage) {
                            if (isAnimated && imageData != null) {
                                // For animated GIFs, show fullscreen with Coil
                                FullscreenAnimatedImageDialog(
                                    imageData = imageData,
                                    onDismiss = { showFullscreenImage = false },
                                )
                            } else if (imageBitmap != null) {
                                FullscreenImageDialog(
                                    bitmap = imageBitmap,
                                    onDismiss = { showFullscreenImage = false },
                                )
                            }
                        }

                        // Display file attachments if present (LXMF field 5 = FILE_ATTACHMENTS)
                        if (message.hasFileAttachments) {
                            message.fileAttachments.forEach { fileAttachment ->
                                FileAttachmentCard(
                                    attachment = fileAttachment,
                                    onTap = {
                                        onFileAttachmentTap(
                                            message.id,
                                            fileAttachment.index,
                                            fileAttachment.filename,
                                        )
                                    },
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }

                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color =
                                if (isFromMe) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = formatTimestamp(message.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color =
                                    if (isFromMe) {
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                            if (isFromMe) {
                                Text(
                                    text =
                                        when (message.status) {
                                            "pending" -> "○" // Hollow circle - message created, waiting to send
                                            "sent", "retrying_propagated", "propagated" -> "✓" // Single check - transmitted/retrying/stored on relay
                                            "delivered" -> "✓✓" // Double check - delivered and acknowledged by recipient
                                            "failed" -> "!" // Exclamation - delivery failed
                                            else -> ""
                                        },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Display reaction chips overlapping the bottom of message bubble
        // Negative offset pulls reactions up to overlap the message (per Material Design 3)
        if (message.reactions.isNotEmpty()) {
            ReactionDisplayRow(
                reactions = message.reactions,
                isFromMe = isFromMe,
                myIdentityHash = myIdentityHash,
                modifier = Modifier.offset(y = (-8).dp),
            )
        }
    }
}

@Composable
fun MessageContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    isFromMe: Boolean = false,
    isFailed: Boolean = false,
    onViewDetails: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
        offset = DpOffset(x = 0.dp, y = 0.dp),
    ) {
        // Show "Retry" for failed messages
        if (isFailed && onRetry != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                    )
                },
                text = { Text("Retry") },
                onClick = onRetry,
            )
        }

        // Reply option
        if (onReply != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Reply,
                        contentDescription = null,
                    )
                },
                text = { Text("Reply") },
                onClick = onReply,
            )
        }

        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                )
            },
            text = { Text("Copy") },
            onClick = onCopy,
        )

        // Show "View Details" only for sent messages
        if (isFromMe && onViewDetails != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                    )
                },
                text = { Text("View Details") },
                onClick = onViewDetails,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun MessageInputBar(
    modifier: Modifier = Modifier,
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    selectedImageData: ByteArray? = null,
    selectedImageIsAnimated: Boolean = false,
    isProcessingImage: Boolean = false,
    onImageAttachmentClick: () -> Unit = {},
    onImageContentReceived: (ByteArray, String, Boolean) -> Unit = { _, _, _ -> },
    onClearImage: () -> Unit = {},
    selectedFileAttachments: List<FileAttachment> = emptyList(),
    totalAttachmentSize: Int = 0,
    isProcessingFile: Boolean = false,
    onFileAttachmentClick: () -> Unit = {},
    onRemoveFileAttachment: (Int) -> Unit = {},
    onSendClick: () -> Unit,
    isSending: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textFieldState = rememberTextFieldState(messageText)

    // Sync external messageText changes to textFieldState
    LaunchedEffect(messageText) {
        if (textFieldState.text.toString() != messageText) {
            textFieldState.edit {
                replace(0, length, messageText)
            }
        }
    }

    // Sync textFieldState changes to external onMessageTextChange
    LaunchedEffect(textFieldState.text) {
        val newText = textFieldState.text.toString()
        if (newText != messageText && newText.length <= ValidationConstants.MAX_MESSAGE_LENGTH) {
            onMessageTextChange(newText)
        }
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column {
            // File attachment preview row (if files are selected)
            if (selectedFileAttachments.isNotEmpty()) {
                FileAttachmentPreviewRow(
                    attachments = selectedFileAttachments,
                    totalSizeBytes = totalAttachmentSize,
                    onRemove = onRemoveFileAttachment,
                )
            }

            // Image preview (if image is selected)
            if (selectedImageData != null) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selectedImageIsAnimated) {
                        // Animated GIF - use Coil for preview
                        AsyncImage(
                            model =
                                ImageRequest.Builder(context)
                                    .data(selectedImageData)
                                    .crossfade(true)
                                    .build(),
                            imageLoader = AnimatedImageLoader.getInstance(context),
                            contentDescription = "Selected animated image",
                            modifier =
                                Modifier
                                    .size(80.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        // Static image - use decoded bitmap
                        val bitmap =
                            remember(selectedImageData) {
                                BitmapFactory.decodeByteArray(selectedImageData, 0, selectedImageData.size)
                                    ?.asImageBitmap()
                            }
                        bitmap?.let { imageBitmap ->
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = "Selected image",
                                modifier =
                                    Modifier
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }

                    Text(
                        text =
                            if (selectedImageIsAnimated) {
                                "GIF attached (${selectedImageData.size / 1024} KB)"
                            } else {
                                "Image attached (${selectedImageData.size / 1024} KB)"
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )

                    IconButton(onClick = onClearImage) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Close,
                            contentDescription = "Remove image",
                        )
                    }
                }
            }

            // Character count display
            val remaining = ValidationConstants.MAX_MESSAGE_LENGTH - messageText.length
            if (remaining < 100) {
                Text(
                    text = "$remaining characters remaining",
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (remaining < 20) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // BasicTextField with contentReceiver for keyboard GIFs and clipboard paste
                val isError = messageText.length >= ValidationConstants.MAX_MESSAGE_LENGTH - 20
                val borderColor =
                    when {
                        isError -> MaterialTheme.colorScheme.error
                        else -> Color.Transparent
                    }

                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp, max = 120.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                RoundedCornerShape(24.dp),
                            )
                            .border(1.dp, borderColor, RoundedCornerShape(24.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        state = textFieldState,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .contentReceiver { transferableContent ->
                                    // Check if content contains images
                                    if (!transferableContent.hasMediaType(MediaType.Image)) {
                                        return@contentReceiver transferableContent
                                    }

                                    // Process image content from keyboard or clipboard
                                    val clipData = transferableContent.clipEntry.clipData
                                    for (i in 0 until clipData.itemCount) {
                                        val item = clipData.getItemAt(i)
                                        val uri = item.uri
                                        if (uri != null) {
                                            scope.launch(Dispatchers.IO) {
                                                val result =
                                                    ImageUtils.compressImagePreservingAnimation(
                                                        context,
                                                        uri,
                                                    )
                                                result?.let { compressed ->
                                                    withContext(Dispatchers.Main) {
                                                        onImageContentReceived(
                                                            compressed.data,
                                                            compressed.format,
                                                            compressed.isAnimated,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    null // Content consumed
                                },
                        textStyle =
                            MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                            ),
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                        decorator = { innerTextField ->
                            Box {
                                if (textFieldState.text.isEmpty()) {
                                    Text(
                                        text = "Type a message...",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                }

                // Image attachment button
                IconButton(
                    onClick = onImageAttachmentClick,
                    modifier =
                        Modifier
                            .size(48.dp)
                            .padding(0.dp),
                    enabled = !isProcessingImage,
                ) {
                    if (isProcessingImage) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Attach image",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                // File attachment button
                IconButton(
                    onClick = onFileAttachmentClick,
                    modifier =
                        Modifier
                            .size(48.dp)
                            .padding(0.dp),
                    enabled = !isProcessingFile,
                ) {
                    if (isProcessingFile) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Attach file",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                FilledIconButton(
                    onClick = onSendClick,
                    enabled = !isSending && (messageText.isNotBlank() || selectedImageData != null || selectedFileAttachments.isNotEmpty()),
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send message",
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyMessagesState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Text(
                text = "No messages yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Send a message to start the conversation",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> {
            val minutes = (diff / 60_000).toInt()
            "$minutes min ago"
        }
        diff < 86400_000 -> {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        else -> {
            SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

@Composable
private fun FullscreenImageDialog(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    onDismiss: () -> Unit,
) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { onDismiss() }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "Fullscreen image",
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun FullscreenAnimatedImageDialog(
    imageData: ByteArray,
    onDismiss: () -> Unit,
) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val context = LocalContext.current

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { onDismiss() }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model =
                    ImageRequest.Builder(context)
                        .data(imageData)
                        .crossfade(true)
                        .build(),
                imageLoader = AnimatedImageLoader.getInstance(context),
                contentDescription = "Fullscreen animated image",
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        ),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/**
 * System message bubble for pending file notifications.
 *
 * Displayed when a sender's file message fell back to propagation,
 * notifying the recipient that a file is arriving via relay.
 *
 * This is styled as a centered, muted system message rather than a
 * regular chat bubble.
 *
 * @param pendingFileInfo Info about the pending file(s)
 */
@Suppress("FunctionNaming")
@Composable
fun PendingFileNotificationBubble(
    pendingFileInfo: com.lxmf.messenger.ui.model.PendingFileInfo,
    peerName: String,
    syncProgress: SyncProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSyncing = syncProgress !is SyncProgress.Idle

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
            modifier =
                Modifier
                    .widthIn(max = 300.dp)
                    .then(if (!isSyncing) Modifier.clickable(onClick = onClick) else Modifier),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Column {
                        Text(
                            text = if (isSyncing) "Fetching file..." else "$peerName sent a large file",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "${pendingFileInfo.filename} (${formatFileSize(pendingFileInfo.totalSize)})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!isSyncing && pendingFileInfo.fileCount > 1) {
                            Text(
                                text = "+${pendingFileInfo.fileCount - 1} more file${if (pendingFileInfo.fileCount > 2) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = getSyncStatusText(syncProgress),
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (isSyncing) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                        )
                    }
                }
                // Show progress bar when downloading
                if (syncProgress is SyncProgress.InProgress && syncProgress.progress > 0f) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { syncProgress.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Get status text for pending file notification based on sync progress.
 */
private fun getSyncStatusText(syncProgress: SyncProgress): String =
    when (syncProgress) {
        is SyncProgress.Idle -> "Tap to fetch from relay"
        is SyncProgress.Starting -> "Connecting to relay..."
        is SyncProgress.InProgress ->
            when (syncProgress.stateName.lowercase()) {
                "path_requested" -> "Discovering network path..."
                "link_establishing" -> "Establishing connection..."
                "link_established" -> "Connected, preparing..."
                "request_sent" -> "Requesting messages..."
                "receiving", "downloading" ->
                    if (syncProgress.progress > 0f) {
                        "Downloading: ${(syncProgress.progress * 100).toInt()}%"
                    } else {
                        "Downloading..."
                    }
                else -> "Processing..."
            }
        is SyncProgress.Complete -> "Download complete"
    }

/**
 * Format file size in human-readable format.
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
