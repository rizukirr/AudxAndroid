package com.example.audx

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.audx.AudxDenoiser
import com.example.audx.audio.AudioPlayer
import com.example.audx.audio.AudioRecorder
import com.example.audx.domain.AudioState
import com.example.audx.domain.RecordingMode
import com.example.audx.domain.RecordingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for managing audio recording and playback state
 *
 * Demonstrates best practices for using AudxDenoiser:
 * - Creates denoiser with Builder pattern
 * - Uses processChunk() for streaming audio
 * - Calls flush() before destroy() to process remaining samples
 * - Uses onProcessedAudio callback for denoised audio
 * - Tracks VAD (Voice Activity Detection) results
 */
class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    private val _state = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = _state.asStateFlow()

    private val audioRecorder = AudioRecorder()
    private var audioPlayer: AudioPlayer? = null
    private var denoiser: AudxDenoiser? = null

    private var recordingJob: Job? = null
    private val rawAudioBuffer = mutableListOf<Short>()
    private val denoisedAudioBuffer = mutableListOf<Short>()
    private var frameCount = 0

    /**
     * Update permission state
     */
    fun updatePermission(hasPermission: Boolean) {
        _state.update { it.copy(hasRecordPermission = hasPermission) }
    }

    /**
     * Start recording audio
     *
     * @param mode Recording mode (RAW or DENOISED)
     */
    fun startRecording(mode: RecordingMode) {
        if (_state.value.recordingState !is RecordingState.Idle) {
            Log.w(TAG, "Cannot start recording: already recording or playing")
            return
        }

        viewModelScope.launch {
            try {
                // Clear the appropriate buffer
                when (mode) {
                    RecordingMode.RAW -> {
                        rawAudioBuffer.clear()
                        Log.i(TAG, "Starting RAW recording")
                    }
                    RecordingMode.DENOISED -> {
                        denoisedAudioBuffer.clear()
                        frameCount = 0  // Reset frame counter
                        initializeDenoiser()
                        Log.i(TAG, "Starting DENOISED recording with AudxDenoiser")
                    }
                }

                // Initialize AudioRecorder
                audioRecorder.initialize().getOrThrow()

                // Update state
                _state.update { it.copy(recordingState = RecordingState.Recording(mode)) }

                // Start recording
                recordingJob = viewModelScope.launch {
                    audioRecorder.startRecording()
                        .catch { e ->
                            Log.e(TAG, "Recording error", e)
                            _state.update { it.copy(error = "Recording error: ${e.message}") }
                            stopRecording()
                        }
                        .collect { audioChunk ->
                            when (mode) {
                                RecordingMode.RAW -> {
                                    // Add directly to buffer
                                    rawAudioBuffer.addAll(audioChunk.toList())
                                    updateRawBuffer()
                                }
                                RecordingMode.DENOISED -> {
                                    // Process through denoiser
                                    denoiser?.processChunk(audioChunk)
                                }
                            }
                        }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                _state.update { it.copy(error = "Failed to start recording: ${e.message}") }
                stopRecording()
            }
        }
    }

    /**
     * Initialize AudxDenoiser with callback for processed audio
     *
     * Performance optimization: Updates UI every 10 frames (100ms) instead of every frame (10ms)
     * to avoid overwhelming the system with state updates and buffer copies during long recordings.
     */
    private fun initializeDenoiser() {
        denoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)  // Default threshold
            .enableVadOutput(true)  // Enable VAD in results
            .onProcessedAudio { denoisedAudio, result ->
                // Collect denoised audio (fast operation)
                denoisedAudioBuffer.addAll(denoisedAudio.toList())

                frameCount++

                // Update UI every 10 frames (100ms) instead of every frame (10ms)
                // This prevents performance degradation during long recordings
                if (frameCount % 10 == 0) {
                    // Update VAD info in state
                    _state.update {
                        it.copy(
                            vadProbability = result.vadProbability,
                            isSpeechDetected = result.isSpeech
                        )
                    }

                    // Update buffer display (expensive operation)
                    updateDenoisedBuffer()

                    Log.d(TAG, "Frame $frameCount: VAD: %.2f, Speech: ${result.isSpeech}, Buffer: ${denoisedAudioBuffer.size} samples"
                        .format(result.vadProbability))
                } else {
                    // Still log individual frames at verbose level for debugging
                    Log.v(TAG, "Processed ${result.samplesProcessed} samples, " +
                            "VAD: %.2f, Speech: ${result.isSpeech}".format(result.vadProbability))
                }
            }
            .build()

        Log.i(TAG, "AudxDenoiser initialized with:")
        Log.i(TAG, "  Sample Rate: ${AudxDenoiser.SAMPLE_RATE} Hz")
        Log.i(TAG, "  Channels: ${AudxDenoiser.CHANNELS}")
        Log.i(TAG, "  Frame Size: ${AudxDenoiser.FRAME_SIZE} samples")
        Log.i(TAG, "  VAD Threshold: 0.5")
        Log.i(TAG, "  UI Update Interval: 100ms (every 10 frames)")
    }

    /**
     * Stop recording
     *
     * Important: Calls denoiser.flush() before destroy() to process remaining samples
     */
    fun stopRecording() {
        viewModelScope.launch {
            // First cancel the recording job and release AudioRecord to stop collecting data
            recordingJob?.cancel()
            recordingJob = null
            audioRecorder.release()

            Log.i(TAG, "Recording job cancelled and AudioRecord released")

            // If using denoiser, flush remaining samples
            denoiser?.let { den ->
                Log.i(TAG, "Flushing remaining audio samples...")
                den.flush()
                delay(100)  // Allow callbacks to complete
                den.destroy()
                denoiser = null
                Log.i(TAG, "AudxDenoiser destroyed")
            }

            _state.update {
                it.copy(
                    recordingState = RecordingState.Idle,
                    vadProbability = 0f,
                    isSpeechDetected = false
                )
            }

            Log.i(TAG, "Recording stopped")
        }
    }

    /**
     * Play recorded audio
     */
    fun playAudio(mode: RecordingMode) {
        if (_state.value.recordingState !is RecordingState.Idle) {
            Log.w(TAG, "Cannot play: recording or already playing")
            return
        }

        val audioData = when (mode) {
            RecordingMode.RAW -> {
                if (rawAudioBuffer.isEmpty()) {
                    _state.update { it.copy(error = "No raw audio to play") }
                    return
                }
                rawAudioBuffer.toShortArray()
            }
            RecordingMode.DENOISED -> {
                if (denoisedAudioBuffer.isEmpty()) {
                    _state.update { it.copy(error = "No denoised audio to play") }
                    return
                }
                denoisedAudioBuffer.toShortArray()
            }
        }

        viewModelScope.launch {
            try {
                audioPlayer = AudioPlayer().apply {
                    initialize(audioData).getOrThrow()
                }

                _state.update { it.copy(recordingState = RecordingState.Playing(mode)) }
                Log.i(TAG, "Playing ${mode.name} audio (${audioData.size} samples)")

                audioPlayer?.play(audioData)

                _state.update { it.copy(recordingState = RecordingState.Idle) }
                audioPlayer?.release()
                audioPlayer = null

                Log.i(TAG, "Playback completed")

            } catch (e: Exception) {
                Log.e(TAG, "Playback error", e)
                _state.update {
                    it.copy(
                        error = "Playback error: ${e.message}",
                        recordingState = RecordingState.Idle
                    )
                }
                audioPlayer?.release()
                audioPlayer = null
            }
        }
    }

    /**
     * Stop playback
     */
    fun stopPlayback() {
        audioPlayer?.stop()
        audioPlayer?.release()
        audioPlayer = null
        _state.update { it.copy(recordingState = RecordingState.Idle) }
        Log.i(TAG, "Playback stopped")
    }

    /**
     * Clear all audio buffers
     */
    fun clearBuffers() {
        rawAudioBuffer.clear()
        denoisedAudioBuffer.clear()
        _state.update {
            it.copy(
                rawAudioBuffer = emptyList(),
                denoisedAudioBuffer = emptyList(),
                vadProbability = 0f,
                isSpeechDetected = false,
                error = null
            )
        }
        Log.i(TAG, "All buffers cleared")
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Update raw buffer in state
     */
    private fun updateRawBuffer() {
        _state.update { it.copy(rawAudioBuffer = rawAudioBuffer.toList()) }
    }

    /**
     * Update denoised buffer in state
     */
    private fun updateDenoisedBuffer() {
        _state.update { it.copy(denoisedAudioBuffer = denoisedAudioBuffer.toList()) }
    }

    override fun onCleared() {
        super.onCleared()
        stopRecording()
        stopPlayback()
        audioRecorder.release()
        Log.i(TAG, "ViewModel cleared")
    }
}
