package com.lxmf.messenger.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records audio for SOS emergency messages using AAC codec in M4A container.
 *
 * Produces compact audio files suitable for LXMF FIELD_AUDIO:
 * ~24 kbps AAC = ~90 KB for 30 seconds, well within the 1 MB message limit.
 */
@Singleton
class SosAudioRecorder
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val TAG = "SosAudioRecorder"
            private const val SAMPLE_RATE = 16000
            private const val BIT_RATE = 24000
        }

        private var recorder: MediaRecorder? = null
        private var outputFile: File? = null

        private val lock = Any()

        val isRecording: Boolean get() = synchronized(lock) { recorder != null }

        fun hasPermission(): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

        /**
         * Start recording audio to a temporary file.
         *
         * @return true if recording started, false if permission missing or error.
         */
        fun start(): Boolean {
            if (!hasPermission()) {
                Log.w(TAG, "RECORD_AUDIO permission not granted")
                return false
            }

            synchronized(lock) {
                if (recorder != null) {
                    Log.w(TAG, "Already recording, ignoring start")
                    return true
                }

                return try {
                    val file = File(context.cacheDir, "sos_audio.m4a")
                    outputFile = file

                    @Suppress("DEPRECATION")
                    val mr =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            MediaRecorder(context)
                        } else {
                            MediaRecorder()
                        }

                    recorder = mr
                    mr.apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioSamplingRate(SAMPLE_RATE)
                        setAudioEncodingBitRate(BIT_RATE)
                        setAudioChannels(1)
                        setOutputFile(file.absolutePath)
                        prepare()
                        start()
                    }
                    Log.d(TAG, "Audio recording started: ${file.absolutePath}")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start audio recording", e)
                    cleanup()
                    false
                }
            }
        }

        /**
         * Stop recording and return the audio bytes.
         *
         * @return audio data as ByteArray, or null on error.
         */
        /** Stop the recorder (must be called on main thread for MediaRecorder). */
        fun stopRecorder() {
            synchronized(lock) {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop recorder", e)
                }
                recorder = null
            }
        }

        /** Read and delete the output file (safe to call from IO thread). */
        fun readAndDeleteOutputFile(): ByteArray? {
            val file = synchronized(lock) {
                val f = outputFile
                outputFile = null
                f
            } ?: return null
            return try {
                val bytes = file.readBytes()
                file.delete()
                Log.d(TAG, "Audio recording stopped: ${bytes.size} bytes")
                bytes
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read audio file", e)
                null
            }
        }

        /**
         * Cancel recording without returning data.
         */
        fun cancel() {
            synchronized(lock) { cleanup() }
            Log.d(TAG, "Audio recording cancelled")
        }

        private fun cleanup() {
            try {
                recorder?.stop()
            } catch (_: Exception) {
                // Ignore — may not have started
            }
            try {
                recorder?.release()
            } catch (_: Exception) {
                // Ignore
            }
            recorder = null
            outputFile?.delete()
            outputFile = null
        }
    }
