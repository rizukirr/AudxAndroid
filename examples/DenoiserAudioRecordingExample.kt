package com.android.audx.examples

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.android.audx.AudioFormatValidator
import com.android.audx.Denoiser
import com.android.audx.DenoiserResult
import com.android.audx.ValidationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Example: Real-time audio recording with denoising
 *
 * This demonstrates how to integrate the Denoiser with real-time audio recording
 * for 48kHz 16-bit PCM audio processing.
 *
 * Key differences from standard recording:
 * - Sample rate: 48kHz (required by denoiser)
 * - Processes audio through Denoiser before sending
 * - Provides VAD (Voice Activity Detection) feedback
 */
class DenoiserAudioRecordingExample {

    companion object {
        private const val TAG = "DenoiserAudioRecording"
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 4
        private const val CHUNK_DURATION_MS = 100L // Send chunks every 500ms

        // VAD threshold for speech detection
        private const val VAD_THRESHOLD = 0.5f
    }

    private var audioRecord: AudioRecord? = null
    private var denoiser: Denoiser? = null
    private var isRecording = false
    private var recordingJob: Job? = null

    // Buffer for accumulating denoised audio before sending
    private val denoisedChunkBuffer = ByteArrayOutputStream()
    private var lastChunkSentTime = 0L

    // Callbacks
    var onVoiceChunkReady: ((String) -> Unit)? = null
    var onRecordingStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onVadDetected: ((Float, Boolean) -> Unit)? = null // (vadProbability, isSpeech)

    /**
     * Start recording with real-time denoising
     */
    fun start() {
        if (isRecording) return

        try {
            initializeDenoiser()
            initializeAudioRecord()
            isRecording = true
            denoisedChunkBuffer.reset()
            lastChunkSentTime = System.currentTimeMillis()
            onRecordingStateChanged?.invoke(true)
            startRecording()
        } catch (e: SecurityException) {
            onError?.invoke("Microphone permission not granted: ${e.message}")
            cleanup()
        } catch (e: Exception) {
            onError?.invoke("Failed to start recording: ${e.message}")
            cleanup()
        }
    }

    /**
     * Initialize the audio denoiser
     */
    private fun initializeDenoiser() {
        denoiser = Denoiser.Builder()
            .vadThreshold(VAD_THRESHOLD)
            .enableVadOutput(true)
            .modelPreset(Denoiser.ModelPreset.EMBEDDED)
            .onProcessedAudio { denoisedAudio, result ->
                // This callback is invoked for each processed frame (10ms of audio)
                handleDenoisedFrame(denoisedAudio, result)
            }
            .build()

        Log.i(TAG, "Denoiser initialized: " +
            "Frame size = ${Denoiser.FRAME_SIZE} samples, " +
            "Frame duration = ${Denoiser.getFrameDurationMs()}ms")
    }

