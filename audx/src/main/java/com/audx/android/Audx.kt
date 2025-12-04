package com.audx.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Configuration for Audx audio processing.
 *
 * @property inputRate Sample rate of input/output audio in Hz (e.g., 16000, 48000). Must be positive.
 *                     Audio will be automatically resampled to 48kHz for internal processing if needed.
 * @property resampleQuality Resampler quality level (0-10). Higher values provide better quality but slower processing.
 *                           Use predefined constants like [Audx.AUDX_RESAMPLER_QUALITY_VOIP] for common use cases.
 * @throws IllegalArgumentException if inputRate is not positive or resampleQuality is outside valid range
 * @see Audx
 */
data class AudxConfig(
    var inputRate: Int = Audx.FRAME_RATE,
    var resampleQuality: Int = Audx.AUDX_RESAMPLER_QUALITY_DEFAULT,
) {
    init {
        require(
            resampleQuality in Audx.AUDX_RESAMPLER_QUALITY_MIN..Audx.AUDX_RESAMPLER_QUALITY_MAX,
        ) {
            "resampleQuality must be between " +
                "${Audx.AUDX_RESAMPLER_QUALITY_MIN} and ${Audx.AUDX_RESAMPLER_QUALITY_MAX}, got: $resampleQuality"
        }
        require(inputRate > 0) {
            "inputRate must be positive, got: $inputRate"
        }
    }
}

/**
 * Real-time audio denoising and Voice Activity Detection (VAD) processor.
 *
 * Audx provides high-quality noise reduction for continuous audio streams, optimized for
 * real-time microphone processing. It operates on PCM16 audio (ShortArray) with automatic
 * resampling to/from 48kHz internally.
 *
 * ## Typical Usage - Real-time Microphone Processing
 * ```kotlin
 * class AudioViewModel : ViewModel() {
 *     private val audx = Audx.Builder()
 *         .inputRate(16000)  // Microphone sample rate
 *         .resampleQuality(Audx.AUDX_RESAMPLER_QUALITY_VOIP)
 *         .build()
 *
 *     // Process audio chunks
 *     fun processChunk(audioRecordData: ShortArray) {
 *         val outputBuffer = ShortArray(audioRecordData.size)  // Same size as input
 *         audx.process(audioRecordData, outputBuffer) { vadProbability ->
 *             if (vadProbability > 0.5f) {
 *                 // Voice detected
 *             }
 *         }
 *         // Use outputBuffer for playback
 *     }
 *
 *     override fun onCleared() {
 *         audx.close()  // Cleanup when ViewModel destroyed
 *     }
 * }
 * ```
 *
 * ## Thread Safety
 * - NOT thread-safe for concurrent process() calls
 * - Callbacks execute on the calling thread
 * - close() is thread-safe and idempotent
 *
 * ## Resource Management
 * Create once, use many times, close when done:
 * - Create in ViewModel/Component initialization
 * - Process continuous audio chunks
 * - Call close() in onCleared()/cleanup
 *
 * ## Audio Format
 * - Internal Processing: 48kHz (FRAME_RATE), 480 samples (FRAME_SIZE) = 10ms
 * - Input/Output: Matches configured inputRate (automatically resampled)
 * - Sample Format: PCM16 (ShortArray)
 *
 * @property config Configuration for audio processing
 * @see AudxConfig
 */
