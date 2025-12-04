package com.audx.example.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Wrapper for Android's AudioTrack that plays back PCM audio with dynamic sample rate support.
 *
 * This class provides:
 * - Dynamic sample rate configuration (16kHz for this example app)
 * - Thread-safe playback control with ReentrantLock
 * - Chunk-based streaming with 100ms buffers
 * - Graceful stop and cleanup handling
 * - Automatic playback completion detection
 *
 * Audio configuration:
 * - Channels: Mono (1 channel)
 * - Encoding: PCM 16-bit
 * - Usage: MEDIA
 * - Content type: MUSIC
 *
 * The player is designed to work with audio at the same sample rate it was recorded,
 * ensuring proper playback speed and pitch.
 *
 * Usage:
 * ```kotlin
 * val player = AudioPlayer()
 * player.initialize(16000).getOrThrow()
 * player.play(audioData) // Suspends until playback completes
 * player.release()
 * ```
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
    }

    /** Android AudioTrack instance for audio playback */
    private var audioTrack: AudioTrack? = null

    /** Sample rate for the current AudioTrack configuration */
    private var sampleRate: Int = 0

    /** Flag indicating if playback is currently active (protected by lock) */
    private var isPlaying = false

    /** Lock for thread-safe access to playback state */
    private val lock = ReentrantLock()

    /**
     * Initializes the AudioTrack with the specified sample rate.
     *
     * Creates an AudioTrack configured for media playback with:
     * - MEDIA usage (for music/media content)
     * - MUSIC content type
     * - Mono channel output
     * - PCM 16-bit encoding
     * - Minimum required buffer size
     *
     * @param sampleRate The sample rate in Hz (e.g., 16000 for 16kHz)
     * @return Result.success if initialization succeeded, Result.failure with exception otherwise
     */
    fun initialize(sampleRate: Int): Result<Unit> {
        return try {
            // Get the minimum buffer size required by the system
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBufferSize == AudioTrack.ERROR_BAD_VALUE || minBufferSize == AudioTrack.ERROR) {
                return Result.failure(IllegalStateException("Invalid AudioTrack configuration"))
            }

            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                minBufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                val errorMsg = "AudioTrack initialization failed - state: ${audioTrack?.state}"
                Log.e(TAG, errorMsg)
                return Result.failure(IllegalStateException(errorMsg))
            }

            // Store sample rate for dynamic chunk size calculation
            this.sampleRate = sampleRate

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
            Result.failure(e)
        }
    }

    /**
     * Plays the provided audio buffer to completion or until stopped.
     *
     * This is a suspending function that writes audio data in 100ms chunks and monitors
     * playback progress. The function will suspend until:
     * - All audio has been played
     * - Playback is stopped via stop()
     * - An error occurs
     *
     * Playback details:
     * - Chunk size: 100ms (automatically adjusted to sample rate)
     * - Runs on Dispatchers.IO
     * - Thread-safe with lock protection
     * - Polls playback head position to detect completion
     *
     * @param audioData The audio samples to play (PCM16 format)
     * @throws IllegalStateException if AudioTrack is not initialized
     */
    suspend fun play(audioData: ShortArray) = withContext(Dispatchers.IO) {
        // Check and mark as playing atomically
        lock.withLock {
            val track = audioTrack ?: throw IllegalStateException("AudioTrack not initialized")

            if (isPlaying) {
                Log.w(TAG, "Already playing")
                return@withContext
            }

            isPlaying = true
            track.play()
            Log.i(TAG, "Playback started")
        }

        try {
            // Write audio data in chunks
            var offset = 0
            // 100ms chunks - automatically adjusts to sample rate (1600 at 16kHz, 4800 at 48kHz)
            val chunkSize = sampleRate / 10

            while (offset < audioData.size) {
                // Check if we should continue playing (with lock)
                val shouldContinue = lock.withLock {
                    if (!isPlaying) {
                        Log.i(TAG, "Playback cancelled at offset $offset")
                        return@withLock false
                    }
                    true
                }

                if (!shouldContinue) {
                    break
                }

                // Get track reference safely
                val track = lock.withLock { audioTrack }
                if (track == null) {
                    Log.w(TAG, "AudioTrack was released during playback")
                    break
                }

                val remainingSamples = audioData.size - offset
                val samplesToWrite = minOf(chunkSize, remainingSamples)

                val written = try {
                    track.write(audioData, offset, samplesToWrite)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "AudioTrack write failed: ${e.message}")
                    break
                }

                if (written < 0) {
                    Log.e(TAG, "Error writing to AudioTrack: $written")
                    break
                }

                offset += written
            }

            Log.i(TAG, "Finished writing $offset samples")

            // Wait for playback to complete if not cancelled
            if (lock.withLock { isPlaying }) {
                // Wait for the AudioTrack to finish playing all buffered data
                val track = lock.withLock { audioTrack }
                if (track != null) {
                    // Poll the playback head position to detect when playback completes
                    val targetFrames = offset
                    var lastPosition = 0
                    var stuckCount = 0

                    while (lock.withLock { isPlaying }) {
                        try {
                            val currentPosition = track.playbackHeadPosition

                            // Check if we've played all frames
                            if (currentPosition >= targetFrames) {
                                Log.i(TAG, "Playback completed - reached target position")
                                break
                            }

                            // Detect if playback is stuck (position not advancing)
                            if (currentPosition == lastPosition) {
                                stuckCount++
                                if (stuckCount > 10) {
                                    Log.w(TAG, "Playback appears stuck, completing")
                                    break
                                }
                            } else {
                                stuckCount = 0
                                lastPosition = currentPosition
                            }

                            // Check playback state
                            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                Log.i(TAG, "AudioTrack stopped playing")
                                break
                            }

                            delay(50) // Check every 50ms
                        } catch (e: IllegalStateException) {
                            Log.w(TAG, "Error checking playback position: ${e.message}")
                            break
                        }
                    }
                }
                Log.i(TAG, "Playback completed")
            }
        } finally {
            lock.withLock {
                isPlaying = false
                // Only stop if AudioTrack is still valid and playing
                audioTrack?.let { track ->
                    try {
                        if (track.state == AudioTrack.STATE_INITIALIZED &&
                            track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            track.stop()
                        }
                        track.flush()
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Stops the current playback gracefully.
     *
     * Sets the isPlaying flag to false, causing the play() loop to exit gracefully
     * at the next iteration. This is thread-safe and can be called from any thread.
     * Safe to call multiple times or when not playing.
     */
    fun stop() {
        lock.withLock {
            if (isPlaying) {
                isPlaying = false
                audioTrack?.let { track ->
                    try {
                        if (track.state == AudioTrack.STATE_INITIALIZED &&
                            track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            track.stop()
                            Log.i(TAG, "Playback stopped")
                        }
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Error stopping playback: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Releases AudioTrack resources and stops playback if active.
     *
     * This method safely stops any ongoing playback, releases the AudioTrack instance,
     * and clears all references. Thread-safe and safe to call multiple times.
     * Should be called when done with the player to free system resources.
     */
    fun release() {
        lock.withLock {
            audioTrack?.let { track ->
                try {
                    // Stop playback if running
                    if (track.state == AudioTrack.STATE_INITIALIZED &&
                        track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.stop()
                    }
                    // Release the track
                    track.release()
                    Log.i(TAG, "AudioTrack released")
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Error releasing AudioTrack: ${e.message}")
                }
            }
            audioTrack = null
            isPlaying = false
        }
    }

    /**
     * Checks if audio is currently playing.
     *
     * Thread-safe method that returns the current playback state.
     *
     * @return true if audio is playing, false otherwise
     */
    fun isPlaying(): Boolean = lock.withLock { isPlaying }
}
