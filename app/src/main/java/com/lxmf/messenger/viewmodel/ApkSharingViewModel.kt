package com.lxmf.messenger.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.service.ApkSharingServer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

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
)

/**
 * ViewModel for the APK sharing screen.
 *
 * Manages the lifecycle of the local HTTP server that serves the APK file,
 * and provides the download URL for QR code generation.
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
            private val APK_FILE_NAME = "columba-${com.lxmf.messenger.BuildConfig.VERSION_NAME}.apk"
        }

        private val _state = MutableStateFlow(ApkSharingState())
        val state: StateFlow<ApkSharingState> = _state.asStateFlow()

        private val server = ApkSharingServer().apply { downloadFileName = APK_FILE_NAME }
        private var serverJob: Job? = null
        private var cachedApkFile: File? = null

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
                    if (localIp == null) {
                        _state.value =
                            _state.value.copy(
                                errorMessage =
                                    "No WiFi connection detected. " +
                                        "Both devices must be on the same WiFi network, " +
                                        "or use the \"Share via...\" option below.",
                            )
                        return@launch
                    }

                    // Obtain the readiness signal before launching so both the
                    // caller and the server use the same CompletableDeferred instance.
                    val portDeferred = server.prepareStart()

                    // Launch the accept loop in a child coroutine
                    launch { server.start(apkFile) }

                    // Await actual server readiness instead of using a fixed delay
                    val port = portDeferred.await()
                    if (port == 0) {
                        _state.value =
                            _state.value.copy(
                                errorMessage = "Failed to start sharing server",
                            )
                        return@launch
                    }

                    val downloadUrl = "http://$localIp:$port"
                    Log.i(TAG, "APK sharing server ready at: $downloadUrl")

                    _state.value =
                        ApkSharingState(
                            isServerRunning = true,
                            downloadUrl = downloadUrl,
                            localIp = localIp,
                            apkSizeBytes = apkFile.length(),
                        )
                }
        }

        /**
         * Stop the local HTTP server.
         */
        private fun stopServer() {
            server.stop()
            serverJob?.cancel()
            serverJob = null
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

        override fun onCleared() {
            super.onCleared()
            stopServer()
            // Clean up cached APK
            cachedApkFile?.delete()
            cachedApkFile = null
        }
    }
