package com.android.audx

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.lang.ref.Cleaner
import java.util.concurrent.CountDownLatch

/**
 * Holds the result of processing a stream chunk through the denoiser.
 *
 * This object is returned from the denoiser for each processed chunk of audio.
 *
 * @property audio The processed audio data for the chunk, as a [ShortArray]. The size of this array
 *           may be different from the input chunk size due to resampling and internal buffering.
 * @property vadProbability Voice Activity Detection (VAD) probability of the *last* 10ms frame
 *           processed within this chunk. The value ranges from 0.0 (no speech) to 1.0 (speech).
 * @property isSpeech A boolean flag indicating if speech was detected in the last processed frame,
 *           based on whether [vadProbability] exceeded the `vadThreshold` set in the builder.
 */
data class DenoiseStreamResult(
    val audio: ShortArray,
    val vadProbability: Float,
    val isSpeech: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DenoiseStreamResult
        if (!audio.contentEquals(other.audio)) return false
        if (vadProbability != other.vadProbability) return false
        if (isSpeech != other.isSpeech) return false
        return true
    }

    override fun hashCode(): Int {
        var result = audio.contentHashCode()
        result = 31 * result + vadProbability.hashCode()
        result = 31 * result + isSpeech.hashCode()
        return result
    }
}

/**
 * Holds runtime statistics and performance metrics of the denoiser
 *
 * This structure is filled by getStats() and provides
 * Information about the number of frames processed, speech activity
 * detection (VAD) results and processing performance
 *
 * @property frameProcessed The total number of frames processed by the denoiser
 * @property speechDetectedPercent The percentage of frames that detected speech
 * @property vadScoreAvg The average VAD score across all frames
 * @property vadScoreMin The minimum VAD score across all frames
 * @property vadScoreMax The maximum VAD score across all frames
 * @property processTimeTotal The total processing time in milliseconds
 * @property processTimeAvg The average processing time in milliseconds
 * @property processTimeLast The processing time of the last frame in milliseconds
 */
data class DenoiserStatsResult(
    val frameProcessed: Int,
    val speechDetectedPercent: Float,
    val vadScoreAvg: Float,
    val vadScoreMin: Float,
    val vadScoreMax: Float,
    val processTimeTotal: Float,
    val processTimeAvg: Float,
    val processTimeLast: Float
)

/**
 * Defines the callback function for receiving processed audio results.
 *
 * @param result The [DenoiseStreamResult] containing the denoised audio and VAD statistics.
 */
typealias ProcessedAudioCallback = (result: DenoiseStreamResult) -> Unit

/**
 * Defines the callback function for receiving denoiser statistics.
 *
 * @param result The [DenoiserStatsResult] containing the denoiser statistics.
 */
typealias GetStatsCallback = (result: DenoiserStatsResult) -> Unit

/**
 * A real-time, streaming audio denoiser for Android.
 *
 * This class provides a high-level API for denoising mono, 16-bit PCM audio streams.
 * It is designed to handle arbitrary input sample rates and audio chunk sizes by managing
 * an internal streaming pipeline that correctly buffers and resamples audio for the
 * underlying denoiser engine.
 *
 * The denoiser operates on a dedicated background thread to prevent blocking the caller.
 * All processing is asynchronous.
 *
 * **Lifecycle:**
 * 1. Create an instance using the [AudxDenoiser.Builder].
 * 2. Call [processAudio] repeatedly with incoming audio chunks.
 * 3. Denoised audio is delivered asynchronously to the [ProcessedAudioCallback].
 * 4. At the end of the stream, call [flush] to process any remaining buffered audio.
 * 5. Call [close] to release all native and thread resources.
 *
 * @see AudxDenoiser.Builder
 */
