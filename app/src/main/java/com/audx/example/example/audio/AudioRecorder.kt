package com.audx.example.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Wrapper for Android's AudioRecord that captures microphone audio at 16kHz.
 *
 * This class demonstrates using the Audx library's automatic resampling feature:
 * - Records at 16kHz (lower sample rate reduces bandwidth and processing)
 * - Audx automatically resamples to 48kHz internally for denoising
 * - Output from Audx is downsampled back to 16kHz
 * - Requires initializing Audx with `.inputRate(16000)`
 *
 * Audio configuration:
 * - Sample rate: 16kHz
 * - Channels: Mono (1 channel)
 * - Encoding: PCM 16-bit
 * - Frame size: 160 samples (10ms at 16kHz)
 *
 * Usage:
 * ```kotlin
 * val recorder = AudioRecorder()
 * recorder.initialize().getOrThrow()
 * recorder.startRecording()
 *     .collect { audioChunk ->
 *         // Process 160-sample chunks (10ms at 16kHz)
 *     }
 * recorder.release()
 * ```
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"

        /**
         * Sample rate for audio recording (16kHz).
         *
         * This lower sample rate is sufficient for voice and reduces bandwidth.
         * Audx will automatically resample to 48kHz internally, then back to 16kHz for output.
         */
        const val SAMPLE_RATE = 16000

        /**
         * Frame size in samples for 10ms audio chunks at 16kHz.
         *
         * Calculation: 16000 samples/sec × 0.01 sec = 160 samples
         * This matches the standard 10ms frame duration used in speech processing.
         */
        const val FRAME_SIZE = 160
    }

    /** Android AudioRecord instance for capturing microphone input */
    private var audioRecord: AudioRecord? = null

    /**
     * Initializes the AudioRecord for capturing microphone audio at 16kHz.
     *
     * Sets up audio recording with:
     * - VOICE_RECOGNITION source (optimized for speech)
     * - 16kHz sample rate
     * - Mono channel
     * - PCM 16-bit encoding
     * - Buffer size of at least 4x the minimum required
     *
     * @return Result.success if initialization succeeded, Result.failure with exception otherwise
     */
    @SuppressLint("MissingPermission")
    fun initialize(): Result<Unit> {
        return try {
            // Get minimum buffer size required by Android
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
                return Result.failure(IllegalStateException("Invalid AudioRecord configuration"))
            }

            // Use at least 4x the minimum buffer size for stable recording
            val bufferSize = maxOf(minBufferSize * 4, FRAME_SIZE * 10)

            Log.i(TAG, "AudioRecord buffer: min=$minBufferSize, using=$bufferSize")

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
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
     * Starts recording audio and emits 10ms chunks as a Flow.
     *
     * Continuously reads audio from the microphone and emits ShortArray chunks of 160 samples
     * (10ms at 16kHz). The Flow runs on Dispatchers.IO and will continue until cancelled or
     * an error occurs.
     *
     * The recording stops automatically when:
     * - The coroutine is cancelled
     * - An AudioRecord error occurs
     * - release() is called
     *
     * @return Flow emitting ShortArray audio chunks (160 samples each)
     * @throws IllegalStateException if AudioRecord is not initialized
     */
    fun startRecording(): Flow<ShortArray> = flow {
        val record = audioRecord ?: throw IllegalStateException("AudioRecord not initialized")

        // At 16kHz: 10ms = 160 samples
        // This provides a good balance between latency and efficiency
        val buffer = ShortArray(FRAME_SIZE)

        record.startRecording()
        Log.i(TAG, "Recording started at $SAMPLE_RATE Hz")

        try {
            while (currentCoroutineContext().isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val samplesRead = record.read(buffer, 0, buffer.size)

                if (samplesRead > 0) {
                    // Emit the audio chunk (will be resampled by AudxDenoiser)
                    val chunk = buffer.copyOf(samplesRead)
                    emit(chunk)
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
     * Releases AudioRecord resources and stops recording if active.
     *
     * This method safely stops any ongoing recording, releases the AudioRecord instance,
     * and clears the reference. Safe to call multiple times or when not recording.
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
