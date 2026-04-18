package network.columba.app.viewmodel

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import network.columba.app.R
import network.columba.app.service.ApkSharingServer
import network.columba.app.service.LocalHotspotManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * How the APK is being shared — over an existing WiFi network
 * or via a local-only hotspot created by this device.
 */
enum class SharingMode {
    /** Both devices are on the same existing WiFi network. */
    WIFI,

    /** This device created a local-only hotspot for sharing. */
    HOTSPOT,
}

/**
 * State for the APK sharing screen.
 */
data class ApkSharingState(
    /** Whether the local HTTP server is running */
    val isServerRunning: Boolean = false,
    /** The download URL shown in the QR code */
    val downloadUrl: String? = null,
    /** The device's local IP address */
    val localIp: String? = null,
    /** Error message if something went wrong */
    val errorMessage: String? = null,
    /** The APK file size in bytes for display */
    val apkSizeBytes: Long = 0,
    /** How sharing is happening (WiFi or hotspot) */
    val sharingMode: SharingMode? = null,
    /** Hotspot SSID (only set in HOTSPOT mode) */
    val hotspotSsid: String? = null,
    /** Hotspot password (only set in HOTSPOT mode) */
    val hotspotPassword: String? = null,
    /** Whether we need to request permissions before starting hotspot */
    val needsHotspotPermission: Boolean = false,
    /** Whether the hotspot is currently starting up */
    val isHotspotStarting: Boolean = false,
)

/**
 * ViewModel for the APK sharing screen.
 *
 * Manages the lifecycle of the local HTTP server that serves the APK file,
 * and provides the download URL for QR code generation.
 *
 * When no existing WiFi network is available, offers to create a local-only
 * hotspot so the receiving device can connect directly to this device.
 */
