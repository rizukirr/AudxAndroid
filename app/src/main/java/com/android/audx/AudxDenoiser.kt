package com.android.audx

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.lang.ref.Cleaner
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

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

@Suppress("MemberVisibilityCanBePrivate")
class AudxDenoiser private constructor(
    private val modelPreset: ModelPreset,
    private val modelPath: String?,
    private val vadThreshold: Float,
    private val collectStatistics: Boolean,
    private val inputSampleRate: Int,
    private val resampleQuality: Int,
    private val poolFrameCount: Int,
    private val workerThreadName: String,
    private val zeroCopyDelivery: Boolean,
    private val skipInitialFrames: Int
) : AutoCloseable {

    companion object {
        private const val TAG = "AudxDenoiser"

        init {
            try {
                System.loadLibrary("audx")
                Log.i(TAG, "Native audx library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native audx library", e)
            }
        }

        // Native forwarded constants
        val SAMPLE_RATE: Int get() = getSampleRateNative()
        val CHANNELS: Int get() = getChannelsNative()
        val BIT_DEPTH: Int get() = getBitDepthNative()
        val FRAME_SIZE: Int get() = getFrameSizeNative()

        @JvmStatic
        private external fun getSampleRateNative(): Int

        @JvmStatic
        private external fun getChannelsNative(): Int

        @JvmStatic
        private external fun getBitDepthNative(): Int

        @JvmStatic
        private external fun getFrameSizeNative(): Int
    }

    enum class ModelPreset(val value: Int) { EMBEDDED(0), CUSTOM(1) }

    // native handle
    @Volatile
    private var nativeHandle: Long = 0L

    // lifecycle cleaner
    private val cleaner: Cleaner.Cleanable
    private val CLEANER = Cleaner.create()

    // worker
    private val workerThread: HandlerThread
    private val workerHandler: Handler

    // frame sizes
    private val frameSize: Int

    // aggregator for partial frames (worker-thread only)
    private val aggregator: ShortArray
    private var aggFill = 0

    // buffer pools
    private val inputPool = ConcurrentLinkedQueue<ShortArray>()
    private val outputPool = ConcurrentLinkedQueue<ShortArray>()

    // user callback
    @Volatile
    private var processedAudioCallback: ((ShortArray, DenoiserResult) -> Unit)? = null

    // flags
    @Volatile
    private var closed = false

    // Builder
    class Builder {
        private var modelPreset: ModelPreset = ModelPreset.EMBEDDED
        private var modelPath: String? = null
        private var vadThreshold: Float = 0.5f
        private var collectStatistics: Boolean = false
        private var inputSampleRate: Int = SAMPLE_RATE
        private var resampleQuality: Int = 4
        private var poolFrameCount: Int = 8
        private var workerThreadName: String = "audx-worker"
        private var zeroCopyDelivery: Boolean = false
        private var skipInitialFrames: Int = 3
        private var callback: ((ShortArray, DenoiserResult) -> Unit)? = null

        fun modelPreset(value: ModelPreset) = apply { this.modelPreset = value }
        fun modelPath(path: String?) = apply { this.modelPath = path }
        fun vadThreshold(value: Float) = apply { this.vadThreshold = value }
        fun collectStatistics(value: Boolean) = apply { this.collectStatistics = value }
        fun inputSampleRate(value: Int) = apply { this.inputSampleRate = value }
        fun resampleQuality(value: Int) = apply { this.resampleQuality = value }
        fun poolFrameCount(value: Int) = apply { this.poolFrameCount = max(1, value) }
        fun workerThreadName(name: String) = apply { this.workerThreadName = name }
        fun zeroCopyDelivery(enabled: Boolean) = apply { this.zeroCopyDelivery = enabled }
        fun skipInitialFrames(count: Int) = apply { this.skipInitialFrames = max(0, count) }
        fun onProcessedAudio(cb: (ShortArray, DenoiserResult) -> Unit) =
            apply { this.callback = cb }

        fun build(): AudxDenoiser {
            val denoiser = AudxDenoiser(
                modelPreset = modelPreset,
                modelPath = modelPath,
                vadThreshold = vadThreshold,
                collectStatistics = collectStatistics,
                inputSampleRate = inputSampleRate,
                resampleQuality = resampleQuality,
                poolFrameCount = poolFrameCount,
                workerThreadName = workerThreadName,
                zeroCopyDelivery = zeroCopyDelivery,
                skipInitialFrames = skipInitialFrames
            )
            if (callback != null) denoiser.setCallback(callback!!)
            return denoiser
        }
    }

    init {
        require(vadThreshold in 0.0f..1.0f)
        require(inputSampleRate in 8000..192000)

        frameSize = (inputSampleRate * 10) / 1000;


        // create HandlerThread with audio priority
        workerThread = HandlerThread(workerThreadName, Process.THREAD_PRIORITY_URGENT_AUDIO)
        workerThread.start()
        workerHandler = Handler(workerThread.looper)

        // aggregator
        aggregator = ShortArray(frameSize)
        aggFill = 0

        // fill pools with fixed-size frames
        repeat(poolFrameCount) {
            inputPool.add(ShortArray(frameSize))
            outputPool.add(ShortArray(frameSize))
        }

        // create native handle on worker thread synchronously and warm up using processNative()
        val latch = java.util.concurrent.CountDownLatch(1)
        workerHandler.post {
            try {
                nativeHandle = createNative(
                    modelPreset.value, modelPath, vadThreshold, collectStatistics,
                    inputSampleRate, resampleQuality
                )

                // warm-up native (on worker thread) using processNative
                repeat(skipInitialFrames) {
                    try {
                        val z = ShortArray(frameSize)
                        val t = ShortArray(frameSize)
                        // call processNative and ignore the result (warm-up)
                        try {
                            processNative(nativeHandle, z, t)
                        } catch (_: Throwable) { /* ignore warmup errors */
                        }
                    } catch (_: Throwable) { /* ignore */
                    }
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await()

        // register cleaner as backup
        cleaner = CLEANER.register(this) {
            try {
                closeInternal()
            } catch (_: Throwable) {
            }
        }
    }

    // allow user to set or change callback
    fun setCallback(cb: (ShortArray, DenoiserResult) -> Unit) {
        processedAudioCallback = cb
    }

    /**
     * Non-blocking submit. Accepts any chunk size. Will be processed on worker thread.
     */
    fun processAudio(input: ShortArray) {
        if (closed) return
        // copy into pooled buffer to avoid aliasing and GC pressure
        val buf = inputPool.poll() ?: ShortArray(frameSize.coerceAtLeast(input.size))
        val len = input.size.coerceAtMost(buf.size)
        System.arraycopy(input, 0, buf, 0, len)
        submitToWorker(buf, len)
    }

    private fun submitToWorker(buffer: ShortArray, validSamples: Int) {
        if (closed) return
        workerHandler.post {
            try {
                internalProcess(buffer, validSamples)
            } finally {
                if (buffer.size == frameSize) inputPool.offer(buffer)
            }
        }
    }

    // internal processing runs on worker thread
    private fun internalProcess(buf: ShortArray, validSamples: Int) {
        if (closed) return

        var offset = 0
        var remaining = validSamples

        while (remaining > 0) {
            val needed = frameSize - aggFill
            val toCopy = minOf(needed, remaining)
            System.arraycopy(buf, offset, aggregator, aggFill, toCopy)
            aggFill += toCopy
            offset += toCopy
            remaining -= toCopy

            if (aggFill == frameSize) {
                // we have a full frame in aggregator
                val inFrame = aggregator // worker-owned
                val outFrame = acquireOutputBuffer()

                val statusObj = processNative(nativeHandle, inFrame, outFrame)
                statusObj?.let { status ->
                    // convert native DenoiserResult -> our DenoiserResult (if types differ adjust accordingly)
                    val denStatus = DenoiserResult(
                        status.vadProbability,
                        status.isSpeech,
                        status.samplesProcessed
                    )

                    processedAudioCallback?.let { cb ->
                        if (zeroCopyDelivery) {
                            // deliver a buffer from pool (caller MUST NOT retain it)
                            try {
                                cb(outFrame, denStatus)
                            } catch (t: Throwable) {
                                Log.w(TAG, "user callback failed: ${t.message}")
                            }
                        } else {
                            // safe delivery: give a copy the user owns
                            val delivered = outFrame.copyOf()
                            try {
                                cb(delivered, denStatus)
                            } catch (t: Throwable) {
                                Log.w(TAG, "user callback failed: ${t.message}")
                            }
                        }
                    }
                }

                // return outFrame to pool (if zeroCopyDelivery was used, user must not retain)
                outputPool.offer(outFrame)
                // reset aggregator
                aggFill = 0
            }
        }
    }

    private fun acquireOutputBuffer(): ShortArray = outputPool.poll() ?: ShortArray(frameSize)

    // synchronous shutdown on worker
    private fun closeInternal() {
        if (closed) return
        closed = true
        try {
            // post a final task that destroys native handle and quits thread
            val latch = java.util.concurrent.CountDownLatch(1)
            workerHandler.post {
                try {
                    if (nativeHandle != 0L) {
                        try {
                            destroyNative(nativeHandle)
                        } catch (_: Throwable) {
                        }
                        nativeHandle = 0L
                    }
                } finally {
                    latch.countDown()
                }
            }
            latch.await()
        } catch (_: Throwable) {
        }
        try {
            workerThread.quitSafely()
        } catch (_: Throwable) {
        }
    }

    override fun close() {
        // prefer explicit close - synchronous
        closeInternal()
        cleaner.clean()
    }

    // JNI bindings
    private external fun createNative(
        modelPreset: Int, modelPath: String?, vadThreshold: Float, collectStatistics: Boolean,
        inputSampleRate: Int, resampleQuality: Int
    ): Long

    /**
     * NOTE: This uses the "object" returning JNI function.
     * Signature on Kotlin side: external fun processNative(handle, input, output): DenoiserResult
     *
     * It is expected that `processNative` will:
     *  - accept exactly `frameSize` input samples (as computed above)
     *  - write `frameSize` samples into `output`
     *  - return a DenoiserResult object (or throw/return null on error)
     */
    private external fun processNative(
        handle: Long,
        input: ShortArray,
        output: ShortArray
    ): DenoiserResult?

    private external fun destroyNative(handle: Long)

    // keep processNativeFast declaration removed/unused
}
