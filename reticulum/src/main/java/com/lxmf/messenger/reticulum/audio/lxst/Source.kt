package com.lxmf.messenger.reticulum.audio.lxst

/**
 * Base Source interface for LXST audio pipeline.
 *
 * Matches Python LXST Sources.py structure. Sources capture audio
 * and push encoded frames to sinks.
 */
abstract class Source {
    /** Sample rate in Hz (e.g., 48000, 8000) */
    abstract var sampleRate: Int

    /** Number of audio channels (1 for mono, 2 for stereo) */
    abstract var channels: Int

    /** Start capturing audio */
    abstract fun start()

    /** Stop capturing audio */
    abstract fun stop()

    /** Check if source is currently capturing */
    abstract fun isRunning(): Boolean
}

/**
 * LocalSource - base class for local audio capture (microphone).
 *
 * Subclasses: LineSource (microphone)
 * Future: OpusFileSource (file playback) - out of scope for Phase 8
 */
abstract class LocalSource : Source()

/**
 * RemoteSource - base class for network audio (future).
 *
 * Used for receiving audio from remote peers via Reticulum links.
 * Out of scope for Phase 8.
 */
abstract class RemoteSource : Source()
