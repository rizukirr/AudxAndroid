package com.audx.example.example

import android.util.Log
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.audx.android.Audx
import com.audx.example.example.audio.AudioPlayer
import com.audx.example.example.audio.AudioRecorder
import com.audx.example.example.domain.AudioState
import com.audx.example.example.domain.RecordingMode
import com.audx.example.example.domain.RecordingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel managing audio recording, denoising, and playback functionality.
 *
 * This ViewModel demonstrates the usage of the Audx library for real-time audio denoising.
 * During recording, it simultaneously captures both raw audio and processes it through the
 * Audx denoiser, allowing users to compare the original and denoised audio during playback.
 *
 * Key features:
 * - Real-time audio recording with simultaneous raw and denoised capture
 * - Voice Activity Detection (VAD) monitoring during denoising
 * - Playback of both raw and denoised audio for comparison
 * - Proper resource management with Audx instance reuse across recording sessions
 *
 * The Audx denoiser instance is created once and reused for multiple recording sessions,
 * only being released when the ViewModel is cleared.
 */
@Stable
class MainViewModel : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
        /** Sample rate used for all audio recording and playback (16kHz) */
        private const val SAMPLE_RATE = 16000
    }

    /** Exposed state flow containing current audio recording/playback state */
    private val _state = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = _state.asStateFlow()

    /** Audio recorder for capturing microphone input */
    private val audioRecorder = AudioRecorder()

    /** Audio player for playback, created on-demand */
    private var audioPlayer: AudioPlayer? = null

    /** Audx denoiser instance, reused across multiple recording sessions */
    private val denoiser: Audx = Audx.Builder()
        .inputRate(SAMPLE_RATE)
        .resampleQuality(4)
        .build()

    /** Coroutine job managing the active recording session */
    private var recordingJob: Job? = null

    /** Buffer storing raw audio samples captured during recording */
    private val rawAudioBuffer = mutableListOf<Short>()

    /** Buffer storing denoised audio samples processed by Audx */
    private val denoisedAudioBuffer = mutableListOf<Short>()

    /** Debug counter tracking total raw audio samples captured */
    private var totalRawSamples = 0

    /** Debug counter tracking total denoised audio samples produced */
    private var totalDenoisedSamples = 0

    /** Debug counter tracking number of audio frames processed */
    private var frameCount = 0

    /**
     * Updates the microphone permission status in the UI state.
     *
     * @param hasPermission true if microphone permission is granted, false otherwise
     */
    fun updatePermission(hasPermission: Boolean) {
        _state.update { it.copy(hasRecordPermission = hasPermission) }
    }

    /**
     * Starts audio recording session with real-time denoising.
     *
     * When recording with DENOISED mode (the only mode available in UI), this function:
     * 1. Clears previous audio buffers
     * 2. Initializes the AudioRecorder
     * 3. Continuously captures audio frames from the microphone
     * 4. Processes each frame through Audx denoiser
     * 5. Stores both raw and denoised audio for later playback comparison
     * 6. Updates VAD (Voice Activity Detection) probability in real-time
     *
     * The Audx denoiser is reused across recording sessions - it's not recreated each time.
     *
     * @param mode The recording mode (DENOISED captures both raw and denoised simultaneously)
     */
    fun startRecording(mode: RecordingMode) {
        if (_state.value.recordingState !is RecordingState.Idle) {
            Log.w(TAG, "Cannot start recording: already recording or playing")
            return
        }

        viewModelScope.launch {
            try {
                // Clear buffers for the new recording session
                clearBuffers()

                totalRawSamples = 0
                totalDenoisedSamples = 0
                frameCount = 0

                audioRecorder.initialize().getOrThrow()
                _state.update { it.copy(recordingState = RecordingState.Recording(mode)) }

                recordingJob = viewModelScope.launch {
                    audioRecorder.startRecording()
                        .collect { audioChunk ->
                            frameCount++

                            if (mode == RecordingMode.DENOISED) {
                                // Output buffer must match input size (native outputs at input rate)
                                val outputBuffer = ShortArray(audioChunk.size)
                                denoiser.process(audioChunk, outputBuffer) { vad ->
                                    _state.update {
                                        it.copy(
                                            vadProbability = vad,
                                            isSpeechDetected = vad > 0.5f
                                        )
                                    }
                                }
                                denoisedAudioBuffer.addAll(outputBuffer.toList())
                                updateDenoisedBuffer()
                            }

                            // RAW mode: save raw audio without skipping
                            rawAudioBuffer.addAll(audioChunk.toList())
                            updateRawBuffer()
                            totalRawSamples += audioChunk.size

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
     * Stops the current recording session and releases the AudioRecorder.
     *
     * The Audx denoiser is kept alive for reuse in subsequent recording sessions.
     * It will only be released when the ViewModel is cleared.
     */
    fun stopRecording() {
        viewModelScope.launch {
            recordingJob?.cancel()
            recordingJob = null
            audioRecorder.release()

            // Keep denoiser alive for reuse across multiple recording sessions
            // It will be closed only in onCleared()

            _state.update {
                it.copy(
                    recordingState = RecordingState.Idle,
                    vadProbability = 0f,
                    isSpeechDetected = false
                )
            }
        }
    }

    /**
     * Plays back recorded audio based on the specified mode.
     *
     * @param mode RAW to play the original recorded audio, DENOISED to play the processed audio
     */
    fun playAudio(mode: RecordingMode) {
        if (_state.value.recordingState !is RecordingState.Idle) {
            Log.w(TAG, "Cannot play: recording or already playing")
            return
        }

        val audioData = when (mode) {
            RecordingMode.RAW -> rawAudioBuffer.toShortArray()
            RecordingMode.DENOISED -> denoisedAudioBuffer.toShortArray()
        }

        if (audioData.isEmpty()) {
            val errorMessage = when (mode) {
                RecordingMode.RAW -> "No raw audio recorded to play"
                RecordingMode.DENOISED -> "No denoised audio recorded to play"
            }
            _state.update { it.copy(error = errorMessage) }
            return
        }

        // Debug logging with buffer verification
        Log.d(TAG, "Playing $mode mode: ${audioData.size} samples, " +
                "duration = ${audioData.size.toFloat() / SAMPLE_RATE}s")
        Log.d(TAG, "Buffer sizes: raw=${rawAudioBuffer.size}, denoised=${denoisedAudioBuffer.size}")

        if (rawAudioBuffer.size != denoisedAudioBuffer.size) {
            val difference = rawAudioBuffer.size - denoisedAudioBuffer.size
            val percentDiff = (difference.toFloat() / rawAudioBuffer.size) * 100
            Log.e(TAG, "BUFFER SIZE MISMATCH! Difference: $difference samples (${percentDiff}%)")
        } else {
            Log.i(TAG, "Buffer sizes match perfectly")
        }

        viewModelScope.launch {
            try {
                audioPlayer = AudioPlayer().apply {
                    // Both buffers are at 16kHz
                    initialize(SAMPLE_RATE).getOrThrow()
                }

                _state.update { it.copy(recordingState = RecordingState.Playing(mode)) }

                audioPlayer?.play(audioData)

                _state.update { it.copy(recordingState = RecordingState.Idle) }
                audioPlayer?.release()
                audioPlayer = null
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
     * Stops the currently playing audio and releases the AudioPlayer.
     */
    fun stopPlayback() {
        audioPlayer?.stop()
        audioPlayer?.release()
        audioPlayer = null
        _state.update { it.copy(recordingState = RecordingState.Idle) }
    }

    /**
     * Clears both raw and denoised audio buffers and resets VAD state.
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
    }

    /**
     * Clears the current error message from the UI state.
     */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * Updates the UI state with the current raw audio buffer contents.
     */
    private fun updateRawBuffer() {
        _state.update { it.copy(rawAudioBuffer = rawAudioBuffer.toList()) }
    }

    /**
     * Updates the UI state with the current denoised audio buffer contents.
     */
    private fun updateDenoisedBuffer() {
        _state.update { it.copy(denoisedAudioBuffer = denoisedAudioBuffer.toList()) }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.release()
        audioPlayer?.release()
        denoiser.close()
    }
}