    /**
     * Initialize AudioRecord for 48kHz recording
     */
    @SuppressLint("MissingPermission")
    private fun initializeAudioRecord() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            Denoiser.SAMPLE_RATE,  // Use constant from native library
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        val bufferSize = minBufferSize * BUFFER_SIZE_FACTOR

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            Denoiser.SAMPLE_RATE,  // Use constant from native library
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord initialization failed")
        }

        Log.i(TAG, "AudioRecord initialized: ${Denoiser.SAMPLE_RATE}Hz, mono, 16-bit PCM, buffer=$bufferSize bytes")
    }

    /**
     * Start the recording loop
     */
    private fun startRecording() {
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            // Read buffer: Use recommended size for efficient processing
            val readBufferSize = Denoiser.getFrameSizeInSamples(1) * 2 // 2 frames at a time
            val buffer = ShortArray(readBufferSize)

            audioRecord?.startRecording()
            Log.i(TAG, "Recording started")

            while (isRecording) {
                try {
                    val samplesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                    if (samplesRead > 0) {
                        // Extract only the samples that were actually read
                        val audioData = if (samplesRead < buffer.size) {
                            buffer.copyOf(samplesRead)
                        } else {
                            buffer
                        }

                        // Process through denoiser asynchronously
                        // The denoiser will buffer and process complete frames
                        denoiser?.processChunk(audioData)

                    } else if (samplesRead < 0) {
                        onError?.invoke("AudioRecord read error: $samplesRead")
                    }
                } catch (e: Exception) {
                    if (isRecording) {
                        onError?.invoke("Error reading audio: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    /**
     * Handle each denoised audio frame (called from Denoiser callback)
     * This runs on Dispatchers.Default via the denoiser
     */
    private fun handleDenoisedFrame(denoisedAudio: ShortArray, result: DenoiserResult) {
        // Notify about VAD detection
        onVadDetected?.invoke(result.vadProbability, result.isSpeech)

        // Log speech detection
        if (result.isSpeech) {
            Log.d(TAG, "Speech detected! VAD: ${String.format("%.2f", result.vadProbability)}")
        }

        // Convert denoised audio to bytes
        val byteBuffer = ByteBuffer.allocate(denoisedAudio.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        denoisedAudio.forEach { byteBuffer.putShort(it) }
        val audioBytes = byteBuffer.array()

        // Add to chunk buffer
        synchronized(denoisedChunkBuffer) {
            denoisedChunkBuffer.write(audioBytes)
        }

        // Check if it's time to send a chunk
        checkAndSendChunk()
    }

    /**
     * Check if enough time has passed to send accumulated audio
     */
    private fun checkAndSendChunk() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastChunkSentTime >= CHUNK_DURATION_MS) {
            sendCurrentChunk()
        }
    }

    /**
     * Send accumulated denoised audio as base64
     */
    private fun sendCurrentChunk() {
        synchronized(denoisedChunkBuffer) {
            if (denoisedChunkBuffer.size() > 0) {
                try {
                    val pcmData = denoisedChunkBuffer.toByteArray()

                    // Option 1: Send raw PCM as base64 (48kHz, 16-bit, mono)
                    val base64Audio = Base64.encodeToString(pcmData, Base64.NO_WRAP)
                    onVoiceChunkReady?.invoke(base64Audio)

                    // Option 2: If you need WAV format, convert using your PcmToWavConverter
                    // but make sure it handles 48kHz correctly
                    // val wavData = pcmToWavConverter.convertPcmToWav(pcmData, sampleRate = 48000)
                    // val base64Audio = Base64.encodeToString(wavData, Base64.NO_WRAP)
                    // onVoiceChunkReady?.invoke(base64Audio)

                    denoisedChunkBuffer.reset()
                    lastChunkSentTime = System.currentTimeMillis()

                    Log.d(TAG, "Sent denoised audio chunk: ${pcmData.size} bytes")
                } catch (e: Exception) {
                    onError?.invoke("Error processing audio chunk: ${e.message}")
                }
            }
        }
    }

    /**
     * Stop recording and clean up resources
     */
    fun stop() {
        if (!isRecording) return

        Log.i(TAG, "Stopping recording...")
        isRecording = false
        recordingJob?.cancel()

        // Flush any remaining audio in the denoiser's buffer
        CoroutineScope(Dispatchers.IO).launch {
            try {
                denoiser?.flush()
                // Give a moment for flush to complete
                kotlinx.coroutines.delay(50)
                // Send any remaining audio chunk
                sendCurrentChunk()
            } catch (e: Exception) {
                Log.e(TAG, "Error during flush: ${e.message}")
            } finally {
                cleanup()
            }
        }
    }

    /**
     * Clean up all resources
     */
    private fun cleanup() {
        // Release AudioRecord
        audioRecord?.apply {
            try {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioRecord: ${e.message}")
            }
        }
        audioRecord = null

        // Release Denoiser
        denoiser?.destroy()
        denoiser = null

        onRecordingStateChanged?.invoke(false)
        Log.i(TAG, "Cleanup complete")
    }
}

/**
 * Example usage in an Activity or Fragment:
 *
 * ```kotlin
 * class MainActivity : AppCompatActivity() {
 *     private val audioRecorder = DenoiserAudioRecordingExample()
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *
 *         // Set up callbacks
 *         audioRecorder.onVoiceChunkReady = { base64Audio ->
 *             // Send to your API/WebSocket
 *             Log.d("Audio", "Received denoised chunk: ${base64Audio.length} chars")
 *         }
 *
 *         audioRecorder.onVadDetected = { vadProbability, isSpeech ->
 *             // Update UI based on speech detection
 *             runOnUiThread {
 *                 tvVadStatus.text = if (isSpeech) {
 *                     "Speaking (${String.format("%.0f%%", vadProbability * 100)})"
 *                 } else {
 *                     "Silence"
 *                 }
 *             }
 *         }
 *
 *         audioRecorder.onRecordingStateChanged = { isRecording ->
 *             runOnUiThread {
 *                 btnRecord.text = if (isRecording) "Stop" else "Start"
 *             }
 *         }
 *
 *         audioRecorder.onError = { error ->
 *             Log.e("Audio", "Error: $error")
 *             Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
 *         }
 *
 *         // Start/stop recording
 *         btnRecord.setOnClickListener {
 *             if (audioRecorder.isRecording) {
 *                 audioRecorder.stop()
 *             } else {
 *                 audioRecorder.start()
 *             }
 *         }
 *     }
 *
 *     override fun onDestroy() {
 *         super.onDestroy()
 *         audioRecorder.stop()
 *     }
 * }
 * ```
 */
