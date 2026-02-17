package com.lxmf.messenger

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.MutableState
import com.lxmf.messenger.notifications.CallNotificationHelper
import com.lxmf.messenger.notifications.NotificationHelper
import com.lxmf.messenger.util.Base32
import com.lxmf.messenger.util.FileUtils

class MainActivityIntentHandler(
    private val activity: MainActivity,
    private val pendingNavigation: MutableState<PendingNavigation?>,
    private val logTag: String,
    private val onUsbDeviceAttached: (UsbDevice) -> Unit,
) {
    fun handle(intent: Intent) {
        when (intent.action) {
            NotificationHelper.ACTION_OPEN_ANNOUNCE -> handleOpenAnnounce(intent)
            NotificationHelper.ACTION_OPEN_CONVERSATION -> handleOpenConversation(intent)
            Intent.ACTION_VIEW -> handleActionView(intent)
            Intent.ACTION_SEND -> handleActionSend(intent)
            Intent.ACTION_SEND_MULTIPLE -> handleActionSendMultiple(intent)
            Intent.ACTION_PROCESS_TEXT -> handleActionProcessText(intent)
            CallNotificationHelper.ACTION_OPEN_CALL -> handleOpenCall(intent)
            CallNotificationHelper.ACTION_ANSWER_CALL -> handleAnswerCall(intent)
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleUsbDeviceAttachedIntent(intent)
        }
    }

    private fun handleOpenAnnounce(intent: Intent) {
        val destinationHash = intent.getStringExtra(NotificationHelper.EXTRA_DESTINATION_HASH)
        if (destinationHash != null) {
            Log.d(logTag, "Opening announce detail for: $destinationHash")
            pendingNavigation.value = PendingNavigation.AnnounceDetail(destinationHash)
        }
    }

    private fun handleOpenConversation(intent: Intent) {
        val destinationHash = intent.getStringExtra(NotificationHelper.EXTRA_DESTINATION_HASH)
        val peerName = intent.getStringExtra(NotificationHelper.EXTRA_PEER_NAME)
        if (destinationHash != null && peerName != null) {
            Log.d(logTag, "Opening conversation with: $peerName ($destinationHash)")
            pendingNavigation.value = PendingNavigation.Conversation(destinationHash, peerName)
        }
    }

    private fun handleActionView(intent: Intent) {
        val data = intent.data
        if (data != null && data.scheme == "lxma") {
            val lxmaUrl = data.toString()
            Log.d(logTag, "Opening LXMF deep link: $lxmaUrl")
            pendingNavigation.value = PendingNavigation.AddContact(lxmaUrl)
        }
    }

    private fun handleActionSend(intent: Intent) {
        val mimeType = intent.type

        if (mimeType != null && mimeType.startsWith("image/")) {
            handleActionSendImage(intent)
            return
        }

        val isTextShare = mimeType == null || mimeType.startsWith("text/")
        if (!isTextShare) {
            Log.w(logTag, "ACTION_SEND received with non-text mimeType=$mimeType (ignored)")
            return
        }

        val extrasKeys = intent.extras?.keySet()?.joinToString() ?: "<none>"
        val clipLabel =
            intent.clipData
                ?.description
                ?.label
                ?.toString() ?: "<none>"
        val sharedText =
            intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
                ?: intent.clipData
                    ?.getItemAt(0)
                    ?.coerceToText(activity)
                    ?.toString()

        if (!sharedText.isNullOrBlank()) {
            Log.d(
                logTag,
                "Received shared text (mimeType=$mimeType, extras=[$extrasKeys], clipLabel=$clipLabel): ${sharedText.take(120)}",
            )
            triggerSharedText(sharedText)
        } else {
            Log.w(
                logTag,
                "ACTION_SEND received with text/* mimeType=$mimeType but no text found (extras=[$extrasKeys], clipLabel=$clipLabel)",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun handleActionSendImage(intent: Intent) {
        val uri: Uri? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

        if (uri != null) {
            Log.d(logTag, "Received shared image (ACTION_SEND): $uri")
            triggerSharedImages(listOf(uri))
        } else {
            Log.w(logTag, "ACTION_SEND received with image/* but no EXTRA_STREAM URI found")
        }
    }

    private fun handleActionSendMultiple(intent: Intent) {
        val mimeType = intent.type

        if (mimeType != null && mimeType.startsWith("image/")) {
            handleActionSendMultipleImages(intent)
            return
        }

        val isTextShare = mimeType == null || mimeType.startsWith("text/")
        if (!isTextShare) {
            Log.w(logTag, "ACTION_SEND_MULTIPLE received with non-text mimeType=$mimeType (ignored)")
            return
        }

        val extrasKeys = intent.extras?.keySet()?.joinToString() ?: "<none>"
        val clipLabel =
            intent.clipData
                ?.description
                ?.label
                ?.toString() ?: "<none>"
        val sharedTextList = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
        val sharedText =
            sharedTextList?.joinToString("\n") { it.toString() }
                ?: intent.clipData?.let { clip ->
                    (0 until clip.itemCount)
                        .mapNotNull { idx -> clip.getItemAt(idx).coerceToText(activity)?.toString() }
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                }

        if (!sharedText.isNullOrBlank()) {
            Log.d(
                logTag,
                "Received shared text (SEND_MULTIPLE mimeType=$mimeType, extras=[$extrasKeys], clipLabel=$clipLabel): ${sharedText.take(120)}",
            )
            triggerSharedText(sharedText)
        } else {
            Log.w(
                logTag,
                "ACTION_SEND_MULTIPLE received with text/* mimeType=$mimeType but no text found (extras=[$extrasKeys], clipLabel=$clipLabel)",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun handleActionSendMultipleImages(intent: Intent) {
        val uris: List<Uri> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            } else {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            }

        if (uris.isNotEmpty()) {
            Log.d(logTag, "Received ${uris.size} shared images (ACTION_SEND_MULTIPLE)")
            triggerSharedImages(uris)
        } else {
            Log.w(logTag, "ACTION_SEND_MULTIPLE received with image/* but no EXTRA_STREAM URIs found")
        }
    }

    private fun handleActionProcessText(intent: Intent) {
        val extrasKeys = intent.extras?.keySet()?.joinToString() ?: "<none>"
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (!sharedText.isNullOrBlank()) {
            Log.d(logTag, "Received processed text (PROCESS_TEXT extras=[$extrasKeys]): ${sharedText.take(120)}")
            triggerSharedText(sharedText)
        } else {
            Log.w(logTag, "ACTION_PROCESS_TEXT received but EXTRA_PROCESS_TEXT is empty (extras=[$extrasKeys])")
        }
    }

    private fun triggerSharedText(sharedText: String) {
        pendingNavigation.value = null
        if (Base32.isIdentityKey(sharedText)) {
            Log.d(logTag, "Shared text is a valid identity key, routing to identity import")
            pendingNavigation.value = PendingNavigation.ImportIdentityFromText(sharedText.trim())
        } else {
            pendingNavigation.value = PendingNavigation.SharedText(sharedText)
        }
    }

    private fun triggerSharedImages(uris: List<Uri>) {
        // Eagerly copy content:// URIs to app-private temp files while permissions
        // are still valid. Content URIs from ACTION_SEND are ephemeral — the sender
        // may revoke access once our Activity is paused during destination selection.
        val stableUris =
            uris.mapIndexedNotNull { index, uri ->
                FileUtils.copyUriToTempFile(activity, uri, index)
            }
        if (stableUris.isEmpty()) {
            Log.w(logTag, "All shared image URIs failed to copy to temp files")
            return
        }
        pendingNavigation.value = null
        pendingNavigation.value = PendingNavigation.SharedImage(stableUris)
    }

    private fun handleOpenCall(intent: Intent) {
        val identityHash = intent.getStringExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH)
        if (identityHash != null) {
            Log.d(logTag, "Opening incoming call screen for: ${identityHash.take(16)}...")
            pendingNavigation.value = PendingNavigation.IncomingCall(identityHash)
        }
    }

    private fun handleAnswerCall(intent: Intent) {
        val identityHash = intent.getStringExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH)
        Log.w(logTag, "📞 ACTION_ANSWER_CALL received! identityHash=$identityHash")
        if (identityHash != null) {
            Log.w(logTag, "📞 Setting pendingNavigation to AnswerCall($identityHash)")
            CallNotificationHelper(activity).cancelIncomingCallNotification()
            pendingNavigation.value = PendingNavigation.AnswerCall(identityHash)
            Log.w(logTag, "📞 pendingNavigation.value is now: ${pendingNavigation.value}")
        } else {
            Log.e(logTag, "📞 identityHash is NULL! Cannot navigate to call")
        }
    }

    private fun handleUsbDeviceAttachedIntent(intent: Intent) {
        Log.d(logTag, "🔌 USB_DEVICE_ATTACHED action matched!")
        @Suppress("DEPRECATION")
        val usbDevice: UsbDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
        if (usbDevice != null) {
            Log.d(logTag, "🔌 USB device attached: ${usbDevice.deviceName} (${usbDevice.deviceId})")
            onUsbDeviceAttached(usbDevice)
        } else {
            Log.e(logTag, "🔌 USB device is null in intent extras!")
        }
    }
}
