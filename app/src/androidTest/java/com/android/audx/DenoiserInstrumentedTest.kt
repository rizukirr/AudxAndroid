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
            .enableVadOutput(true)
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
            .enableVadOutput(false)
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

    // ==================== Validation Tests ====================

    @Test
    fun testProcessChunk_ValidatesNullChunk() {
        // Note: In Kotlin, we can't actually pass null to a non-nullable ShortArray parameter
        // This test verifies that the validator itself handles null properly
        val result = AudxValidator.validateChunk(null)
        assertTrue(
            "Null chunk should be rejected by validator",
            result is ValidationResult.Error
        )
        if (result is ValidationResult.Error) {
            assertTrue(
                "Error message should mention null",
                result.message.contains("null", ignoreCase = true)
            )
        }
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

    @Test
    fun testAudioFormatValidator_Integration() {
        // Test that validator works with denoiser constants
        val result = AudxValidator.validateFormat(
            sampleRate = AudxDenoiser.SAMPLE_RATE,
            channels = AudxDenoiser.CHANNELS,
            bitDepth = AudxDenoiser.BIT_DEPTH
        )

        assertTrue(
            "Denoiser constants should pass validation",
            result is ValidationResult.Success
        )
    }

    @Test
    fun testAudioFormatValidator_RejectsInvalidFormat() {
        // Test that validator rejects invalid formats
        val invalidFormats = listOf(
            Triple(44100, 1, 16),  // Wrong sample rate
            Triple(48000, 2, 16),  // Wrong channels
            Triple(48000, 1, 24)   // Wrong bit depth
        )

        invalidFormats.forEach { (sampleRate, channels, bitDepth) ->
            val result = AudxValidator.validateFormat(sampleRate, channels, bitDepth)
            assertTrue(
                "Invalid format ($sampleRate, $channels, $bitDepth) should be rejected",
                result is ValidationResult.Error
            )
        }
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
