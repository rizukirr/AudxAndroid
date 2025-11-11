package com.android.audx

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Result of processing one frame through the denoiser
 *
 * @property vadProbability Voice Activity Detection probability (0.0 to 1.0)
 *                          Higher values indicate higher likelihood of speech
 * @property isSpeech True if vadProbability exceeds the configured threshold
 * @property samplesProcessed Number of samples processed (should be 480 per channel)
 */
data class DenoiserResult(
    val vadProbability: Float,
    val isSpeech: Boolean,
    val samplesProcessed: Int
)

/**
 * Callback for receiving processed audio chunks in streaming mode
 */
typealias ProcessedAudioCallback = (denoisedAudio: ShortArray, result: DenoiserResult) -> Unit

/**
 * Audio denoiser for real-time processing
 *
 * REQUIREMENTS:
 * - Sample rate: 48kHz (48000 Hz)
 * - Audio format: 16-bit signed PCM (Short/int16_t)
 * - Frame size: 480 samples (10ms at 48kHz)
 * - Channel: Mono (single-channel) only
 *
 * The denoiser processes audio in fixed frames of 480 samples.
 */
class AudxDenoiser private constructor(
    modelPreset: ModelPreset,
    vadThreshold: Float,
    enableVadOutput: Boolean,
    private val modelPath: String?,
    private val processedAudioCallback: ProcessedAudioCallback?
) : AutoCloseable {

    companion object {
        private const val TAG = "Denoiser"

        init {
            try {
                System.loadLibrary("audx")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }

        // Audio format constants from native library (single source of truth)

        /**
         * Required sample rate for audio processing (from native: AUDX_SAMPLE_RATE_48KHZ)
         */
        @JvmStatic
        val SAMPLE_RATE: Int
            get() = getSampleRateNative()

        /**
         * Number of audio channels supported (from native: AUDX_CHANNELS_MONO)
         * Always 1 for mono audio
         */
        @JvmStatic
        val CHANNELS: Int
            get() = getChannelsNative()

        /**
         * Bit depth for audio samples (from native: AUDX_BIT_DEPTH_16)
         * 16-bit signed PCM (Short/int16_t)
         */
        @JvmStatic
        val BIT_DEPTH: Int
            get() = getBitDepthNative()

        /**
         * Frame size in samples (from native: AUDX_FRAME_SIZE)
         * 480 samples at 48kHz (10ms) for mono audio
         */
        @JvmStatic
        val FRAME_SIZE: Int
            get() = getFrameSizeNative()

        // JNI functions to retrieve native constants
        @JvmStatic
        private external fun getSampleRateNative(): Int

        @JvmStatic
        private external fun getChannelsNative(): Int

        @JvmStatic
        private external fun getBitDepthNative(): Int

        @JvmStatic
        private external fun getFrameSizeNative(): Int

        /**
         * Calculate the required buffer size for AudioRecord at 48kHz (mono)
         *
         * @param durationMs Duration of buffer in milliseconds (default: 10ms = one frame)
         * @return Buffer size in bytes
         */
        @JvmStatic
        fun getRecommendedBufferSize(durationMs: Int = 10): Int {
            // 48000 samples/sec * (durationMs / 1000) * 1 channel * 2 bytes/sample
            return (SAMPLE_RATE * durationMs / 1000) * 2
        }

        /**
         * Get the frame duration in milliseconds (always 10ms at 48kHz)
         *
         * @return Frame duration in milliseconds
         */
        @JvmStatic
        fun getFrameDurationMs(): Int {
            return (FRAME_SIZE * 1000) / SAMPLE_RATE
        }
    }

    private var nativeHandle: Long = 0

    // Streaming mode: buffer for accumulating samples until we have a complete frame
    private var streamBuffer = ShortArray(FRAME_SIZE * 4)  // Initial capacity: 4 frames
    private var bufferSize = 0  // Current number of samples in buffer
    private val bufferLock = ReentrantLock()
    private val audioDispatcher = Dispatchers.Default.limitedParallelism(1)

    enum class ModelPreset(val value: Int) {
        EMBEDDED(0),
        CUSTOM(1)
    }

    init {
        require(vadThreshold in 0.0f..1.0f) {
            "vadThreshold must be between 0.0 and 1.0"
        }
        if (modelPreset == ModelPreset.CUSTOM) {
            requireNotNull(modelPath) {
                "modelPath is required when using CUSTOM model preset"
            }
            require(File(modelPath).exists()) {
                "Custom model file not found: $modelPath"
            }
        }

        nativeHandle = createNative(
            modelPreset.value,
            modelPath,
            vadThreshold,
            enableVadOutput
        )

        if (nativeHandle == 0L) {
            throw RuntimeException("Failed to create native denoiser")
        }

        Log.i(
            TAG,
            "Denoiser initialized (channels=1, preset=$modelPreset, vad=$vadThreshold)"
        )
    }

    /**
     * Builder for Denoiser
     *
     * Example usage:
     * ```
     * val denoiser = Denoiser.Builder()
     *     .vadThreshold(0.5f)
     *     .onProcessedAudio { denoisedAudio, result ->
     *         // Handle denoised audio (48kHz, 16-bit PCM mono)
     *     }
     *     .build()
     *
     * // Feed audio in chunks (must be 48kHz PCM mono)
     * denoiser.processChunk(audioData)
     * ```
     */
    class Builder {
        private var modelPreset: ModelPreset = ModelPreset.EMBEDDED
        private var modelPath: String? = null
        private var vadThreshold: Float = 0.5f
        private var enableVadOutput: Boolean = true
        private var processedAudioCallback: ProcessedAudioCallback? = null

        /**
         * Set model preset (EMBEDDED or CUSTOM)
         * @param value Model preset
         */
        fun modelPreset(value: ModelPreset) = apply { this.modelPreset = value }

        /**
         * Set path to custom model file (required for CUSTOM preset)
         * @param value Path to model file
         */
        fun modelPath(value: String?) = apply { this.modelPath = value }

        /**
         * Set VAD (Voice Activity Detection) threshold
         * @param value Threshold between 0.0 and 1.0 (default: 0.5)
         */
        fun vadThreshold(value: Float) = apply { this.vadThreshold = value }

        /**
         * Enable/disable VAD output in results
         * @param value Enable VAD output (default: true)
         */
        fun enableVadOutput(value: Boolean) = apply { this.enableVadOutput = value }

        /**
         * Set callback for streaming mode. When set, use processChunk() to feed audio.
         * The callback receives denoised audio in the same format as input (48kHz, 16-bit PCM).
         *
         * @param callback Function that receives (denoisedAudio, result) for each processed frame
         */
        fun onProcessedAudio(callback: ProcessedAudioCallback?) = apply {
            this.processedAudioCallback = callback
        }

        fun build(): AudxDenoiser {
            return AudxDenoiser(
                modelPreset = modelPreset,
                modelPath = modelPath,
                vadThreshold = vadThreshold,
                enableVadOutput = enableVadOutput,
                processedAudioCallback = processedAudioCallback
            )
        }
    }


    /**
     * Process audio chunk of any size for real-time streaming.
     * Internally buffers samples until complete frames are available.
     * Processed audio is delivered via the callback set in Builder.onProcessedAudio()
     *
     * IMPORTANT: Input audio must be 48kHz, 16-bit PCM mono format.
     *
     * Performance: Uses primitive array buffer to avoid boxing overhead and GC pressure
     * during continuous real-time streaming. After initial warm-up, operates with
     * zero allocations per chunk (except for frame output arrays).
     *
     * @param input Audio samples at 48kHz mono (any size, will be buffered internally)
     * @throws IllegalStateException if no callback was set in builder
     * @throws IllegalArgumentException if audio chunk is invalid
     */
    suspend fun processChunk(input: ShortArray) = withContext(audioDispatcher) {
        check(nativeHandle != 0L) { "Denoiser has been destroyed" }
        requireNotNull(processedAudioCallback) {
            "processChunk requires a callback. Use Builder.onProcessedAudio() to set one."
        }

        // Validate audio chunk
        when (val result = AudxValidator.validateChunk(input)) {
            is ValidationResult.Success -> {
                // Chunk is valid, proceed
            }
            is ValidationResult.Error -> {
                throw IllegalArgumentException("Invalid audio chunk: ${result.message}")
            }
        }

        bufferLock.withLock {
            // Ensure buffer has enough capacity
            val requiredCapacity = bufferSize + input.size
            if (requiredCapacity > streamBuffer.size) {
                // Grow buffer (double size or fit required capacity, whichever is larger)
                val newCapacity = maxOf(streamBuffer.size * 2, requiredCapacity)
                streamBuffer = streamBuffer.copyOf(newCapacity)
                Log.d(TAG, "Buffer resized to $newCapacity samples")
            }

            // Copy input samples to buffer using fast native memcpy
            System.arraycopy(input, 0, streamBuffer, bufferSize, input.size)
            bufferSize += input.size

            // Process all complete frames in the buffer
            while (bufferSize >= FRAME_SIZE) {
                // Extract frame directly from buffer (single allocation)
                val frame = streamBuffer.copyOfRange(0, FRAME_SIZE)

                // Process the frame
                val output = ShortArray(FRAME_SIZE)
                val result = processNative(nativeHandle, frame, output)

                // Deliver via callback
                if (result != null) {
                    processedAudioCallback.invoke(output, result)
                } else {
                    Log.w(TAG, "Native processing returned null for chunk")
                }

                // Remove processed frame from buffer using fast native memcpy
                val remainingSize = bufferSize - FRAME_SIZE
                if (remainingSize > 0) {
                    System.arraycopy(streamBuffer, FRAME_SIZE, streamBuffer, 0, remainingSize)
                }
                bufferSize = remainingSize
            }
        }
    }

    /**
     * Flush any remaining buffered samples by processing them as a partial frame.
     * Call this when stopping recording to ensure all audio is processed.
     * Only needed in streaming mode (when using processChunk).
     */
    suspend fun flush() = withContext(audioDispatcher) {
        check(nativeHandle != 0L) { "Denoiser has been destroyed" }

        if (processedAudioCallback == null) return@withContext

        bufferLock.withLock {
            if (bufferSize == 0) return@withContext

            // Pad remaining samples to complete frame with zeros
            val remaining = bufferSize
            val paddingNeeded = FRAME_SIZE - remaining

            // Create frame with padding
            val frame = ShortArray(FRAME_SIZE)
            System.arraycopy(streamBuffer, 0, frame, 0, remaining)
            // Remaining elements are already zero-initialized in ShortArray

            // Process the final padded frame
            val output = ShortArray(FRAME_SIZE)
            val result = processNative(nativeHandle, frame, output)

            // Deliver only the non-padded portion via callback
            if (result != null) {
                val actualOutput = output.copyOfRange(0, remaining)
                processedAudioCallback.invoke(actualOutput, result)
            }

            // Clear buffer
            bufferSize = 0

            Log.d(TAG, "Flushed $remaining remaining samples (padded with $paddingNeeded zeros)")
        }
    }

    /**
     * Check if voice activity is detected
     */
    fun isVoiceDetected(vadProbability: Float, threshold: Float = 0.5f): Boolean {
        return vadProbability >= threshold
    }

    /**
     * Release resources
     */
    override fun close() {
        destroy()
    }

    /**
     * Destroy the denoiser and free native resources
     */
    fun destroy() {
        if (nativeHandle != 0L) {
            bufferLock.withLock {
                // Reset buffer to initial size to free memory
                streamBuffer = ShortArray(FRAME_SIZE * 4)
                bufferSize = 0
            }
            destroyNative(nativeHandle)
            Log.i(TAG, "Denoiser destroyed (handle=$nativeHandle)")
            nativeHandle = 0
        }
    }

    // Native bindings
    private external fun createNative(
        modelPreset: Int,
        modelPath: String?,
        vadThreshold: Float,
        enableVadOutput: Boolean
    ): Long

    private external fun destroyNative(handle: Long)
    private external fun processNative(
        handle: Long,
        input: ShortArray,
        output: ShortArray
    ): DenoiserResult?
}
