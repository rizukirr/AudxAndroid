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
    val vadProbability: Float, val isSpeech: Boolean, val samplesProcessed: Int
)

/**
 * Comprehensive statistics for denoiser performance and behavior
 *
 * Statistics accumulate over the lifetime of the denoiser instance unless
 * explicitly reset using resetStats(). Use getStats() to retrieve current values.
 *
 * @property frameProcessed Total number of frames processed (each frame = 480 samples = 10ms)
 * @property speechDetectedPercent Percentage of frames classified as speech (0-100)
 * @property vadScoreAvg Average VAD (Voice Activity Detection) score across all frames (0.0-1.0)
 * @property vadScoreMin Minimum VAD score observed (0.0-1.0)
 * @property vadScoreMax Maximum VAD score observed (0.0-1.0)
 * @property processingTimeTotal Total processing time in milliseconds for all frames
 * @property processingTimeAvg Average processing time per frame in milliseconds
 * @property processingTimeLast Processing time for the most recent frame in milliseconds
 */
data class DenoiserStats(
    val frameProcessed: Int,
    val speechDetectedPercent: Float,
    val vadScoreAvg: Float,
    val vadScoreMin: Float,
    val vadScoreMax: Float,
    val processingTimeTotal: Float,
    val processingTimeAvg: Float,
    val processingTimeLast: Float
)

/**
 * Callback for receiving processed audio chunks in streaming mode
 */
typealias ProcessedAudioCallback = (denoisedAudio: ShortArray, result: DenoiserResult) -> Unit

/**
 * Audio denoiser for real-time processing
 *
 * REQUIREMENTS:
 * - Sample rate: 48kHz (48000 Hz) or specify inputSampleRate for automatic resampling
 * - Audio format: 16-bit signed PCM (Short/int16_t)
 * - Frame size: 480 samples (10ms at 48kHz) - varies based on inputSampleRate
 * - Channel: Mono (single-channel) only
 *
 * The denoiser processes audio in fixed frames. If inputSampleRate is not 48kHz,
 * audio will be automatically resampled to 48kHz for denoising, then resampled
 * back to the original rate.
 */
