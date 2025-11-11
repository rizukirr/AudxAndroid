package com.android.audx.examples

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.android.audx.AudioFormatValidator
import com.android.audx.Denoiser
import com.android.audx.ValidationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal example: Record 48kHz audio and denoise in real-time
 *
 * This is the simplest way to use the Denoiser with real-time recording.
 * Perfect for quick testing and prototyping.
 */
class SimpleDenoiserExample {

    companion object {
        private const val TAG = "SimpleDenoiser"
    }

    private var audioRecord: AudioRecord? = null
    private var denoiser: Denoiser? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    // Output file for denoised audio (optional)
    private var outputStream: FileOutputStream? = null

    /**
     * Start recording and denoising
     *
     * @param outputFile Optional file to save denoised audio to
     */
    @SuppressLint("MissingPermission")
    fun start(outputFile: File? = null) {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        try {
            // 1. Create denoiser
            denoiser = Denoiser.Builder()
                .vadThreshold(0.5f)
                .onProcessedAudio { denoisedAudio, result ->
                    // This is called for each 10ms frame
                    Log.d(TAG, "VAD: ${String.format("%.2f", result.vadProbability)}, " +
                            "Speech: ${result.isSpeech}")

                    // Save to file if specified
                    outputFile?.let { saveDenoisedAudio(denoisedAudio) }
                }
                .build()

            // 2. Create AudioRecord for 48kHz
            val bufferSize = AudioRecord.getMinBufferSize(
                Denoiser.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                Denoiser.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            // 3. Prepare output file
            outputFile?.let {
                outputStream = FileOutputStream(it)
                Log.i(TAG, "Saving denoised audio to: ${it.absolutePath}")
            }

            // 4. Start recording
            isRecording = true
            audioRecord?.startRecording()

            // 5. Start processing loop
            startProcessing()

            Log.i(TAG, "Recording started")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}", e)
            cleanup()
        }
    }

    /**
     * Audio processing loop
     */
    private fun startProcessing() {
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            // Buffer size: 2 frames (20ms) for smooth processing
            val bufferSize = Denoiser.FRAME_SIZE * 2
            val buffer = ShortArray(bufferSize)

            while (isRecording) {
                try {
                    // Read audio from microphone
                    val samplesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                    if (samplesRead > 0) {
                        // Feed to denoiser (validation happens automatically inside processChunk)
                        val audioData = buffer.copyOf(samplesRead)
                        denoiser?.processChunk(audioData)
                    }

                } catch (e: IllegalArgumentException) {
                    // Validation error from denoiser
                    Log.e(TAG, "Audio validation error: ${e.message}")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing audio: ${e.message}")
                    break
                }
            }
        }
    }

    /**
     * Save denoised audio to file
     */
    private fun saveDenoisedAudio(audio: ShortArray) {
        try {
            // Convert shorts to bytes (little endian)
            val buffer = ByteBuffer.allocate(audio.size * 2)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            audio.forEach { buffer.putShort(it) }

            outputStream?.write(buffer.array())
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio: ${e.message}")
        }
    }

    /**
     * Stop recording and cleanup
     */
    fun stop() {
        if (!isRecording) return

        isRecording = false
        recordingJob?.cancel()

        // Flush any remaining audio
        CoroutineScope(Dispatchers.IO).launch {
            try {
                denoiser?.flush()
                kotlinx.coroutines.delay(50)
            } finally {
                cleanup()
            }
        }
    }

    /**
     * Cleanup all resources
     */
    private fun cleanup() {
        outputStream?.close()
        outputStream = null

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

        denoiser?.destroy()
        denoiser = null

        Log.i(TAG, "Stopped and cleaned up")
    }
}

/**
 * Example usage:
 *
 * ```kotlin
 * // In your Activity/Fragment
 *
 * val denoiser = SimpleDenoiserExample()
 *
 * // Start recording (audio will be denoised in real-time)
 * btnStart.setOnClickListener {
 *     denoiser.start()
 * }
 *
 * // Stop recording
 * btnStop.setOnClickListener {
 *     denoiser.stop()
 * }
 *
 * // Save denoised audio to file
 * val outputFile = File(context.cacheDir, "denoised_${System.currentTimeMillis()}.pcm")
 * denoiser.start(outputFile)
 * // ... record ...
 * denoiser.stop()
 * // outputFile now contains 48kHz mono PCM audio
 * ```
 *
 * To convert the PCM file to WAV (for playback):
 * ```bash
 * ffmpeg -f s16le -ar 48000 -ac 1 -i denoised.pcm denoised.wav
 * ```
 */
