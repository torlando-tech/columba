package com.lxmf.messenger.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.lxmf.messenger.util.CameraPermissionManager
import com.lxmf.messenger.viewmodel.GuardianViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "GuardianQrScannerScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianQrScannerScreen(
    onBackClick: () -> Unit = {},
    onPaired: () -> Unit = {},
    viewModel: GuardianViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(CameraPermissionManager.hasPermission(context))
    }
    var showPermissionDenied by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var pendingQrData by remember { mutableStateOf<String?>(null) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val permissionLauncher = rememberLauncherForActivityResult(
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
                title = { Text("Scan Guardian QR Code") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
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
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
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
                            if (!isProcessing && !showConfirmDialog && qrData.startsWith("lxmf-guardian://")) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                isProcessing = true
                                pendingQrData = qrData
                                showConfirmDialog = true
                            }
                        },
                    )

                    // Instruction overlay
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 80.dp, start = 32.dp, end = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (errorMessage != null) {
                            Card(
                                colors = CardDefaults.cardColors(
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
                        } else if (!showConfirmDialog) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                ),
                            ) {
                                Text(
                                    text = "Point camera at guardian's QR code\nThis will enable parental controls on this device",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(16.dp),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }

            // Confirmation dialog
            if (showConfirmDialog && pendingQrData != null) {
                AlertDialog(
                    onDismissRequest = {
                        showConfirmDialog = false
                        isProcessing = false
                        pendingQrData = null
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    title = {
                        Text("Enable Parental Controls?")
                    },
                    text = {
                        Text(
                            "This will pair your device with a guardian. They will be able to:\n\n" +
                                "• Lock/unlock your messaging\n" +
                                "• Control who you can message\n" +
                                "• Restrict app features\n\n" +
                                "Continue with pairing?",
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val success = viewModel.pairWithGuardian(pendingQrData!!)
                                    showConfirmDialog = false
                                    if (success) {
                                        showSuccessDialog = true
                                    } else {
                                        errorMessage = "Failed to pair with guardian"
                                        isProcessing = false
                                        pendingQrData = null
                                    }
                                }
                            },
                        ) {
                            Text("Pair")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showConfirmDialog = false
                                isProcessing = false
                                pendingQrData = null
                            },
                        ) {
                            Text("Cancel")
                        }
                    },
                )
            }

            // Success dialog
            if (showSuccessDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showSuccessDialog = false
                        onPaired()
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    title = {
                        Text("Pairing Successful")
                    },
                    text = {
                        Text("Your device is now paired with a guardian. They can now control your messaging settings remotely.")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showSuccessDialog = false
                                onPaired()
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
        modifier = Modifier
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

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(
                                cameraExecutor,
                                GuardianQrCodeAnalyzer { qrCode ->
                                    onQrCodeDetected(qrCode)
                                },
                            )
                        }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        camera = cameraProvider.bindToLifecycle(
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

        // Scanner overlay
        ScannerOverlay()
    }
}

@Composable
private fun ScannerOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        val viewfinderSize = minOf(canvasWidth, canvasHeight) * 0.7f
        val left = (canvasWidth - viewfinderSize) / 2f
        val top = (canvasHeight - viewfinderSize) / 2f

        // Semi-transparent overlay
        drawRect(
            color = Color.Black.copy(alpha = 0.5f),
            size = size,
        )

        // Cut out viewfinder area
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(viewfinderSize, viewfinderSize),
            cornerRadius = CornerRadius(16.dp.toPx()),
            blendMode = BlendMode.Clear,
        )

        // Viewfinder border
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(viewfinderSize, viewfinderSize),
            cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(width = 4.dp.toPx()),
        )

        // Corner indicators
        val cornerLength = 30.dp.toPx()
        val cornerStroke = 6.dp.toPx()

        // Top-left
        drawLine(Color.Green, Offset(left, top), Offset(left + cornerLength, top), cornerStroke)
        drawLine(Color.Green, Offset(left, top), Offset(left, top + cornerLength), cornerStroke)

        // Top-right
        drawLine(Color.Green, Offset(left + viewfinderSize, top), Offset(left + viewfinderSize - cornerLength, top), cornerStroke)
        drawLine(Color.Green, Offset(left + viewfinderSize, top), Offset(left + viewfinderSize, top + cornerLength), cornerStroke)

        // Bottom-left
        drawLine(Color.Green, Offset(left, top + viewfinderSize), Offset(left + cornerLength, top + viewfinderSize), cornerStroke)
        drawLine(Color.Green, Offset(left, top + viewfinderSize), Offset(left, top + viewfinderSize - cornerLength), cornerStroke)

        // Bottom-right
        drawLine(Color.Green, Offset(left + viewfinderSize, top + viewfinderSize), Offset(left + viewfinderSize - cornerLength, top + viewfinderSize), cornerStroke)
        drawLine(Color.Green, Offset(left + viewfinderSize, top + viewfinderSize), Offset(left + viewfinderSize, top + viewfinderSize - cornerLength), cornerStroke)
    }
}

private class GuardianQrCodeAnalyzer(
    private val onQrCodeDetected: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        )
        setHints(hints)
    }

    private var lastScannedTime = 0L
    private val scanCooldown = 2000L

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()

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

            val source = PlanarYUVLuminanceSource(
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
                    Log.d(TAG, "QR Code detected: ${qrText.take(60)}...")
                    // Only process guardian QR codes
                    if (qrText.startsWith("lxmf-guardian://")) {
                        lastScannedTime = currentTime
                        Log.d(TAG, "Guardian QR Code detected, calling onQrCodeDetected")
                        onQrCodeDetected(qrText)
                    } else {
                        Log.d(TAG, "QR code detected but not a guardian QR (prefix: ${qrText.take(20)})")
                    }
                }
            } catch (e: Exception) {
                // No QR code found in this frame - this is normal, don't log
            } finally {
                reader.reset()
            }
        }

        imageProxy.close()
    }
}