class Audx(
    private val config: AudxConfig,
) {
    init {
        System.loadLibrary("audx-android")
    }

    private var denoisePtr: Long? = null
    private val closed = AtomicBoolean(false)
    private val callbackLock = Any()
    private var frameCount = 0L
    private val SKIP_FIRST_N_FRAMES = 1

    companion object {
        /** Native processing sample rate (48kHz) used internally by RNNoise. */
        const val FRAME_RATE: Int = 48_000

        /** Native frame size (480 samples) = 10ms at 48kHz. */
        const val FRAME_SIZE: Int = 480

        /** Maximum resampler quality (10) - highest quality, slowest processing. */
        const val AUDX_RESAMPLER_QUALITY_MAX: Int = 10

        /** Minimum resampler quality (0) - lowest quality, fastest processing. */
        const val AUDX_RESAMPLER_QUALITY_MIN: Int = 0

        /** Default resampler quality (4) - balanced quality and performance for general use. */
        const val AUDX_RESAMPLER_QUALITY_DEFAULT: Int = 4

        /** VoIP-optimized resampler quality (3) - tuned for real-time voice communication. */
        const val AUDX_RESAMPLER_QUALITY_VOIP: Int = 3
    }

    /**
     * Builder for creating [Audx] instances with custom configuration.
     *
     * Example usage:
     * ```kotlin
     * val audx = Audx.Builder()
     *     .inputRate(16000)
     *     .resampleQuality(Audx.AUDX_RESAMPLER_QUALITY_VOIP)
     *     .build()
     * ```
     *
     * Default values: inputRate = 48000, resampleQuality = 4
     */
    class Builder {
        private var inputRate = FRAME_RATE
        private var resampleQuality = AUDX_RESAMPLER_QUALITY_DEFAULT

        /**
         * Sets the input/output sample rate in Hz.
         *
         * @param rate Sample rate in Hz (e.g., 8000, 16000, 48000). Must be positive.
         * @return This Builder instance for method chaining
         */
        fun inputRate(rate: Int): Builder {
            inputRate = rate
            return this
        }

        /**
         * Sets the resampler quality level.
         *
         * @param quality Quality level (0-10). Use constants like [AUDX_RESAMPLER_QUALITY_VOIP] or [AUDX_RESAMPLER_QUALITY_DEFAULT].
         *                Higher values provide better quality but slower processing.
         * @return This Builder instance for method chaining
         */
        fun resampleQuality(quality: Int): Builder {
            resampleQuality = quality
            return this
        }

        /**
         * Builds and initializes an [Audx] instance with the configured parameters.
         *
         * @return A new [Audx] instance ready for audio processing
         * @throws AudxInitializationException if native initialization fails
         * @throws IllegalArgumentException if configuration parameters are invalid
         */
        fun build(): Audx {
            val audx =
                Audx(
                    AudxConfig(
                        inputRate = inputRate,
                        resampleQuality = resampleQuality,
                    ),
                )

            audx.create()
            return audx
        }
    }

    /**
     * Initializes native resources for audio processing.
     *
     * This method is called automatically by [Builder.build]. You typically don't need to call this directly
     * unless you're managing the lifecycle manually.
     *
     * @throws AudxInitializationException if native initialization fails (e.g., invalid config, missing native library)
     */
    fun create() {
        val ptr = denoiseCreateJNI(config.inputRate, config.resampleQuality)
        if (ptr == -1L) {
            throw AudxInitializationException(
                "Failed to initialize Audx with " +
                    "inputRate=${config.inputRate}, resampleQuality=${config.resampleQuality}",
            )
        }
        denoisePtr = ptr
        frameCount = 0  // Reset frame counter on new instance
    }

    private external fun denoiseCreateJNI(
        inRate: Int,
        resampleQuality: Int,
    ): Long

    /**
     * Processes audio samples through the denoising pipeline (explicit buffer version).
     *
     * This method provides maximum control and performance by letting users manage their
     * own input and output buffers. Samples are processed at the configured inputRate
     * (e.g., 16kHz), with automatic resampling to/from 48kHz internally for denoising.
     *
     * @param input ShortArray containing PCM16 audio samples at the configured inputRate
     * @param output ShortArray to receive denoised PCM16 audio at the same rate as input. Must be at least as large as input
     * @param vadProbabilityCallback Callback invoked with Voice Activity Detection probability (0.0-1.0, higher = more likely speech)
     * @throws IllegalStateException if this Audx instance has been closed
     * @throws IllegalArgumentException if output buffer is smaller than input buffer
     * @throws AudxProcessingException if native processing fails
     */
    fun process(
        input: ShortArray,
        output: ShortArray,
        vadProbabilityCallback: (Float) -> Unit,
    ) {
        checkNotClosed("process")

        val ptr = denoisePtr ?: error("Native pointer is null")
        val result = denoiseProcessJNI(ptr, input, output)

        frameCount++
        if (frameCount <= SKIP_FIRST_N_FRAMES) {
            // Skip first frame output - fill with silence to prevent warm-up noise
            output.fill(0)
        }

        vadProbabilityCallback(result)
    }

    /**
     * Asynchronously processes audio samples (explicit buffer version).
     *
     * Executes on Dispatchers.Default (CPU-intensive work). VAD callbacks are invoked
     * on the coroutine's context. Processing uses the configured inputRate with automatic
     * resampling internally.
     *
     * @param input ShortArray containing PCM16 audio samples at the configured inputRate
     * @param output ShortArray to receive denoised audio at the same rate as input
     * @param vadProbabilityCallback Callback invoked with Voice Activity Detection probability (0.0-1.0)
     * @throws IllegalStateException if this Audx instance has been closed
     * @throws IllegalArgumentException if output buffer is smaller than input buffer
     * @throws AudxProcessingException if native processing fails
     */
    suspend fun processAsync(
        input: ShortArray,
        output: ShortArray,
        vadProbabilityCallback: (Float) -> Unit,
    ) = withContext(Dispatchers.Default) {
        process(input, output, vadProbabilityCallback)
    }

    /**
     * Releases native resources associated with this Audx instance.
     *
     * After calling close(), this instance cannot be used for further processing.
     * All subsequent method calls will throw IllegalStateException.
     * This method is idempotent - calling it multiple times is safe.
     *
     * **Important:** Call this method when you're completely done with the Audx instance,
     * typically in ViewModel.onCleared() or similar lifecycle cleanup methods.
     */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                synchronized(callbackLock) {
                    denoisePtr?.let { ptr ->
                        denoiseDestroyJNI(ptr)
                    }
                }
            } finally {
                denoisePtr = null
            }
        }
    }

    /**
     * Returns true if this Audx instance has been closed and cannot be used.
     */
    fun isClosed(): Boolean = closed.get()

    private fun checkNotClosed(methodName: String) {
        check(!closed.get()) {
            "Cannot call $methodName() on closed Audx instance"
        }
    }

    private external fun denoiseProcessJNI(
        ptr: Long,
        input: ShortArray,
        output: ShortArray,
    ): Float

    private external fun denoiseDestroyJNI(ptr: Long)
}
