package com.lxmf.messenger.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.lxmf.messenger.ui.theme.MeshConnected
import com.lxmf.messenger.ui.theme.MeshOffline
import com.lxmf.messenger.util.formatRelativeTime
import com.lxmf.messenger.util.validation.ValidationConstants
import com.lxmf.messenger.viewmodel.MessagingViewModel
import kotlinx.coroutines.launch
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
    viewModel: MessagingViewModel = hiltViewModel(),
) {
    val pagingItems = viewModel.messages.collectAsLazyPagingItems()
    val announceInfo by viewModel.announceInfo.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var messageText by remember { mutableStateOf("") }

    // Image selection state
    val context = androidx.compose.ui.platform.LocalContext.current
    val selectedImageData by viewModel.selectedImageData.collectAsStateWithLifecycle()
    val selectedImageFormat by viewModel.selectedImageFormat.collectAsStateWithLifecycle()
    val isProcessingImage by viewModel.isProcessingImage.collectAsStateWithLifecycle()

    // Lifecycle-aware coroutine scope for image processing
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    // Image picker launcher
    val imageLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
        ) { uri: android.net.Uri? ->
            uri?.let {
                viewModel.setProcessingImage(true)
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val compressed = com.lxmf.messenger.util.ImageUtils.compressImage(context, it)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            viewModel.setProcessingImage(false)
                            if (compressed != null) {
                                viewModel.selectImage(compressed.data, compressed.format)
                            }
                        }
                    } catch (e: Exception) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            viewModel.setProcessingImage(false)
                        }
                        android.util.Log.e("MessagingScreen", "Error compressing image", e)
                    }
                }
            }
        }

    // Clipboard for copy functionality
    val clipboardManager = LocalClipboardManager.current

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
                    // Online status indicator - updates in real-time
                    val lastSeen = announceInfo?.lastSeenTimestamp
                    if (lastSeen != null) {
                        val isOnline = System.currentTimeMillis() - lastSeen < (5 * 60 * 1000L) // 5 minutes

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Circle,
                                contentDescription = null,
                                tint = if (isOnline) MeshConnected else MeshOffline,
                                modifier = Modifier.size(8.dp),
                            )
                            Text(
                                text = if (isOnline) "Online" else formatRelativeTime(lastSeen),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                    .imePadding(), // Apply IME padding to container
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
                                bottom = 16.dp, // Space for input bar
                            ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        reverseLayout = true, // Messages anchored to bottom (industry standard)
                    ) {
                        // Paging3 infinite scroll: loads 30 messages initially,
                        // then loads more as user scrolls up
                        // DB returns DESC (newest first), reverseLayout shows newest at bottom
                        items(
                            count = pagingItems.itemCount,
                            key = pagingItems.itemKey { message -> message.id },
                            contentType = { "message" }, // All items are message bubbles
                        ) { index ->
                            val message = pagingItems[index]
                            if (message != null) {
                                MessageBubble(
                                    message = message,
                                    isFromMe = message.isFromMe,
                                    clipboardManager = clipboardManager,
                                )
                            }
                        }
                    }
                }
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
                isProcessingImage = isProcessingImage,
                onAttachmentClick = { imageLauncher.launch("image/*") },
                onClearImage = { viewModel.clearSelectedImage() },
                onSendClick = {
                    if (messageText.isNotBlank() || selectedImageData != null) {
                        viewModel.sendMessage(destinationHash, messageText.trim())
                        messageText = ""
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: com.lxmf.messenger.ui.model.MessageUi,
    isFromMe: Boolean,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
) {
    val hapticFeedback = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isFromMe) Alignment.End else Alignment.Start,
    ) {
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
                        .combinedClickable(
                            onClick = { },
                            onLongClick = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                showMenu = true
                            },
                        ),
            ) {
                // PERFORMANCE: Use pre-decoded image from MessageUi to avoid expensive
                // decoding during composition (critical for smooth scrolling)
                val imageBitmap = message.decodedImage
                var showFullscreenImage by remember { mutableStateOf(false) }

                Column(
                    modifier =
                        Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 10.dp,
                        ),
                ) {
                    // Display image attachment if present (LXMF field 6 = IMAGE)
                    imageBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Image attachment",
                            modifier =
                                Modifier
                                    .widthIn(max = 268.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { showFullscreenImage = true },
                            contentScale = ContentScale.FillWidth,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Fullscreen image dialog
                    if (showFullscreenImage && imageBitmap != null) {
                        FullscreenImageDialog(
                            bitmap = imageBitmap,
                            onDismiss = { showFullscreenImage = false },
                        )
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
                                        "sent" -> "✓" // Single check - transmitted to network
                                        "delivered" -> "✓✓" // Double check - delivered and acknowledged
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

            // Context menu
            MessageContextMenu(
                expanded = showMenu,
                onDismiss = { showMenu = false },
                onCopy = {
                    clipboardManager.setText(AnnotatedString(message.content))
                    showMenu = false
                },
            )
        }
    }
}

@Composable
fun MessageContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
        offset = DpOffset(x = 0.dp, y = 0.dp),
    ) {
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
    }
}

@Composable
fun MessageInputBar(
    modifier: Modifier = Modifier,
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    selectedImageData: ByteArray? = null,
    isProcessingImage: Boolean = false,
    onAttachmentClick: () -> Unit = {},
    onClearImage: () -> Unit = {},
    onSendClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column {
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
                    val bitmap =
                        remember(selectedImageData) {
                            BitmapFactory.decodeByteArray(selectedImageData, 0, selectedImageData.size)?.asImageBitmap()
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

                    Text(
                        text = "Image attached (${selectedImageData.size / 1024} KB)",
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

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { newText ->
                        // VALIDATION: Enforce maximum message length
                        if (newText.length <= ValidationConstants.MAX_MESSAGE_LENGTH) {
                            onMessageTextChange(newText)
                        }
                    },
                    modifier =
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp, max = 120.dp),
                    placeholder = {
                        Text(
                            text = "Type a message...",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingText =
                        if (ValidationConstants.MAX_MESSAGE_LENGTH - messageText.length < 100) {
                            {
                                // VALIDATION: Show character counter when approaching limit
                                val remaining = ValidationConstants.MAX_MESSAGE_LENGTH - messageText.length
                                Text(
                                    text = "$remaining characters remaining",
                                    color =
                                        if (remaining < 20) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }
                        } else {
                            null
                        },
                    isError = messageText.length >= ValidationConstants.MAX_MESSAGE_LENGTH - 20,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Default,
                        ),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            unfocusedBorderColor = Color.Transparent,
                        ),
                )

                // Attachment button (between text field and send button)
                IconButton(
                    onClick = onAttachmentClick,
                    modifier =
                        Modifier
                            .size(48.dp)
                            .padding(0.dp),
                    enabled = !isProcessingImage,
                ) {
                    if (isProcessingImage) {
                        androidx.compose.material3.CircularProgressIndicator(
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

                FilledIconButton(
                    onClick = onSendClick,
                    enabled = messageText.isNotBlank() || selectedImageData != null,
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
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message",
                    )
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
