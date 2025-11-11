package com.example.audx.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.android.audx.AudxDenoiser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Audio player that uses AudxDenoiser constants for playback configuration
 *
 * This ensures playback format matches the recording format:
 * - Uses AudxDenoiser.SAMPLE_RATE (48000 Hz)
 * - Uses AudxDenoiser.CHANNELS (1 for mono)
 * - Uses AudxDenoiser.BIT_DEPTH (16-bit)
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private val lock = ReentrantLock()

    /**
     * Initialize AudioTrack with AudxDenoiser constants
     */
    fun initialize(audioData: ShortArray): Result<Unit> {
        return try {
            // Get the minimum buffer size required by the system
            val minBufferSize = AudioTrack.getMinBufferSize(
                AudxDenoiser.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBufferSize == AudioTrack.ERROR_BAD_VALUE || minBufferSize == AudioTrack.ERROR) {
                return Result.failure(IllegalStateException("Invalid AudioTrack configuration"))
            }

            Log.i(TAG, "Initializing AudioTrack with:")
            Log.i(TAG, "  Sample Rate: ${AudxDenoiser.SAMPLE_RATE} Hz")
            Log.i(TAG, "  Channels: ${AudxDenoiser.CHANNELS} (Mono)")
            Log.i(TAG, "  Bit Depth: ${AudxDenoiser.BIT_DEPTH}-bit PCM")
            Log.i(TAG, "  Min Buffer Size: $minBufferSize bytes")
            Log.i(TAG, "  Audio Length: ${audioData.size} samples")

            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(AudxDenoiser.SAMPLE_RATE)
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

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
            Result.failure(e)
        }
    }

    /**
     * Play the audio buffer
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
            val chunkSize = 4800 // 100ms chunks at 48kHz

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
     * Stop playback
     *
     * Sets isPlaying flag to false, which will cause the play() loop to exit gracefully
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
     * Release AudioTrack resources
     *
     * Stops playback and releases the AudioTrack safely
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
     * Check if currently playing (thread-safe)
     */
    fun isPlaying(): Boolean = lock.withLock { isPlaying }
}
