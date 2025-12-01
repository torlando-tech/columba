package com.lxmf.messenger.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.lxmf.messenger.ui.components.AddContactConfirmationDialog
import com.lxmf.messenger.util.CameraPermissionManager
import com.lxmf.messenger.viewmodel.ContactsViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "QrScannerScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onBackClick: () -> Unit = {},
    onQrScanned: (qrData: String) -> Unit = {},
    onNavigateToConversation: (destinationHash: String) -> Unit = {},
    contactsViewModel: ContactsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(CameraPermissionManager.hasPermission(context))
    }
    var showPermissionRationale by remember { mutableStateOf(false) }
    var showPermissionDenied by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var scanSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Confirmation dialog state (for new contacts)
    var showConfirmationDialog by remember { mutableStateOf(false) }
    var pendingContactHash by remember { mutableStateOf<String?>(null) }
    var pendingContactPubKey by remember { mutableStateOf<ByteArray?>(null) }

    // Contact already exists dialog state
    var showContactExistsDialog by remember { mutableStateOf(false) }
    var existingContactName by remember { mutableStateOf<String?>(null) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            hasCameraPermission = isGranted
            if (!isGranted) {
                showPermissionDenied = true
            }
        }

    DisposableEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan QR Code") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (hasCameraPermission) {
                        IconButton(onClick = { torchEnabled = !torchEnabled }) {
                            Icon(
                                imageVector = if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = if (torchEnabled) "Turn off flash" else "Turn on flash",
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
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues),
        ) {
            when {
                !hasCameraPermission -> {
                    PermissionRequiredContent(
                        showDenied = showPermissionDenied,
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onOpenSettings = {
                            val intent =
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            context.startActivity(intent)
                        },
                    )
                }
                else -> {
                    CameraPreviewWithOverlay(
                        cameraExecutor = cameraExecutor,
                        torchEnabled = torchEnabled,
                        onQrCodeDetected = { qrData ->
                            if (!scanSuccess && !showConfirmationDialog) { // Only process if not already processing
                                // Decode and validate the QR code
                                val decodedData = contactsViewModel.decodeQrCode(qrData)
                                if (decodedData != null) {
                                    val (hashHex, publicKey) = decodedData
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scanSuccess = true

                                    // Check if contact already exists
                                    scope.launch {
                                        val existingContact = contactsViewModel.checkContactExists(hashHex)
                                        if (existingContact != null) {
                                            // Contact exists - show info dialog
                                            Log.d(TAG, "Contact already exists: $hashHex")
                                            existingContactName = existingContact.displayName
                                            showContactExistsDialog = true
                                        } else {
                                            // New contact - show confirmation dialog
                                            Log.d(TAG, "New contact detected, showing confirmation dialog: $hashHex")
                                            pendingContactHash = hashHex
                                            pendingContactPubKey = publicKey
                                            showConfirmationDialog = true
                                        }
                                    }
                                } else {
                                    errorMessage = "Invalid QR code format"
                                }
                            }
                        },
                    )

                    // Error/Instruction overlay
                    Column(
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 16.dp, start = 32.dp, end = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when {
                            errorMessage != null -> {
                                Card(
                                    colors =
                                        CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                        ),
                                    modifier = Modifier.padding(bottom = 16.dp),
                                ) {
                                    Text(
                                        text = errorMessage!!,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(16.dp),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                            !showConfirmationDialog -> {
                                Card(
                                    colors =
                                        CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                        ),
                                ) {
                                    Text(
                                        text = "Point camera at QR code\nIt will scan automatically",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(16.dp),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Confirmation dialog
            if (showConfirmationDialog && pendingContactHash != null) {
                AddContactConfirmationDialog(
                    destinationHash = pendingContactHash!!,
                    onDismiss = {
                        showConfirmationDialog = false
                        scanSuccess = false
                        pendingContactHash = null
                        pendingContactPubKey = null
                        errorMessage = null
                    },
                    onConfirm = { nickname ->
                        // Add the contact
                        contactsViewModel.addContactFromQrCode(
                            qrData = "lxma://$pendingContactHash:${pendingContactPubKey?.joinToString("") { "%02x".format(it) }}",
                            nickname = nickname,
                        )
                        // Call the original callback for compatibility
                        onQrScanned("lxma://$pendingContactHash:${pendingContactPubKey?.joinToString("") { "%02x".format(it) }}")
                        // Dismiss scanner
                        onBackClick()
                    },
                )
            }

            // Contact already exists dialog
            if (showContactExistsDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showContactExistsDialog = false
                        scanSuccess = false
                        existingContactName = null
                        errorMessage = null
                        onBackClick()
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Contact Exists",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    title = {
                        Text(
                            text = "Contact Already Added",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                    },
                    text = {
                        Text(
                            text =
                                if (existingContactName != null) {
                                    "This contact is already in your contacts list as \"$existingContactName\"."
                                } else {
                                    "This contact is already in your contacts list."
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showContactExistsDialog = false
                                scanSuccess = false
                                existingContactName = null
                                errorMessage = null
                                onBackClick()
                            },
                        ) {
                            Text("OK")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionRequiredContent(
    showDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (showDenied) "Camera Permission Denied" else "Camera Permission Required",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = CameraPermissionManager.getPermissionRationale(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (showDenied) {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open Settings")
            }
        } else {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Grant Permission")
            }
        }
    }
}

@Composable
private fun CameraPreviewWithOverlay(
    cameraExecutor: ExecutorService,
    torchEnabled: Boolean,
    onQrCodeDetected: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var camera by remember { mutableStateOf<Camera?>(null) }

    // Update torch state when torchEnabled changes
    LaunchedEffect(torchEnabled, camera) {
        camera?.cameraControl?.enableTorch(torchEnabled)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview =
                        Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                    val imageAnalyzer =
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also {
                                it.setAnalyzer(
                                    cameraExecutor,
                                    QrCodeAnalyzer { qrCode ->
                                        onQrCodeDetected(qrCode)
                                    },
                                )
                            }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        camera =
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalyzer,
                            )
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera binding failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Overlay with viewfinder
        ScannerOverlay()
    }
}

@Composable
private fun ScannerOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Viewfinder dimensions (square in center)
        val viewfinderSize = minOf(canvasWidth, canvasHeight) * 0.7f
        val left = (canvasWidth - viewfinderSize) / 2f
        val top = (canvasHeight - viewfinderSize) / 2f

        // Draw semi-transparent overlay
        drawRect(
            color = Color.Black.copy(alpha = 0.5f),
            size = size,
        )

        // Cut out the viewfinder area
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(viewfinderSize, viewfinderSize),
            cornerRadius = CornerRadius(16.dp.toPx()),
            blendMode = BlendMode.Clear,
        )

        // Draw viewfinder border
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(viewfinderSize, viewfinderSize),
            cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(width = 4.dp.toPx()),
        )

        // Draw corner indicators
        val cornerLength = 30.dp.toPx()
        val cornerStroke = 6.dp.toPx()

        // Top-left corner
        drawLine(
            color = Color.Green,
            start = Offset(left, top),
            end = Offset(left + cornerLength, top),
            strokeWidth = cornerStroke,
        )
        drawLine(
            color = Color.Green,
            start = Offset(left, top),
            end = Offset(left, top + cornerLength),
            strokeWidth = cornerStroke,
        )

        // Top-right corner
        drawLine(
            color = Color.Green,
            start = Offset(left + viewfinderSize, top),
            end = Offset(left + viewfinderSize - cornerLength, top),
            strokeWidth = cornerStroke,
        )
        drawLine(
            color = Color.Green,
            start = Offset(left + viewfinderSize, top),
            end = Offset(left + viewfinderSize, top + cornerLength),
            strokeWidth = cornerStroke,
        )

        // Bottom-left corner
        drawLine(
            color = Color.Green,
            start = Offset(left, top + viewfinderSize),
            end = Offset(left + cornerLength, top + viewfinderSize),
            strokeWidth = cornerStroke,
        )
        drawLine(
            color = Color.Green,
            start = Offset(left, top + viewfinderSize),
            end = Offset(left, top + viewfinderSize - cornerLength),
            strokeWidth = cornerStroke,
        )

        // Bottom-right corner
        drawLine(
            color = Color.Green,
            start = Offset(left + viewfinderSize, top + viewfinderSize),
            end = Offset(left + viewfinderSize - cornerLength, top + viewfinderSize),
            strokeWidth = cornerStroke,
        )
        drawLine(
            color = Color.Green,
            start = Offset(left + viewfinderSize, top + viewfinderSize),
            end = Offset(left + viewfinderSize, top + viewfinderSize - cornerLength),
            strokeWidth = cornerStroke,
        )
    }
}

private class QrCodeAnalyzer(
    private val onQrCodeDetected: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val reader =
        MultiFormatReader().apply {
            val hints =
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                )
            setHints(hints)
        }

    private var lastScannedTime = 0L
    private val scanCooldown = 2000L // 2 seconds between scans

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()

        // Throttle scanning to avoid multiple detections
        if (currentTime - lastScannedTime < scanCooldown) {
            imageProxy.close()
            return
        }

        val image = imageProxy.image
        if (image != null) {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val source =
                PlanarYUVLuminanceSource(
                    data,
                    imageProxy.width,
                    imageProxy.height,
                    0,
                    0,
                    imageProxy.width,
                    imageProxy.height,
                    false,
                )

            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

            try {
                val result = reader.decode(binaryBitmap)
                result?.text?.let { qrText ->
                    lastScannedTime = currentTime
                    onQrCodeDetected(qrText)
                    Log.d(TAG, "QR Code detected: $qrText")
                }
            } catch (e: Exception) {
                // No QR code found in this frame
            } finally {
                reader.reset()
            }
        }

        imageProxy.close()
    }
}
