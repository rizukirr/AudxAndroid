package com.android.audx

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * - Frame size: 480 samples per channel (10ms at 48kHz)
 * - Stereo audio must be interleaved: [L, R, L, R, ...]
 *
 * The denoiser processes audio in fixed frames of 480 samples per channel.
 * For mono: 480 samples per frame
 * For stereo: 960 samples per frame (480L + 480R interleaved)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Denoiser private constructor(
    modelPreset: ModelPreset,
    vadThreshold: Float,
    enableVadOutput: Boolean,
    private val numChannels: Int,
    private val modelPath: String?,
    private val processedAudioCallback: ProcessedAudioCallback?
) : AutoCloseable {

    companion object {
        private const val TAG = "Denoiser"

        /**
         * Frame size: 480 samples per channel at 48kHz (10ms)
         * Total samples per frame = FRAME_SIZE * numChannels
         */
        const val FRAME_SIZE = 480

        /**
         * Required sample rate for audio processing
         */
        const val SAMPLE_RATE = 48000

        init {
            try {
                System.loadLibrary("audx")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
            }
        }

        /**
         * Calculate the required buffer size for AudioRecord at 48kHz
         *
         * @param numChannels Number of channels (1=mono, 2=stereo)
         * @param durationMs Duration of buffer in milliseconds (default: 10ms = one frame)
         * @return Buffer size in bytes
         */
        @JvmStatic
        fun getRecommendedBufferSize(numChannels: Int = 1, durationMs: Int = 10): Int {
            // 48000 samples/sec * (durationMs / 1000) * numChannels * 2 bytes/sample
            return (SAMPLE_RATE * durationMs / 1000) * numChannels * 2
        }

        /**
         * Get the frame size in samples for the given channel configuration
         *
         * @param numChannels Number of channels (1=mono, 2=stereo)
         * @return Total samples per frame (480 for mono, 960 for stereo)
         */
        @JvmStatic
        fun getFrameSizeInSamples(numChannels: Int): Int {
            return FRAME_SIZE * numChannels
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
    private val streamBuffer = mutableListOf<Short>()
    private val bufferLock = ReentrantLock()
    private val audioDispatcher = Dispatchers.Default.limitedParallelism(1)

    enum class ModelPreset(val value: Int) {
        EMBEDDED(0),
        CUSTOM(1)
    }

    init {
        require(numChannels == 1 || numChannels == 2) {
            "numChannels must be 1 or 2"
        }
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
            numChannels,
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
            "Denoiser initialized (channels=$numChannels, preset=$modelPreset, vad=$vadThreshold)"
        )
    }

    /**
     * Builder for Denoiser
     *
     * Example usage:
     * ```
     * val denoiser = Denoiser.Builder()
     *     .numChannels(1)  // Mono audio
     *     .vadThreshold(0.5f)
     *     .onProcessedAudio { denoisedAudio, result ->
     *         // Handle denoised audio (48kHz, 16-bit PCM)
     *     }
     *     .build()
     *
     * // Feed audio in chunks (must be 48kHz PCM)
     * denoiser.processChunk(audioData)
     * ```
     */
    class Builder {
        private var numChannels: Int = 1
        private var modelPreset: ModelPreset = ModelPreset.EMBEDDED
        private var modelPath: String? = null
        private var vadThreshold: Float = 0.5f
        private var enableVadOutput: Boolean = true
        private var processedAudioCallback: ProcessedAudioCallback? = null

        /**
         * Set number of audio channels (1=mono, 2=stereo)
         * @param value Number of channels (1 or 2)
         */
        fun numChannels(value: Int) = apply { this.numChannels = value }

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

        fun build(): Denoiser {
            return Denoiser(
                numChannels = numChannels,
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
     * IMPORTANT: Input audio must be 48kHz, 16-bit PCM format.
     * For stereo, samples must be interleaved [L, R, L, R, ...]
     *
     * @param input Audio samples at 48kHz (any size, will be buffered internally)
     * @throws IllegalStateException if no callback was set in builder
     */
    suspend fun processChunk(input: ShortArray) = withContext(audioDispatcher) {
        check(nativeHandle != 0L) { "Denoiser has been destroyed" }
        requireNotNull(processedAudioCallback) {
            "processChunk requires a callback. Use Builder.onProcessedAudio() to set one."
        }

        bufferLock.withLock {
            // Add new samples to buffer
            streamBuffer.addAll(input.toList())

            val frameSize = FRAME_SIZE * numChannels

            // Process all complete frames in the buffer
            while (streamBuffer.size >= frameSize) {
                // Extract one frame
                val frame = streamBuffer.take(frameSize).toShortArray()
                streamBuffer.subList(0, frameSize).clear()

                // Process the frame
                val output = ShortArray(frameSize)
                val result = processNative(nativeHandle, frame, output)

                // Deliver via callback
                if (result != null) {
                    processedAudioCallback.invoke(output, result)
                } else {
                    Log.w(TAG, "Native processing returned null for chunk")
                }
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
            if (streamBuffer.isEmpty()) return@withContext

            val frameSize = FRAME_SIZE * numChannels

            // Pad remaining samples to complete frame with zeros
            val remaining = streamBuffer.size
            val paddingNeeded = frameSize - remaining

            if (paddingNeeded > 0) {
                repeat(paddingNeeded) { streamBuffer.add(0) }
            }

            // Process the final padded frame
            val frame = streamBuffer.toShortArray()
            streamBuffer.clear()

            val output = ShortArray(frameSize)
            val result = processNative(nativeHandle, frame, output)

            // Deliver only the non-padded portion via callback
            if (result != null) {
                val actualOutput = output.take(remaining).toShortArray()
                processedAudioCallback.invoke(actualOutput, result)
            }

            Log.d(TAG, "Flushed $remaining remaining samples")
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
                streamBuffer.clear()
            }
            destroyNative(nativeHandle)
            Log.i(TAG, "Denoiser destroyed (handle=$nativeHandle)")
            nativeHandle = 0
        }
    }

    // Native bindings
    private external fun createNative(
        numChannels: Int,
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