class AudxDenoiser private constructor(
    modelPreset: ModelPreset,
    vadThreshold: Float,
    enableVadOutput: Boolean,
    private val modelPath: String?,
    private val processedAudioCallback: ProcessedAudioCallback?,
    private val inputSampleRate: Int,
    private val resampleQuality: Int
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

        // Resampler quality constants
        const val RESAMPLER_QUALITY_MAX = 10
        const val RESAMPLER_QUALITY_MIN = 0
        const val RESAMPLER_QUALITY_DEFAULT = 4
        const val RESAMPLER_QUALITY_VOIP = 3

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

    // Calculate frame size based on input sample rate (10ms chunks)
    private val inputFrameSize: Int

    // Streaming mode: buffer for accumulating samples until we have a complete frame
    private var streamBuffer: ShortArray
    private var bufferSize = 0  // Current number of samples in buffer
    private val bufferLock = ReentrantLock()
    private val audioDispatcher = Dispatchers.Default.limitedParallelism(1)

    enum class ModelPreset(val value: Int) {
        EMBEDDED(0), CUSTOM(1)
    }

    init {
        require(vadThreshold in 0.0f..1.0f) {
            "vadThreshold must be between 0.0 and 1.0"
        }
        require(inputSampleRate > 0) {
            "inputSampleRate must be positive"
        }
        require(resampleQuality in RESAMPLER_QUALITY_MIN..RESAMPLER_QUALITY_MAX) {
            "resampleQuality must be between $RESAMPLER_QUALITY_MIN and $RESAMPLER_QUALITY_MAX"
        }
        if (modelPreset == ModelPreset.CUSTOM) {
            requireNotNull(modelPath) {
                "modelPath is required when using CUSTOM model preset"
            }
            require(File(modelPath).exists()) {
                "Custom model file not found: $modelPath"
            }
        }

        // Initialize frame size and buffer AFTER validation
        inputFrameSize = (inputSampleRate * 10 / 1000) * CHANNELS
        streamBuffer = ShortArray(inputFrameSize * 4)  // Initial capacity: 4 frames

        nativeHandle = createNative(
            modelPreset.value, modelPath, vadThreshold, enableVadOutput,
            inputSampleRate, resampleQuality
        )

        if (nativeHandle == 0L) {
            throw RuntimeException("Failed to create native denoiser")
        }

        val needsResampling = inputSampleRate != SAMPLE_RATE
        Log.i(
            TAG, "Denoiser initialized (inputRate=$inputSampleRate, preset=$modelPreset, " +
                    "vad=$vadThreshold, needsResampling=$needsResampling, quality=$resampleQuality)"
        )
    }

    /**
     * Builder for Denoiser
     *
     * Example usage:
     * ```
     * // For 48kHz audio (no resampling)
     * val denoiser = Denoiser.Builder()
     *     .vadThreshold(0.5f)
     *     .onProcessedAudio { denoisedAudio, result ->
     *         // Handle denoised audio (48kHz, 16-bit PCM mono)
     *     }
     *     .build()
     *
     * // For non-48kHz audio (with automatic resampling)
     * val denoiser = Denoiser.Builder()
     *     .inputSampleRate(16000)  // Input is 16kHz
     *     .resampleQuality(RESAMPLER_QUALITY_VOIP)
     *     .vadThreshold(0.5f)
     *     .onProcessedAudio { denoisedAudio, result ->
     *         // Handle denoised audio (16kHz, resampled back from 48kHz)
     *     }
     *     .build()
     *
     * // Feed audio in chunks
     * denoiser.processChunk(audioData)
     * ```
     */
    class Builder {
        private var modelPreset: ModelPreset = ModelPreset.EMBEDDED
        private var modelPath: String? = null
        private var vadThreshold: Float = 0.5f
        private var enableVadOutput: Boolean = true
        private var processedAudioCallback: ProcessedAudioCallback? = null
        private var inputSampleRate: Int = SAMPLE_RATE  // Default to 48kHz (no resampling)
        private var resampleQuality: Int = RESAMPLER_QUALITY_DEFAULT

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
         * Set input sample rate. If not 48kHz, audio will be automatically resampled
         * to 48kHz for denoising, then resampled back to the original rate.
         * @param value Input sample rate in Hz (default: 48000)
         */
        fun inputSampleRate(value: Int) = apply { this.inputSampleRate = value }

        /**
         * Set resampling quality (0-10). Only used if inputSampleRate != 48kHz.
         * 0 = fastest, 10 = best quality
         * @param value Quality level (default: RESAMPLER_QUALITY_DEFAULT = 4)
         */
        fun resampleQuality(value: Int) = apply { this.resampleQuality = value }

        /**
         * Set callback for streaming mode. When set, use processChunk() to feed audio.
         * The callback receives denoised audio in the same format as input.
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
                processedAudioCallback = processedAudioCallback,
                inputSampleRate = inputSampleRate,
                resampleQuality = resampleQuality
            )
        }
    }


    /**
     * Process audio chunk of any size for real-time streaming.
     * Internally buffers samples until complete frames are available.
     * Processed audio is delivered via the callback set in Builder.onProcessedAudio()
     *
     * IMPORTANT: Input audio must match the inputSampleRate specified in Builder,
     * 16-bit PCM mono format. If inputSampleRate != 48kHz, resampling is handled automatically.
     *
     * Performance: Uses primitive array buffer to avoid boxing overhead and GC pressure
     * during continuous real-time streaming. After initial warm-up, operates with
     * zero allocations per chunk (except for frame output arrays).
     *
     * @param input Audio samples at the specified inputSampleRate (any size, will be buffered internally)
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
            while (bufferSize >= inputFrameSize) {
                // Extract frame directly from buffer (single allocation)
                val frame = streamBuffer.copyOfRange(0, inputFrameSize)

                // Process the frame (resampling handled in native code)
                val output = ShortArray(inputFrameSize)
                val result = processNative(nativeHandle, frame, output)

                // Deliver via callback
                if (result != null) {
                    processedAudioCallback.invoke(output, result)
                } else {
                    Log.w(TAG, "Native processing returned null for chunk")
                }

                // Remove processed frame from buffer using fast native memcpy
                val remainingSize = bufferSize - inputFrameSize
                if (remainingSize > 0) {
                    System.arraycopy(streamBuffer, inputFrameSize, streamBuffer, 0, remainingSize)
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
            val paddingNeeded = inputFrameSize - remaining

            // Create frame with padding
            val frame = ShortArray(inputFrameSize)
            System.arraycopy(streamBuffer, 0, frame, 0, remaining)
            // Remaining elements are already zero-initialized in ShortArray

            // Process the final padded frame
            val output = ShortArray(inputFrameSize)
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
     * Get current denoiser statistics
     *
     * Returns comprehensive statistics including frame counts, speech detection rates,
     * VAD score statistics, and processing time metrics. Statistics accumulate over
     * the lifetime of this denoiser instance unless explicitly reset with resetStats().
     *
     * Thread-safe: Can be called from any thread.
     *
     * @return Current statistics snapshot, or null if stats retrieval fails
     * @throws IllegalStateException if denoiser has been destroyed
     */
    fun getStats(): DenoiserStats? {
        check(nativeHandle != 0L) { "Denoiser has been destroyed" }
        return getStatsNative(nativeHandle)
    }

    /**
     * Reset all statistics counters to zero
     *
     * Clears all accumulated statistics including frame counts, speech detection rates,
     * VAD scores, and processing times. Use this to measure statistics for specific
     * recording sessions or time periods.
     *
     * Example usage:
     * ```
     * denoiser.resetStats()  // Start fresh
     * // ... process audio ...
     * val stats = denoiser.getStats()  // Get stats for this session
     * ```
     *
     * Thread-safe: Can be called from any thread.
     *
     * @throws IllegalStateException if denoiser has been destroyed
     */
    fun resetStats() {
        check(nativeHandle != 0L) { "Denoiser has been destroyed" }
        resetStatsNative(nativeHandle)
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
                streamBuffer = ShortArray(inputFrameSize * 4)
                bufferSize = 0
            }
            destroyNative(nativeHandle)
            Log.i(TAG, "Denoiser destroyed (handle=$nativeHandle)")
            nativeHandle = 0
        }
    }

    // Native bindings
    private external fun createNative(
        modelPreset: Int, modelPath: String?, vadThreshold: Float, enableVadOutput: Boolean,
        inputSampleRate: Int, resampleQuality: Int
    ): Long

    private external fun destroyNative(handle: Long)
    private external fun processNative(
        handle: Long, input: ShortArray, output: ShortArray
    ): DenoiserResult?

    private external fun getStatsNative(handle: Long): DenoiserStats?
    private external fun resetStatsNative(handle: Long)
}