@Suppress("MemberVisibilityCanBePrivate")
class AudxDenoiser private constructor(
    private val modelPath: String?,
    private val vadThreshold: Float,
    private val collectStatistics: Boolean,
    private val inputSampleRate: Int,
    private val resampleQuality: Int,
    private val workerThreadName: String,
) : AutoCloseable {

    companion object {
        private const val TAG = "AudxDenoiser"

        init {
            try {
                System.loadLibrary("audx")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native audx library", e)
            }
        }

        /** The default sample rate expected by the underlying denoiser model (48000 Hz). */
        val SAMPLE_RATE: Int get() = getSampleRateNative()

        /** The number of audio channels supported (1 for mono). */
        val CHANNELS: Int get() = getChannelsNative()

        /** The audio bit depth supported (16-bit). */
        val BIT_DEPTH: Int get() = getBitDepthNative()

        /** The internal frame size of the denoiser model in samples (480 samples for 10ms at 48kHz). */
        val FRAME_SIZE: Int get() = getFrameSizeNative()

        /** Maximum allowed resampler quality level (10). Higher quality increases CPU usage. */
        val RESAMPLER_QUALITY_MAX: Int get() = getResamplerQualityMaxNative()

        /** Minimum allowed resampler quality level (0). Fastest but lowest audio accuracy. */
        val RESAMPLER_QUALITY_MIN: Int get() = getResamplerQualityMinNative()

        /** Default resampler quality level (4). Balanced between performance and quality. */
        val RESAMPLER_QUALITY_DEFAULT: Int get() = getResamplerQualityDefaultNative()

        /** Recommended resampler quality for VoIP or low-latency scenarios (3). */
        val RESAMPLER_QUALITY_VOIP: Int get() = getResamplerQualityVoipNative()

        @JvmStatic
        private external fun getSampleRateNative(): Int

        @JvmStatic
        private external fun getChannelsNative(): Int

        @JvmStatic
        private external fun getBitDepthNative(): Int

        @JvmStatic
        private external fun getFrameSizeNative(): Int

        @JvmStatic
        private external fun getResamplerQualityMaxNative(): Int

        @JvmStatic
        private external fun getResamplerQualityMinNative(): Int

        @JvmStatic
        private external fun getResamplerQualityDefaultNative(): Int

        @JvmStatic
        private external fun getResamplerQualityVoipNative(): Int
    }

    @Volatile
    private var nativeHandle: Long = 0L

    private val cleaner: Cleaner.Cleanable

    private val cleanerCreate = Cleaner.create()

    private val workerThread: HandlerThread
    private val workerHandler: Handler

    @Volatile
    private var processedAudioCallback: ProcessedAudioCallback? = null

    @Volatile
    private var statsCallback: GetStatsCallback? = null

    @Volatile
    private var closed = false

    /**
     * Builder for creating and configuring an [AudxDenoiser] instance.
     */
    class Builder {
        private var modelPath: String? = null
        private var vadThreshold: Float = 0.5f
        private var collectStatistics: Boolean = false
        private var inputSampleRate: Int = 48000
        private var resampleQuality: Int = 4
        private var workerThreadName: String = "audx-worker"
        private var callback: ProcessedAudioCallback? = null
        private var statsCallback: GetStatsCallback? = null


        /** Sets the path to a custom model. more info: `https://github.com/rizukirr/audx-realtime` */
        fun modelPath(path: String?) = apply { this.modelPath = path }

        /** Sets the Voice Activity Detection (VAD) threshold (0.0 to 1.0). Default is 0.5. */
        fun vadThreshold(value: Float) = apply { this.vadThreshold = value }

        /** Enables or disables the collection of VAD statistics. Default is true. */
        fun collectStatistics(value: Boolean) = apply { this.collectStatistics = value }

        /** Sets the sample rate of the input audio. The denoiser will automatically resample if this is not 48000. */
        fun inputSampleRate(value: Int) = apply { this.inputSampleRate = value }

        /** Sets the quality of the internal resampler (0-10). Higher is better quality but more CPU intensive. Default is 4. */
        fun resampleQuality(value: Int) = apply { this.resampleQuality = value }

        /** Sets the name of the internal worker thread. */
        fun workerThreadName(name: String) = apply { this.workerThreadName = name }

        /** Sets the callback to be invoked when a chunk of audio has been processed. This is required. */
        fun onProcessedAudio(cb: ProcessedAudioCallback) = apply { this.callback = cb }

        /** Sets the callback to be invoked when statistics are collected. */
        fun onCollectStats(cb: GetStatsCallback) = apply { this.statsCallback = cb }

        /**
         * Creates and initializes the [AudxDenoiser] instance with the configured settings.
         * @throws IllegalArgumentException if the onProcessedAudio callback is not set.
         * @throws IllegalArgumentException if collectStatistics is enabled but onCollectStats callback is not set.
         * @throws IllegalStateException if the native library fails to initialize.
         */
        fun build(): AudxDenoiser {
            require(callback != null) { "onProcessedAudio callback must be set." }
            require(!collectStatistics || statsCallback != null) {
                "onCollectStats callback must be set when collectStatistics is enabled."
            }
            val denoiser = AudxDenoiser(
                modelPath = modelPath,
                vadThreshold = vadThreshold,
                collectStatistics = collectStatistics,
                inputSampleRate = inputSampleRate,
                resampleQuality = resampleQuality,
                workerThreadName = workerThreadName
            )
            denoiser.setCallback(callback!!)
            if (statsCallback != null) {
                denoiser.setStatsCallback(statsCallback!!)
            }
            return denoiser
        }
    }

    init {
        require(vadThreshold in 0.0f..1.0f)
        require(inputSampleRate in 8000..192000)

        workerThread = HandlerThread(workerThreadName, Process.THREAD_PRIORITY_URGENT_AUDIO)
        workerThread.start()
        workerHandler = Handler(workerThread.looper)

        val latch = CountDownLatch(1)
        workerHandler.post {
            try {
                nativeHandle = createNative(
                    modelPath, vadThreshold, collectStatistics,
                    inputSampleRate, resampleQuality
                )
            } finally {
                latch.countDown()
            }
        }
        latch.await()

        if (nativeHandle == 0L) {
            throw IllegalStateException("Failed to create native AudxDenoiser instance.")
        }

        cleaner = cleanerCreate.register(this) {
            if (nativeHandle != 0L) {
                workerHandler.post {
                    destroyNative(nativeHandle)
                    nativeHandle = 0L
                }
            }
        }
    }

    /**
     * Sets or updates the callback for receiving processed audio.
     * This method is thread-safe.
     */
    fun setCallback(cb: ProcessedAudioCallback) {
        processedAudioCallback = cb
    }

    /**
     * Sets or updates the callback for receiving denoiser statistics.
     * This method is thread-safe.
     */
    fun setStatsCallback(cb: GetStatsCallback) {
        statsCallback = cb
    }

    /**
     * Submits a chunk of audio for asynchronous processing.
     * This method is thread-safe and non-blocking. The audio chunk can be of any size.
     *
     * @param input A [ShortArray] containing the raw 16-bit PCM audio data.
     * @throws IllegalStateException if the denoiser has been closed.
     */
    /**
     * Submits a chunk of audio for asynchronous processing.
     * This method is thread-safe and non-blocking. The audio chunk can be of any size.
     *
     * @param input A [ShortArray] containing the raw 16-bit PCM audio data.
     * @throws IllegalStateException if the denoiser has been closed.
     */
    fun processAudio(input: ShortArray) {
        if (closed) throw IllegalStateException("Denoiser is closed and cannot be used.")
        if (input.isEmpty()) return

        workerHandler.post {
            if (closed) return@post
            try {
                val result = processNative(nativeHandle, input)
                result?.let {
                    if (it.audio.isNotEmpty()) {
                        processedAudioCallback?.invoke(it)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during native processing", e)
            }
        }
    }

    /**
     * Flushes any remaining audio from the internal native buffers.
     *
     * This method should be called at the end of an audio stream to ensure that all
     * submitted samples are processed and delivered to the callback. This is a synchronous,
     * blocking call that waits for the flush operation to complete on the worker thread.
     */
    fun flush() {
        if (closed) return
        val latch = CountDownLatch(1)
        workerHandler.post {
            if (closed) {
                latch.countDown()
                return@post
            }
            try {
                val result = flushNative(nativeHandle)
                result?.let {
                    if (it.audio.isNotEmpty()) {
                        processedAudioCallback?.invoke(it)
                    }
                }

                if(collectStatistics){
                    val stats = getStatsNative(nativeHandle)
                    stats?.let { statsCallback?.invoke(it) }
                    cleanStatsNative(nativeHandle)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during native flush", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    /**
     * Flushes remaining audio and releases all native and thread resources.
     *
     * This method is synchronous and will block until all pending operations are complete.
     * After calling `close()`, the denoiser instance can no longer be used.
     */
    override fun close() {
        if (closed) return
        closed = true

        val latch = CountDownLatch(1)
        workerHandler.post {
            try {
                // Inlined flush to prevent deadlock and ensure final data is processed.
                val result = flushNative(nativeHandle)
                result?.let {
                    if (it.audio.isNotEmpty()) {
                        processedAudioCallback?.invoke(it)
                    }
                }

                if(collectStatistics){
                    val stats = getStatsNative(nativeHandle)
                    stats?.let { statsCallback?.invoke(it) }
                    cleanStatsNative(nativeHandle)
                }

                if (nativeHandle != 0L) {
                    destroyNative(nativeHandle)
                    nativeHandle = 0L
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during native final flush/destroy in close()", e)
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await()
            workerThread.quitSafely()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "Interrupted while closing denoiser", e)
        }
        cleaner.clean()
    }

    /**
     * Resets all collected runtime statistics for the denoiser instance.
     *
     * This function sets all statistics fields (e.g., frames_processed, total_vad_score,
     * min/max scores, processing times) back to their initial, zeroed-out state.
     * This is a synchronous, blocking call that waits for the reset operation to complete
     * on the worker thread.
     *
     * @throws IllegalStateException if the denoiser has been closed.
     */
    fun cleanStats() {
        if (closed) throw IllegalStateException("Denoiser is closed and cannot be used.")
        val latch = CountDownLatch(1)

        workerHandler.post {
            if (closed) {
                latch.countDown()
                return@post
            }
            try {
                cleanStatsNative(nativeHandle)
            } catch (e: Exception) {
                Log.e(TAG, "Error during native cleanStats", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    // --- JNI Bindings ---

    private external fun createNative(
        modelPath: String?, vadThreshold: Float, collectStatistics: Boolean,
        inputSampleRate: Int, resampleQuality: Int
    ): Long

    private external fun processNative(handle: Long, input: ShortArray): DenoiseStreamResult?

    private external fun flushNative(handle: Long): DenoiseStreamResult?

    private external fun destroyNative(handle: Long)

    private external fun getStatsNative(handle: Long): DenoiserStatsResult?

    private external fun cleanStatsNative(handle: Long)
}
