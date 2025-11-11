package com.example.audx.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.android.audx.AudxDenoiser
import com.android.audx.AudxValidator
import com.android.audx.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Audio recorder that uses AudxDenoiser constants for all audio configuration
 *
 * This class demonstrates best practices for using the audx-android library:
 * - Uses AudxDenoiser.SAMPLE_RATE (48000 Hz)
 * - Uses AudxDenoiser.CHANNELS (1 for mono)
 * - Uses AudxDenoiser.BIT_DEPTH (16-bit)
 * - Uses AudxDenoiser.FRAME_SIZE (480 samples)
 * - Uses AudxDenoiser.getRecommendedBufferSize() for buffer sizing
 * - Uses AudxValidator for format validation
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
    }

    private var audioRecord: AudioRecord? = null

    /**
     * Initialize AudioRecord with AudxDenoiser constants
     *
     * This ensures the recording format matches exactly what AudxDenoiser expects.
     */
    @SuppressLint("MissingPermission")
    fun initialize(): Result<Unit> {
        return try {
            // Validate audio format before creating AudioRecord
            val validationResult = AudxValidator.validateFormat(
                sampleRate = AudxDenoiser.SAMPLE_RATE,
                channels = AudxDenoiser.CHANNELS,
                bitDepth = AudxDenoiser.BIT_DEPTH
            )

            if (validationResult is ValidationResult.Error) {
                return Result.failure(IllegalStateException("Audio format validation failed: ${validationResult.message}"))
            }

            // Calculate buffer size using AudxDenoiser utility
            // Using 40ms buffer (4 frames) for stable recording and prevent underruns
            val bufferSize = AudxDenoiser.getRecommendedBufferSize(40)

            Log.i(TAG, "Initializing AudioRecord with:")
            Log.i(TAG, "  Sample Rate: ${AudxDenoiser.SAMPLE_RATE} Hz")
            Log.i(TAG, "  Channels: ${AudxDenoiser.CHANNELS} (Mono)")
            Log.i(TAG, "  Bit Depth: ${AudxDenoiser.BIT_DEPTH}-bit PCM")
            Log.i(TAG, "  Frame Size: ${AudxDenoiser.FRAME_SIZE} samples")
            Log.i(TAG, "  Frame Duration: ${AudxDenoiser.getFrameDurationMs()}ms")
            Log.i(TAG, "  Buffer Size: $bufferSize bytes")

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AudxDenoiser.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                return Result.failure(IllegalStateException("AudioRecord initialization failed"))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioRecord", e)
            Result.failure(e)
        }
    }

    /**
     * Start recording and emit audio chunks as Flow
     *
     * Uses AudxDenoiser.FRAME_SIZE for buffer sizing to align with frame processing.
     * Each emitted chunk will be approximately one or two frames.
     */
    fun startRecording(): Flow<ShortArray> = flow {
        val record = audioRecord ?: throw IllegalStateException("AudioRecord not initialized")

        // Use frame size for buffer (can read multiple frames at once)
        // Using 2 frames (960 samples) for efficient reading
        val bufferSize = AudxDenoiser.FRAME_SIZE * 2
        val buffer = ShortArray(bufferSize)

        record.startRecording()
        Log.i(TAG, "Recording started")

        try {
            while (currentCoroutineContext().isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val samplesRead = record.read(buffer, 0, buffer.size)

                if (samplesRead > 0) {
                    // Validate chunk before emitting
                    val chunk = buffer.copyOf(samplesRead)
                    when (val result = AudxValidator.validateChunk(chunk)) {
                        is ValidationResult.Success -> {
                            emit(chunk)
                        }
                        is ValidationResult.Error -> {
                            Log.w(TAG, "Invalid audio chunk: ${result.message}")
                        }
                    }
                } else if (samplesRead < 0) {
                    Log.e(TAG, "AudioRecord read error: $samplesRead")
                    break
                }
            }
        } finally {
            // Stop recording if still active
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
                Log.i(TAG, "Recording stopped in finally block")
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Release AudioRecord resources
     */
    fun release() {
        audioRecord?.apply {
            try {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        stop()
                    }
                    release()
                }
            } catch (e: IllegalStateException) {
                // AudioRecord was already stopped/released - safe to ignore
                Log.w(TAG, "AudioRecord already stopped: ${e.message}")
            }
        }
        audioRecord = null
        Log.i(TAG, "AudioRecord released")
    }
}
