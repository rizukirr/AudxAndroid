package com.android.audx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Instrumented tests for the Denoiser library.
 * These tests run on an Android device and verify the native library integration.
 *
 * The tests use noise_audio.pcm from raw resources which is 48kHz 16-bit PCM mono audio.
 *
 * Note: This library only supports mono (single-channel) audio processing.
 */
@RunWith(AndroidJUnit4::class)
class DenoiserInstrumentedTest {

    private var audxDenoiser: AudxDenoiser? = null
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        // Ensure any previous denoiser is cleaned up
        audxDenoiser?.destroy()
        audxDenoiser = null
    }

    @After
    fun tearDown() {
        audxDenoiser?.destroy()
        audxDenoiser = null
    }

    // ==================== Initialization Tests ====================

    @Test
    fun testBuilderWithDefaultSettings() {
        audxDenoiser = AudxDenoiser.Builder()
            .build()

        assertNotNull("Denoiser should be created", audxDenoiser)
    }

    @Test
    fun testBuilderConfiguration() {
        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .collectStatistics(true)
            .build()

        assertNotNull("Denoiser should be created", audxDenoiser)
    }


    @Test
    fun testBuilderCustomVadThreshold() {
        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.7f)
            .build()

        assertNotNull("Denoiser with custom VAD threshold should be created", audxDenoiser)
    }

    @Test
    fun testBuilderVadDisabled() {
        audxDenoiser = AudxDenoiser.Builder()
            .collectStatistics(false)
            .build()

        assertNotNull("Denoiser with VAD disabled should be created", audxDenoiser)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidVadThreshold_Negative() {
        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(-0.1f)
            .build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidVadThreshold_TooHigh() {
        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(1.5f)
            .build()
    }

    // ==================== Constants Tests ====================

    @Test
    fun testConstants() {
        assertEquals("Sample rate should be 48000", 48000, AudxDenoiser.SAMPLE_RATE)
        assertEquals("Frame size should be 480", 480, AudxDenoiser.FRAME_SIZE)
        assertEquals("Frame duration should be 10ms", 10, AudxDenoiser.getFrameDurationMs())
    }

    @Test
    fun testGetRecommendedBufferSize() {
        // For mono, 10ms at 48kHz: 48000 * 0.01 * 1 * 2 bytes = 960 bytes
        assertEquals("Buffer size for 10ms", 960, AudxDenoiser.getRecommendedBufferSize(10))

        // For mono, 20ms at 48kHz: 48000 * 0.02 * 1 * 2 bytes = 1920 bytes
        assertEquals("Buffer size for 20ms", 1920, AudxDenoiser.getRecommendedBufferSize(20))
    }

    // ==================== Audio Processing Tests ====================

    @Test
    fun testProcessChunk_SingleFrame() = runBlocking {
        val audioData = loadPcmAudioFromRaw(R.raw.noise_audio)
        val frameSize = AudxDenoiser.FRAME_SIZE // 480 samples for mono

        var callbackInvoked = false
        var receivedAudio: ShortArray? = null
        var receivedResult: DenoiserResult? = null

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .onProcessedAudio { audio, result ->
                callbackInvoked = true
                receivedAudio = audio
                receivedResult = result
            }
            .build()

        // Process first frame
        val firstFrame = audioData.copyOfRange(0, frameSize)
        audxDenoiser?.processChunk(firstFrame)

        assertTrue("Callback should be invoked", callbackInvoked)
        assertNotNull("Received audio should not be null", receivedAudio)
        assertNotNull("Received result should not be null", receivedResult)
        assertEquals("Output size should match frame size", frameSize, receivedAudio?.size)
    }

    @Test
    fun testProcessChunk_MultipleFrames() = runBlocking {
        val audioData = loadPcmAudioFromRaw(R.raw.noise_audio)
        val frameSize = AudxDenoiser.FRAME_SIZE
        val numFramesToProcess = 10

        var callbackCount = 0

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .onProcessedAudio { _, _ ->
                callbackCount++
            }
            .build()

        // Process 10 frames
        for (i in 0 until numFramesToProcess) {
            val start = i * frameSize
            val end = minOf(start + frameSize, audioData.size)
            if (end - start == frameSize) {
                val frame = audioData.copyOfRange(start, end)
                audxDenoiser?.processChunk(frame)
            }
        }

        assertEquals("Callback should be invoked 10 times", numFramesToProcess, callbackCount)
    }

    @Test
    fun testProcessChunk_VadProbabilityRange() = runBlocking {
        val audioData = loadPcmAudioFromRaw(R.raw.noise_audio)
        val frameSize = AudxDenoiser.FRAME_SIZE

        val vadProbabilities = mutableListOf<Float>()

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .onProcessedAudio { _, result ->
                vadProbabilities.add(result.vadProbability)
            }
            .build()

        // Process first 10 frames
        for (i in 0 until 10) {
            val start = i * frameSize
            val end = start + frameSize
            if (end <= audioData.size) {
                val frame = audioData.copyOfRange(start, end)
                audxDenoiser?.processChunk(frame)
            }
        }

        assertTrue("Should have VAD probabilities", vadProbabilities.isNotEmpty())
        vadProbabilities.forEach { prob ->
            assertTrue("VAD probability should be >= 0.0: $prob", prob >= 0.0f)
            assertTrue("VAD probability should be <= 1.0: $prob", prob <= 1.0f)
        }
    }

    @Test
    fun testProcessChunk_SamplesProcessedCount() = runBlocking {
        val audioData = loadPcmAudioFromRaw(R.raw.noise_audio)
        val frameSize = AudxDenoiser.FRAME_SIZE

        var samplesProcessed = 0

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .collectStatistics(true)
            .onProcessedAudio { _, result ->
                samplesProcessed = result.samplesProcessed
            }
            .build()

        val firstFrame = audioData.copyOfRange(0, frameSize)
        audxDenoiser?.processChunk(firstFrame)

        assertEquals("Samples processed should be 480 for mono", frameSize, samplesProcessed)
    }

    @Test
    fun testProcessChunk_OutputNotAllZeros() = runBlocking {
        val audioData = loadPcmAudioFromRaw(R.raw.noise_audio)
        val frameSize = AudxDenoiser.FRAME_SIZE

        var lastReceivedAudio: ShortArray? = null
        var frameCount = 0

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .onProcessedAudio { audio, _ ->
                lastReceivedAudio = audio
                frameCount++
            }
            .build()

        // Process first 3 frames to allow denoiser to warm up
        for (i in 0 until 3) {
            val start = i * frameSize
            val end = start + frameSize
            if (end <= audioData.size) {
                val frame = audioData.copyOfRange(start, end)
                audxDenoiser?.processChunk(frame)
            }
        }

        assertEquals("Should have processed 3 frames", 3, frameCount)
        assertNotNull("Received audio should not be null", lastReceivedAudio)

        // Check that at least one of the processed frames has non-zero output
        val hasNonZero = lastReceivedAudio?.any { it != 0.toShort() } ?: false
        assertTrue("Output should contain non-zero values after warmup", hasNonZero)
    }

    // ==================== Streaming Mode Tests ====================

    @Test
    fun testProcessChunk_SmallChunks_Buffering() = runBlocking {
        val audioData = loadPcmAudioFromRaw(R.raw.noise_audio)
        val chunkSize = 100 // Smaller than frame size (480)

        var callbackCount = 0

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .onProcessedAudio { _, _ ->
                callbackCount++
            }
            .build()

        // Process multiple small chunks
        val numChunks = 10
        for (i in 0 until numChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, audioData.size)
            val chunk = audioData.copyOfRange(start, end)
            audxDenoiser?.processChunk(chunk)
        }

        // Total samples = 1000, which gives 2 complete frames (960 samples)
        assertEquals("Should process 2 complete frames", 2, callbackCount)
    }

    @Test
    fun testProcessChunk_LargeChunks() = runBlocking {
        val audioData = loadPcmAudioFromRaw(R.raw.noise_audio)
        val largeChunkSize = 2000 // Larger than one frame

        var callbackCount = 0

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .onProcessedAudio { _, _ ->
                callbackCount++
            }
            .build()

        val chunk = audioData.copyOfRange(0, minOf(largeChunkSize, audioData.size))
        audxDenoiser?.processChunk(chunk)

        // 2000 samples should produce 4 complete frames (4 * 480 = 1920)
        assertEquals("Should process 4 frames from large chunk", 4, callbackCount)
    }

    @Test
    fun testFlush_WithRemainingData() = runBlocking {
        val partialFrameSize = 240 // Half a frame

        var callbackCount = 0
        var lastAudioSize = 0

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .onProcessedAudio { audio, _ ->
                callbackCount++
                lastAudioSize = audio.size
            }
            .build()

        // Create partial frame
        val partialData = ShortArray(partialFrameSize) { (it % 100).toShort() }
        audxDenoiser?.processChunk(partialData)

        // Should not trigger callback yet (incomplete frame)
        assertEquals("Should not process incomplete frame", 0, callbackCount)

        // Flush should process the remaining data
        audxDenoiser?.flush()

        assertEquals("Flush should trigger callback", 1, callbackCount)
        assertEquals(
            "Last audio size should be the partial frame size",
            partialFrameSize,
            lastAudioSize
        )
    }

    @Test
    fun testFlush_NoRemainingData() = runBlocking {
        val frameSize = AudxDenoiser.FRAME_SIZE

        var callbackCount = 0

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .onProcessedAudio { _, _ ->
                callbackCount++
            }
            .build()

        // Process complete frame
        val completeFrame = ShortArray(frameSize) { (it % 100).toShort() }
        audxDenoiser?.processChunk(completeFrame)

        assertEquals("Should process 1 frame", 1, callbackCount)

        // Flush with no remaining data
        audxDenoiser?.flush()

        // Should still be 1 (no additional callback)
        assertEquals("Flush should not trigger additional callback", 1, callbackCount)
    }

    @Test
    fun testProcessChunk_VariableSizeChunks() = runBlocking {
        val audioData = loadPcmAudioFromRaw(R.raw.noise_audio)

        var callbackCount = 0
        val chunkSizes = listOf(100, 300, 450, 200, 500)

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .onProcessedAudio { _, _ ->
                callbackCount++
            }
            .build()

        var offset = 0
        chunkSizes.forEach { size ->
            if (offset + size <= audioData.size) {
                val chunk = audioData.copyOfRange(offset, offset + size)
                audxDenoiser?.processChunk(chunk)
                offset += size
            }
        }

        // Total: 100 + 300 + 450 + 200 + 500 = 1550 samples
        // Complete frames: 1550 / 480 = 3 frames (1440 samples)
        assertEquals("Should process 3 complete frames", 3, callbackCount)
    }

    // ==================== Resource Management Tests ====================

    @Test
    fun testDestroy() {
        audxDenoiser = AudxDenoiser.Builder()
            .build()

        assertNotNull("Denoiser should be created", audxDenoiser)

        audxDenoiser?.destroy()

        // Attempting to use after destroy should fail
        try {
            runBlocking {
                audxDenoiser?.processChunk(ShortArray(480))
            }
            fail("Should throw exception when using destroyed denoiser")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Should throw IllegalStateException",
                e.message?.contains("destroyed") == true
            )
        }
    }

    @Test
    fun testAutoCloseable() {
        var denoisedSamples = 0

        AudxDenoiser.Builder()
            .onProcessedAudio { audio, _ ->
                denoisedSamples += audio.size
            }
            .build().use { denoiser ->
                runBlocking {
                    val frame = ShortArray(480) { (it % 100).toShort() }
                    denoiser.processChunk(frame)
                }
            }

        assertEquals("Should process frame before closing", 480, denoisedSamples)
    }

    @Test
    fun testMultipleDestroyCalls() {
        audxDenoiser = AudxDenoiser.Builder()
            .build()

        audxDenoiser?.destroy()
        // Second destroy should not crash
        audxDenoiser?.destroy()
    }


    // ==================== Error Cases ====================

    @Test(expected = IllegalArgumentException::class)
    fun testProcessChunk_WithoutCallback() {
        audxDenoiser = AudxDenoiser.Builder()
            // No callback set
            .build()

        runBlocking {
            val frame = ShortArray(480)
            audxDenoiser?.processChunk(frame)
        }
    }

    @Test
    fun testIsVoiceDetected() {
        audxDenoiser = AudxDenoiser.Builder()
            .build()

        assertTrue(
            "Should detect voice at 0.6 with threshold 0.5",
            audxDenoiser?.isVoiceDetected(0.6f, 0.5f) == true
        )

        assertFalse(
            "Should not detect voice at 0.3 with threshold 0.5",
            audxDenoiser?.isVoiceDetected(0.3f, 0.5f) == true
        )

        assertTrue(
            "Should detect voice at exactly threshold",
            audxDenoiser?.isVoiceDetected(0.5f, 0.5f) == true
        )
    }


    @Test
    fun testProcessChunk_ValidatesEmptyChunk() = runBlocking {
        audxDenoiser = AudxDenoiser.Builder()
            .onProcessedAudio { _, _ -> }
            .build()

        try {
            audxDenoiser!!.processChunk(ShortArray(0))
            fail("Should throw IllegalArgumentException for empty chunk")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Error message should mention invalid chunk",
                e.message?.contains("chunk", ignoreCase = true) == true ||
                        e.message?.contains("empty", ignoreCase = true) == true
            )
        }
    }

    @Test
    fun testProcessChunk_AcceptsValidChunkSizes() = runBlocking {
        var processedCount = 0
        audxDenoiser = AudxDenoiser.Builder()
            .onProcessedAudio { _, _ -> processedCount++ }
            .build()

        // Test various valid chunk sizes (streaming mode handles buffering)
        val validSizes = listOf(100, 480, 960, 1024)

        validSizes.forEach { size ->
            val chunk = ShortArray(size) { (it % 1000).toShort() }
            try {
                audxDenoiser!!.processChunk(chunk)
                // Success - no exception thrown
            } catch (e: IllegalArgumentException) {
                fail("Chunk size $size should be valid, but got: ${e.message}")
            }
        }

        // Give some time for processing
        kotlinx.coroutines.delay(100)

        // Should have processed some frames
        assertTrue("Should have processed at least one frame", processedCount > 0)
    }

    @Test
    fun testNativeConstants_AreCorrect() {
        // Verify native constants match expected values
        assertEquals("Sample rate should be 48000 Hz", 48000, AudxDenoiser.SAMPLE_RATE)
        assertEquals("Channels should be 1 (mono)", 1, AudxDenoiser.CHANNELS)
        assertEquals("Bit depth should be 16", 16, AudxDenoiser.BIT_DEPTH)
        assertEquals("Frame size should be 480", 480, AudxDenoiser.FRAME_SIZE)
    }

    // ==================== Statistics Tests ====================

    @Test
    fun testGetStats_ReturnsValidStats() = runBlocking {
        val audioData = loadPcmAudioFromRaw(R.raw.noise_audio)
        val frameSize = AudxDenoiser.FRAME_SIZE

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .collectStatistics(true)
            .onProcessedAudio { _, _ -> }
            .build()

        assert(audxDenoiser != null)

        // Process a few frames
        for (i in 0 until 5) {
            val start = i * frameSize
            val end = start + frameSize
            if (end <= audioData.size) {
                val frame = audioData.copyOfRange(start, end)
                audxDenoiser?.processChunk(frame)
            }
        }

        val stats = audxDenoiser?.getStats()

        assertNotNull("Stats should not be null", stats)
        assertEquals("Frame count should be 5", 5, stats!!.frameProcessed)
        assertTrue(
            "Speech percentage should be in range 0-100",
            stats.speechDetectedPercent in 0.0f..100.0f
        )
        assertTrue(
            "VAD avg should be in range 0-1",
            stats.vadScoreAvg in 0.0f..1.0f
        )
        assertTrue(
            "VAD min should be in range 0-1",
            stats.vadScoreMin in 0.0f..1.0f
        )
        assertTrue(
            "VAD max should be in range 0-1",
            stats.vadScoreMax in 0.0f..1.0f
        )
        assertTrue(
            "Processing time total should be >= 0",
            stats.processingTimeTotal >= 0.0f
        )
        assertTrue(
            "Processing time avg should be >= 0",
            stats.processingTimeAvg >= 0.0f
        )
        assertTrue(
            "Processing time last should be >= 0",
            stats.processingTimeLast >= 0.0f
        )
    }

    @Test
    fun testGetStats_AccumulatesOverTime() = runBlocking {
        val audioData = loadPcmAudioFromRaw(R.raw.noise_audio)
        val frameSize = AudxDenoiser.FRAME_SIZE

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .collectStatistics(true)
            .onProcessedAudio { _, _ -> }
            .build()

        // Process first frame
        val frame1 = audioData.copyOfRange(0, frameSize)
        audxDenoiser?.processChunk(frame1)

        val stats1 = audxDenoiser?.getStats()
        assertEquals("Frame count should be 1", 1, stats1?.frameProcessed)

        // Process more frames
        for (i in 1 until 10) {
            val start = i * frameSize
            val end = start + frameSize
            if (end <= audioData.size) {
                val frame = audioData.copyOfRange(start, end)
                audxDenoiser?.processChunk(frame)
            }
        }

        val stats2 = audxDenoiser?.getStats()
        assertEquals("Frame count should be 10", 10, stats2?.frameProcessed)

        // Note: On very fast devices/emulators (x86_64 with AVX), processing can be
        // < 0.1ms per frame, which may round to same float value. We verify timing
        // is non-negative and non-decreasing rather than strictly increasing.
        assertTrue(
            "Total processing time should be non-negative after frame 1",
            (stats1?.processingTimeTotal ?: -1.0f) >= 0.0f
        )
        assertTrue(
            "Total processing time should be non-negative after frame 10",
            (stats2?.processingTimeTotal ?: -1.0f) >= 0.0f
        )
        // Verify it either increases OR stays non-negative (on very fast devices/emulators)
        assertTrue(
            "Total processing time should increase or stay non-negative",
            (stats2?.processingTimeTotal ?: 0.0f) >= (stats1?.processingTimeTotal ?: 0.0f)
        )
    }

    @Test
    fun testResetStats_ClearsAllCounters() = runBlocking {
        val audioData = loadPcmAudioFromRaw(R.raw.noise_audio)
        val frameSize = AudxDenoiser.FRAME_SIZE

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .collectStatistics(true)
            .onProcessedAudio { _, _ -> }
            .build()

        // Process some frames
        for (i in 0 until 5) {
            val start = i * frameSize
            val end = start + frameSize
            if (end <= audioData.size) {
                val frame = audioData.copyOfRange(start, end)
                audxDenoiser?.processChunk(frame)
            }
        }

        val statsBeforeReset = audxDenoiser?.getStats()
        assertEquals("Frame count should be 5 before reset", 5, statsBeforeReset?.frameProcessed)

        // Reset statistics
        audxDenoiser?.resetStats()

        val statsAfterReset = audxDenoiser?.getStats()
        assertEquals("Frame count should be 0 after reset", 0, statsAfterReset?.frameProcessed)
        assertEquals(
            "Speech percentage should be 0 after reset",
            0.0f, statsAfterReset?.speechDetectedPercent
        )
        assertEquals(
            "Processing time total should be 0 after reset",
            0.0f, statsAfterReset?.processingTimeTotal
        )
    }

    @Test
    fun testStats_PersistAcrossFlush() = runBlocking {
        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .collectStatistics(true)
            .onProcessedAudio { _, _ -> }
            .build()

        // Process a complete frame
        val frame = ShortArray(480) { (it % 100).toShort() }
        audxDenoiser?.processChunk(frame)

        val statsBeforeFlush = audxDenoiser?.getStats()
        assertEquals("Frame count should be 1", 1, statsBeforeFlush?.frameProcessed)

        // Flush should NOT reset statistics
        audxDenoiser?.flush()

        val statsAfterFlush = audxDenoiser?.getStats()
        assertEquals(
            "Frame count should still be 1 after flush",
            1, statsAfterFlush?.frameProcessed
        )
        assertEquals(
            "Stats should persist across flush",
            statsBeforeFlush?.processingTimeTotal, statsAfterFlush?.processingTimeTotal
        )
    }

    @Test
    fun testStats_VADMinMaxTracking() = runBlocking {
        val audioData = loadPcmAudioFromRaw(R.raw.noise_audio)
        val frameSize = AudxDenoiser.FRAME_SIZE

        val vadScores = mutableListOf<Float>()

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .collectStatistics(true)
            .onProcessedAudio { _, result ->
                vadScores.add(result.vadProbability)
            }
            .build()

        // Process multiple frames
        for (i in 0 until 20) {
            val start = i * frameSize
            val end = start + frameSize
            if (end <= audioData.size) {
                val frame = audioData.copyOfRange(start, end)
                audxDenoiser?.processChunk(frame)
            }
        }

        val stats = audxDenoiser?.getStats()
        assertNotNull("Stats should not be null", stats)

        // Verify min/max tracking
        val actualMin = vadScores.minOrNull() ?: 1.0f
        val actualMax = vadScores.maxOrNull() ?: 0.0f

        assertEquals(
            "VAD min should match actual minimum",
            actualMin, stats!!.vadScoreMin, 0.001f
        )
        assertEquals(
            "VAD max should match actual maximum",
            actualMax, stats.vadScoreMax, 0.001f
        )
        assertTrue(
            "VAD min should be <= VAD avg",
            stats.vadScoreMin <= stats.vadScoreAvg
        )
        assertTrue(
            "VAD max should be >= VAD avg",
            stats.vadScoreMax >= stats.vadScoreAvg
        )
    }

    @Test
    fun testStats_SpeechDetectionPercentage() = runBlocking {
        val audioData = loadPcmAudioFromRaw(R.raw.noise_audio)
        val frameSize = AudxDenoiser.FRAME_SIZE
        val threshold = 0.5f

        var speechFrameCount = 0
        var totalFrameCount = 0

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(threshold)
            .collectStatistics(true)
            .onProcessedAudio { _, result ->
                totalFrameCount++
                if (result.isSpeech) {
                    speechFrameCount++
                }
            }
            .build()

        // Process frames
        for (i in 0 until 10) {
            val start = i * frameSize
            val end = start + frameSize
            if (end <= audioData.size) {
                val frame = audioData.copyOfRange(start, end)
                audxDenoiser?.processChunk(frame)
            }
        }

        val stats = audxDenoiser?.getStats()
        assertNotNull("Stats must be not null", stats)
        val expectedPercentage = (speechFrameCount.toFloat() / totalFrameCount.toFloat()) * 100f

        assertEquals(
            "Speech percentage should match calculated value",
            expectedPercentage, stats!!.speechDetectedPercent, 0.1f
        )
    }

    @Test
    fun testStats_ProcessingTimeIsReasonable() = runBlocking {
        val audioData = loadPcmAudioFromRaw(R.raw.noise_audio)
        val frameSize = AudxDenoiser.FRAME_SIZE

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .collectStatistics(true)
            .onProcessedAudio { _, _ -> }
            .build()

        // Process a frame
        for (i in 0 until 100) {
            val start = i * frameSize
            val end = start + frameSize
            if (end <= audioData.size) {
                val frame = audioData.copyOfRange(start, end)
                audxDenoiser?.processChunk(frame)
            }
        }

        val stats = audxDenoiser?.getStats()
        assertNotNull("Stats should not be null", stats)

        // On a real device/emulator, processing should take some time.
        assertTrue(
            "Average processing time should be > 0.0f for real audio",
            (stats?.processingTimeAvg ?: 0.0f) > 0.0f
        )
        // Processing time should be reasonable (< 100ms per frame for real-time)
        assertTrue(
            "Processing time should be < 100ms per frame",
            (stats?.processingTimeAvg ?: Float.MAX_VALUE) < 100.0f
        )
        assertTrue(
            "Last frame time should be >= 0",
            (stats?.processingTimeLast ?: -1.0f) >= 0.0f
        )
    }

    @Test
    fun testGetStats_AfterDestroy_ThrowsException() {
        audxDenoiser = AudxDenoiser.Builder()
            .build()

        audxDenoiser?.destroy()

        try {
            audxDenoiser?.getStats()
            fail("Should throw exception when getting stats from destroyed denoiser")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Should throw IllegalStateException",
                e.message?.contains("destroyed") == true
            )
        }
    }

    @Test
    fun testResetStats_AfterDestroy_ThrowsException() {
        audxDenoiser = AudxDenoiser.Builder()
            .build()

        audxDenoiser?.destroy()

        try {
            audxDenoiser?.resetStats()
            fail("Should throw exception when resetting stats of destroyed denoiser")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Should throw IllegalStateException",
                e.message?.contains("destroyed") == true
            )
        }
    }

    @Test
    fun testStats_PerSessionMeasurement() = runBlocking {
        val audioData = loadPcmAudioFromRaw(R.raw.noise_audio)
        val frameSize = AudxDenoiser.FRAME_SIZE

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .collectStatistics(true)
            .onProcessedAudio { _, _ -> }
            .build()

        // Session 1: Process 5 frames
        audxDenoiser?.resetStats()
        for (i in 0 until 5) {
            val start = i * frameSize
            val end = start + frameSize
            if (end <= audioData.size) {
                val frame = audioData.copyOfRange(start, end)
                audxDenoiser?.processChunk(frame)
            }
        }
        val session1Stats = audxDenoiser?.getStats()
        assertEquals("Session 1 should have 5 frames", 5, session1Stats?.frameProcessed)

        // Session 2: Reset and process 3 frames
        audxDenoiser?.resetStats()
        for (i in 0 until 3) {
            val start = i * frameSize
            val end = start + frameSize
            if (end <= audioData.size) {
                val frame = audioData.copyOfRange(start, end)
                audxDenoiser?.processChunk(frame)
            }
        }
        val session2Stats = audxDenoiser?.getStats()
        assertEquals("Session 2 should have 3 frames", 3, session2Stats?.frameProcessed)
    }

    // ==================== Resampler Tests ===================

    @Test
    fun testResamplerConstants() {
        assertEquals("RESAMPLER_QUALITY_MIN should be 0", 0, AudxDenoiser.RESAMPLER_QUALITY_MIN)
        assertEquals("RESAMPLER_QUALITY_MAX should be 10", 10, AudxDenoiser.RESAMPLER_QUALITY_MAX)
        assertEquals(
            "RESAMPLER_QUALITY_DEFAULT should be 4",
            4,
            AudxDenoiser.RESAMPLER_QUALITY_DEFAULT
        )
        assertEquals("RESAMPLER_QUALITY_VOIP should be 3", 3, AudxDenoiser.RESAMPLER_QUALITY_VOIP)
    }

    @Test
    fun testDenoiser_WithDefaultSampleRate_NoResampling() = runBlocking {
        // Default is 48kHz, no resampling needed
        val frameSize = AudxDenoiser.FRAME_SIZE // 480 samples for 48kHz
        var callbackCount = 0

        audxDenoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .onProcessedAudio { audio, _ ->
                callbackCount++
                assertEquals("Output frame should be $frameSize samples", frameSize, audio.size)
            }
            .build()

        val frame = ShortArray(frameSize) { (it % 100).toShort() }
        audxDenoiser?.processChunk(frame)

        assertEquals("Should process 1 frame", 1, callbackCount)
    }

    @Test
    fun testDenoiser_With16kHzInput_AutomaticResampling() = runBlocking {
        // 16kHz input, should be resampled to 48kHz for processing, then back to 16kHz
        val inputSampleRate = 16000
        val inputFrameSize = (inputSampleRate * 10 / 1000) // 160 samples for 10ms at 16kHz
        var callbackCount = 0
        var outputSize = 0

        audxDenoiser = AudxDenoiser.Builder()
            .inputSampleRate(inputSampleRate)
            .resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_DEFAULT)
            .vadThreshold(0.5f)
            .onProcessedAudio { audio, result ->
                callbackCount++
                outputSize = audio.size
                assertNotNull("Result should not be null", result)
            }
            .build()

        val frame = ShortArray(inputFrameSize) { (it % 100).toShort() }
        audxDenoiser?.processChunk(frame)

        assertEquals("Should process 1 frame", 1, callbackCount)
        assertEquals(
            "Output should be same size as input ($inputFrameSize)",
            inputFrameSize,
            outputSize
        )
    }

    @Test
    fun testDenoiser_With24kHzInput_AutomaticResampling() = runBlocking {
        val inputSampleRate = 24000
        val inputFrameSize = (inputSampleRate * 10 / 1000) // 240 samples for 10ms at 24kHz
        var callbackCount = 0

        audxDenoiser = AudxDenoiser.Builder()
            .inputSampleRate(inputSampleRate)
            .resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_VOIP)
            .vadThreshold(0.5f)
            .onProcessedAudio { audio, _ ->
                callbackCount++
                assertEquals("Output should match input frame size", inputFrameSize, audio.size)
            }
            .build()

        val frame = ShortArray(inputFrameSize) { (it % 100).toShort() }
        audxDenoiser?.processChunk(frame)

        assertEquals("Should process 1 frame", 1, callbackCount)
    }

    @Test
    fun testDenoiser_With8kHzInput_AutomaticResampling() = runBlocking {
        val inputSampleRate = 8000
        val inputFrameSize = (inputSampleRate * 10 / 1000) // 80 samples for 10ms at 8kHz
        var callbackCount = 0

        audxDenoiser = AudxDenoiser.Builder()
            .inputSampleRate(inputSampleRate)
            .resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_VOIP)
            .vadThreshold(0.5f)
            .onProcessedAudio { audio, _ ->
                callbackCount++
                assertEquals("Output should match input frame size", inputFrameSize, audio.size)
            }
            .build()

        val frame = ShortArray(inputFrameSize) { (it % 100).toShort() }
        audxDenoiser?.processChunk(frame)

        assertEquals("Should process 1 frame", 1, callbackCount)
    }

    @Test
    fun testDenoiser_WithResampling_MultipleFrames() = runBlocking {
        val inputSampleRate = 16000
        val inputFrameSize = (inputSampleRate * 10 / 1000) // 160 samples
        var callbackCount = 0

        audxDenoiser = AudxDenoiser.Builder()
            .inputSampleRate(inputSampleRate)
            .resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_DEFAULT)
            .vadThreshold(0.5f)
            .onProcessedAudio { _, _ ->
                callbackCount++
            }
            .build()

        // Process 5 frames
        repeat(5) {
            val frame = ShortArray(inputFrameSize) { (it % 100).toShort() }
            audxDenoiser?.processChunk(frame)
        }

        assertEquals("Should process 5 frames", 5, callbackCount)
    }

    @Test
    fun testDenoiser_WithResampling_SmallChunksBuffering() = runBlocking {
        val inputSampleRate = 16000
        val chunkSize = 50 // Smaller than frame size
        var callbackCount = 0

        audxDenoiser = AudxDenoiser.Builder()
            .inputSampleRate(inputSampleRate)
            .resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_VOIP)
            .vadThreshold(0.5f)
            .onProcessedAudio { _, _ ->
                callbackCount++
            }
            .build()

        // Process multiple small chunks (total: 500 samples = 3 complete frames at 16kHz)
        repeat(10) {
            val chunk = ShortArray(chunkSize) { (it % 100).toShort() }
            audxDenoiser?.processChunk(chunk)
        }

        assertEquals("Should process 3 complete frames (480/160)", 3, callbackCount)
    }

    @Test
    fun testDenoiser_WithResampling_FlushRemainingData() = runBlocking {
        val inputSampleRate = 16000
        val inputFrameSize = (inputSampleRate * 10 / 1000) // 160 samples
        val partialSize = inputFrameSize / 2 // 80 samples (half frame)
        var callbackCount = 0

        audxDenoiser = AudxDenoiser.Builder()
            .inputSampleRate(inputSampleRate)
            .resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_DEFAULT)
            .vadThreshold(0.5f)
            .onProcessedAudio { _, _ ->
                callbackCount++
            }
            .build()

        // Send partial frame
        val partialFrame = ShortArray(partialSize) { (it % 100).toShort() }
        audxDenoiser?.processChunk(partialFrame)

        assertEquals("Should not process incomplete frame", 0, callbackCount)

        // Flush should process remaining data
        audxDenoiser?.flush()

        assertEquals("Should process flushed frame", 1, callbackCount)
    }

    @Test
    fun testDenoiser_ResamplingQualityLevels() = runBlocking {
        val inputSampleRate = 16000
        val inputFrameSize = (inputSampleRate * 10 / 1000)

        // Test all quality levels
        val qualityLevels = listOf(
            AudxDenoiser.RESAMPLER_QUALITY_MIN,
            AudxDenoiser.RESAMPLER_QUALITY_VOIP,
            AudxDenoiser.RESAMPLER_QUALITY_DEFAULT,
            AudxDenoiser.RESAMPLER_QUALITY_MAX
        )

        qualityLevels.forEach { quality ->
            var callbackInvoked = false
            val denoiser = AudxDenoiser.Builder()
                .inputSampleRate(inputSampleRate)
                .resampleQuality(quality)
                .vadThreshold(0.5f)
                .onProcessedAudio { _, _ ->
                    callbackInvoked = true
                }
                .build()

            val frame = ShortArray(inputFrameSize) { (it % 100).toShort() }
            denoiser.processChunk(frame)

            assertTrue("Quality level $quality should work", callbackInvoked)
            denoiser.destroy()
        }
    }

    @Test
    fun testDenoiser_WithResampling_VadProbabilityInRange() = runBlocking {
        val inputSampleRate = 16000
        val inputFrameSize = (inputSampleRate * 10 / 1000)
        val vadProbabilities = mutableListOf<Float>()

        audxDenoiser = AudxDenoiser.Builder()
            .inputSampleRate(inputSampleRate)
            .resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_DEFAULT)
            .vadThreshold(0.5f)
            .onProcessedAudio { _, result ->
                vadProbabilities.add(result.vadProbability)
            }
            .build()

        // Process multiple frames
        repeat(10) {
            val frame = ShortArray(inputFrameSize) { (it % 1000).toShort() }
            audxDenoiser?.processChunk(frame)
        }

        assertTrue("Should have VAD probabilities", vadProbabilities.isNotEmpty())
        vadProbabilities.forEach { prob ->
            assertTrue("VAD probability should be >= 0.0: $prob", prob >= 0.0f)
            assertTrue("VAD probability should be <= 1.0: $prob", prob <= 1.0f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testDenoiser_InvalidResampleQuality_TooLow() {
        audxDenoiser = AudxDenoiser.Builder()
            .inputSampleRate(16000)
            .resampleQuality(-1)
            .build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun testDenoiser_InvalidResampleQuality_TooHigh() {
        audxDenoiser = AudxDenoiser.Builder()
            .inputSampleRate(16000)
            .resampleQuality(11)
            .build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun testDenoiser_InvalidInputSampleRate_Zero() {
        audxDenoiser = AudxDenoiser.Builder()
            .inputSampleRate(0)
            .build()
    }

    @Test(expected = IllegalArgumentException::class)
    fun testDenoiser_InvalidInputSampleRate_Negative() {
        audxDenoiser = AudxDenoiser.Builder()
            .inputSampleRate(-1000)
            .build()
    }

    @Test
    fun testDenoiser_WithResampling_OutputNotAllZeros() = runBlocking {
        val inputSampleRate = 16000
        val inputFrameSize = (inputSampleRate * 10 / 1000)
        var lastReceivedAudio: ShortArray? = null

        audxDenoiser = AudxDenoiser.Builder()
            .inputSampleRate(inputSampleRate)
            .resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_DEFAULT)
            .vadThreshold(0.5f)
            .onProcessedAudio { audio, _ ->
                lastReceivedAudio = audio
            }
            .build()

        // Process a few frames to warm up
        repeat(3) {
            val frame = ShortArray(inputFrameSize) { ((it * 100) % 1000).toShort() }
            audxDenoiser?.processChunk(frame)
        }

        assertNotNull("Received audio should not be null", lastReceivedAudio)
        val hasNonZero = lastReceivedAudio?.any { it != 0.toShort() } ?: false
        assertTrue("Output should contain non-zero values", hasNonZero)
    }

    @Test
    fun testDenoiser_WithResampling_StatsTracking() = runBlocking {
        val inputSampleRate = 16000
        val inputFrameSize = (inputSampleRate * 10 / 1000)

        audxDenoiser = AudxDenoiser.Builder()
            .inputSampleRate(inputSampleRate)
            .resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_DEFAULT)
            .vadThreshold(0.5f)
            .collectStatistics(true)
            .onProcessedAudio { _, _ -> }
            .build()

        // Process frames
        repeat(5) {
            val frame = ShortArray(inputFrameSize) { (it % 100).toShort() }
            audxDenoiser?.processChunk(frame)
        }

        val stats = audxDenoiser?.getStats()
        assertNotNull("Stats should not be null", stats)
        assertEquals("Frame count should be 5", 5, stats?.frameProcessed)
        assertTrue(
            "Processing time should be reasonable",
            (stats?.processingTimeAvg ?: Float.MAX_VALUE) < 100.0f
        )
    }

    // ==================== Helper Methods ====================

    /**
     * Load PCM audio data from raw resources and convert to ShortArray.
     * The PCM file should be 16-bit signed PCM at 48kHz.
     */
    private fun loadPcmAudioFromRaw(resourceId: Int): ShortArray {
        val inputStream: InputStream = context.resources.openRawResource(resourceId)
        val byteArray = inputStream.readBytes()
        inputStream.close()

        return convertBytesToShorts(byteArray)
    }

    /**
     * Convert byte array to short array (16-bit PCM, little-endian)
     */
    private fun convertBytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        for (i in shorts.indices) {
            shorts[i] = byteBuffer.getShort()
        }

        return shorts
    }
}
