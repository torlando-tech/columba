package com.lxmf.messenger.ui.components

import android.content.Intent
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Audio player composable for playing audio messages (LXMF FIELD_AUDIO).
 *
 * @param audioBytes Raw audio data bytes (M4A/AAC format)
 * @param modifier Optional modifier
 */
@Composable
fun AudioMessagePlayer(
    audioBytes: ByteArray,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var durationMs by remember { mutableStateOf(0) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var tempFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(audioBytes) {
        // Release any previous player and clean up stale temp file
        withContext(Dispatchers.Main) {
            player?.release()
            player = null
        }
        withContext(Dispatchers.IO) {
            tempFile?.delete()
            tempFile = null
            val file = File(context.cacheDir, "audio_playback_${audioBytes.contentHashCode()}.m4a")
            file.writeBytes(audioBytes)
            tempFile = file
            val mp = MediaPlayer()
            var assigned = false
            try {
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                withContext(Dispatchers.Main) {
                    durationMs = mp.duration
                    mp.setOnCompletionListener {
                        isPlaying = false
                        progress = 0f
                    }
                    player = mp
                    assigned = true
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Coroutine cancelled — release handled in finally
            } catch (e: Exception) {
                Log.e("AudioMessagePlayer", "Failed to prepare player", e)
            } finally {
                if (!assigned) mp.release()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                player?.release()
            } catch (_: Exception) {
            }
            tempFile?.delete()
        }
    }

    // Progress tracking
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val mp = player ?: break
            if (durationMs > 0) {
                progress = mp.currentPosition.toFloat() / durationMs
            }
            delay(200)
        }
    }

    val durationSec = durationMs / 1000
    val formattedDuration = "%d:%02d".format(durationSec / 60, durationSec % 60)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = {
                val mp = player ?: return@IconButton
                if (isPlaying) {
                    mp.pause()
                    isPlaying = false
                } else {
                    mp.start()
                    isPlaying = true
                }
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.weight(1f),
        )

        Text(
            text = formattedDuration,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        IconButton(
            onClick = {
                val src = tempFile ?: return@IconButton
                scope.launch(Dispatchers.IO) {
                    val shareFile = File(context.cacheDir, "sos_audio_share_${src.name}")
                    try {
                        src.copyTo(shareFile, overwrite = true)
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", shareFile,
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "audio/mp4"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        withContext(Dispatchers.Main) {
                            context.startActivity(Intent.createChooser(shareIntent, "Share Audio"))
                        }
                    } finally {
                        shareFile.delete()
                    }
                }
            },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Share,
                contentDescription = "Share",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