@HiltViewModel
class ApkSharingViewModel
    @Inject
    constructor(
        private val application: Application,
    ) : AndroidViewModel(application) {
        companion object {
            private const val TAG = "ApkSharingViewModel"
            private const val APK_CACHE_DIR = "apk_share"
            private val APK_FILE_NAME = "columba-${network.columba.app.BuildConfig.VERSION_NAME}.apk"
        }

        private val _state = MutableStateFlow(ApkSharingState())
        val state: StateFlow<ApkSharingState> = _state.asStateFlow()

        private val server =
            ApkSharingServer().apply {
                downloadFileName = APK_FILE_NAME
                iconBase64 = loadIconBase64()
            }
        private var serverJob: Job? = null
        private var cachedApkFile: File? = null
        private val hotspotManager = LocalHotspotManager(application)

        init {
            startServer()
        }

        /**
         * Prepare the APK file by copying it from the app's source directory
         * to a cache directory where it can be served and shared.
         */
        private fun prepareApkFile(): File? {
            try {
                val sourceApkPath = application.applicationInfo.sourceDir
                val sourceFile = File(sourceApkPath)

                if (!sourceFile.exists()) {
                    Log.e(TAG, "Source APK not found at: $sourceApkPath")
                    return null
                }

                // Create cache directory for APK sharing
                val cacheDir = File(application.cacheDir, APK_CACHE_DIR)
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }

                val destFile = File(cacheDir, APK_FILE_NAME)

                // Copy APK to cache (overwrites if exists)
                sourceFile.copyTo(destFile, overwrite = true)
                Log.i(TAG, "APK copied to cache: ${destFile.absolutePath} (${destFile.length()} bytes)")

                return destFile
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare APK file", e)
                return null
            }
        }

        /**
         * Start the local HTTP server to serve the APK.
         * Tries existing WiFi first; if unavailable, prompts to use hotspot.
         */
        fun startServer() {
            // Guard against duplicate launches while a server job is already in progress
            if (serverJob?.isActive == true) return

            serverJob =
                viewModelScope.launch {
                    val apkFile = prepareApkFile()
                    if (apkFile == null) {
                        _state.value =
                            _state.value.copy(
                                errorMessage = "Could not prepare APK file for sharing",
                            )
                        return@launch
                    }
                    cachedApkFile = apkFile

                    val localIp = ApkSharingServer.getLocalIpAddress()
                    if (localIp != null) {
                        // Existing WiFi network available — use it
                        launchHttpServer(apkFile, localIp, SharingMode.WIFI)
                    } else if (LocalHotspotManager.isSupported()) {
                        // No WiFi — offer hotspot mode
                        _state.value =
                            _state.value.copy(
                                errorMessage = null,
                                needsHotspotPermission = !hasHotspotPermissions(),
                            )
                        if (hasHotspotPermissions()) {
                            startHotspotAndServe(apkFile)
                        }
                        // else: UI will show a "Start Hotspot" button that calls onHotspotPermissionGranted
                    } else {
                        // API < 26 and no WiFi
                        _state.value =
                            _state.value.copy(
                                errorMessage =
                                    "No WiFi connection detected. " +
                                        "Both devices must be on the same WiFi network, " +
                                        "or use the \"Share via...\" option below.",
                            )
                    }
                }
        }

        /**
         * Called after the user grants hotspot-related permissions.
         * Starts the hotspot and HTTP server.
         */
        fun onHotspotPermissionGranted() {
            _state.value = _state.value.copy(needsHotspotPermission = false)
            val apkFile = cachedApkFile ?: return
            startHotspotAndServe(apkFile)
        }

        /**
         * Called when the user explicitly taps "Start Hotspot" to share
         * via hotspot mode (even when already on WiFi, or after permission grant).
         */
        fun startHotspotSharing() {
            if (!LocalHotspotManager.isSupported()) return
            if (!hasHotspotPermissions()) {
                _state.value = _state.value.copy(needsHotspotPermission = true)
                return
            }

            // Stop existing WiFi-mode server if running
            server.stop()
            serverJob?.cancel()
            serverJob = null

            val apkFile = cachedApkFile ?: prepareApkFile() ?: return
            cachedApkFile = apkFile
            startHotspotAndServe(apkFile)
        }

        private fun startHotspotAndServe(apkFile: File) {
            _state.value =
                _state.value.copy(
                    isHotspotStarting = true,
                    errorMessage = null,
                )

            hotspotManager.start(
                onSystemStopped = {
                    Log.w(TAG, "Hotspot was stopped by the system")
                    server.stop()
                    serverJob?.cancel()
                    serverJob = null
                    _state.value =
                        ApkSharingState(
                            errorMessage =
                                "WiFi hotspot was stopped by the system. " +
                                    "Please start sharing again.",
                        )
                },
            ) { result ->
                result
                    .onSuccess { info ->
                        Log.i(TAG, "Hotspot started: SSID=${info.ssid}")
                        // Give the hotspot interface a moment to come up,
                        // then find the IP and start the HTTP server
                        serverJob =
                            viewModelScope.launch {
                                // Brief delay for the network interface to be assigned an IP
                                kotlinx.coroutines.delay(1000)
                                val localIp = ApkSharingServer.getLocalIpAddress()
                                if (localIp == null) {
                                    _state.value =
                                        _state.value.copy(
                                            isHotspotStarting = false,
                                            errorMessage =
                                                "Hotspot started but could not determine IP address. " +
                                                    "Please try again.",
                                        )
                                    hotspotManager.stop()
                                    return@launch
                                }
                                _state.value =
                                    _state.value.copy(
                                        isHotspotStarting = false,
                                        hotspotSsid = info.ssid,
                                        hotspotPassword = info.password,
                                    )
                                launchHttpServer(apkFile, localIp, SharingMode.HOTSPOT)
                            }
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to start hotspot", error)
                        _state.value =
                            _state.value.copy(
                                isHotspotStarting = false,
                                errorMessage =
                                    when (error) {
                                        is SecurityException ->
                                            "Permission required to create a WiFi hotspot. " +
                                                "Please grant the permission and try again."
                                        else ->
                                            error.message ?: "Could not start WiFi hotspot"
                                    },
                            )
                    }
            }
        }

        private suspend fun launchHttpServer(
            apkFile: File,
            localIp: String,
            mode: SharingMode,
        ) {
            // Obtain the readiness signal before launching so both the
            // caller and the server use the same CompletableDeferred instance.
            val portDeferred = server.prepareStart()

            // Launch the accept loop in a child coroutine
            viewModelScope.launch { server.start(apkFile) }

            // Await actual server readiness instead of using a fixed delay
            val port = portDeferred.await()
            if (port == 0) {
                _state.value =
                    _state.value.copy(
                        errorMessage = "Failed to start sharing server",
                    )
                return
            }

            val downloadUrl = "http://$localIp:$port"
            Log.i(TAG, "APK sharing server ready at: $downloadUrl (mode=$mode)")

            _state.value =
                ApkSharingState(
                    isServerRunning = true,
                    downloadUrl = downloadUrl,
                    localIp = localIp,
                    apkSizeBytes = apkFile.length(),
                    sharingMode = mode,
                    hotspotSsid = _state.value.hotspotSsid,
                    hotspotPassword = _state.value.hotspotPassword,
                )
        }

        /**
         * Check whether all permissions needed for [LocalHotspotManager] are granted.
         */
        private fun hasHotspotPermissions(): Boolean =
            getRequiredHotspotPermissions().all { permission ->
                ContextCompat.checkSelfPermission(application, permission) ==
                    PackageManager.PERMISSION_GRANTED
            }

        /**
         * Returns the list of permissions that need to be requested for hotspot sharing.
         */
        fun getRequiredHotspotPermissions(): Array<String> {
            val permissions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            return permissions.toTypedArray()
        }

        /**
         * Stop the local HTTP server.
         */
        private fun stopServer() {
            server.stop()
            serverJob?.cancel()
            serverJob = null
            hotspotManager.stop()
            _state.value = _state.value.copy(isServerRunning = false)
            Log.i(TAG, "Server stopped")
        }

        /**
         * Create a share intent for the APK file using Android's share sheet.
         * This allows sharing via Bluetooth, Nearby Share, etc.
         */
        fun createShareIntent(): Intent? {
            val apkFile = cachedApkFile ?: prepareApkFile()
            if (apkFile == null || !apkFile.exists()) {
                Log.e(TAG, "APK file not available for sharing")
                return null
            }
            cachedApkFile = apkFile

            return try {
                val uri =
                    FileProvider.getUriForFile(
                        application,
                        "${application.packageName}.fileprovider",
                        apkFile,
                    )

                Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create share intent", e)
                null
            }
        }

        /**
         * Load the app launcher icon as a base64-encoded PNG string
         * for embedding in the download page HTML.
         */
        private fun loadIconBase64(): String? =
            try {
                val resources = application.resources
                val iconResId = android.R.mipmap.sym_def_app_icon
                // Use our own launcher icon if available, fall back to default
                val resId =
                    try {
                        R.mipmap.ic_launcher
                    } catch (_: Exception) {
                        iconResId
                    }
                val drawable =
                    resources.getDrawableForDensity(
                        resId,
                        android.util.DisplayMetrics.DENSITY_XHIGH,
                        null,
                    )
                if (drawable != null) {
                    val bitmap =
                        android.graphics.Bitmap.createBitmap(
                            drawable.intrinsicWidth,
                            drawable.intrinsicHeight,
                            android.graphics.Bitmap.Config.ARGB_8888,
                        )
                    val canvas = android.graphics.Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    val stream = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                    bitmap.recycle()
                    android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not load app icon for download page", e)
                null
            }

        override fun onCleared() {
            super.onCleared()
            stopServer()
            // Clean up cached APK
            cachedApkFile?.delete()
            cachedApkFile = null
        }
    }
