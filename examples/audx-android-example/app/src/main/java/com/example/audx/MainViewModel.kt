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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private var currentSampleRate: Int = 16000 // Centralized sample rate

    fun updatePermission(hasPermission: Boolean) {
        _state.update { it.copy(hasRecordPermission = hasPermission) }
    }

    fun startRecording(mode: RecordingMode) {
        if (_state.value.recordingState !is RecordingState.Idle) {
            Log.w(TAG, "Cannot start recording: already recording or playing")
            return
        }

        viewModelScope.launch {
            try {
                when (mode) {
                    RecordingMode.RAW -> {
                        rawAudioBuffer.clear()
                        Log.i(TAG, "Starting RAW recording")
                    }
                    RecordingMode.DENOISED -> {
                        denoisedAudioBuffer.clear()
                        frameCount = 0
                        initializeDenoiser()
                        Log.i(TAG, "Starting DENOISED recording with AudxDenoiser")
                    }
                }

                audioRecorder.initialize().getOrThrow()
                _state.update { it.copy(recordingState = RecordingState.Recording(mode)) }

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
                                    rawAudioBuffer.addAll(audioChunk.toList())
                                    updateRawBuffer()
                                }
                                RecordingMode.DENOISED -> {
                                    denoiser?.processAudio(audioChunk)
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

    private fun initializeDenoiser() {
        denoiser = AudxDenoiser.Builder()
            .inputSampleRate(currentSampleRate) // Use the centralized sample rate
            .vadThreshold(0.5f)
            .onProcessedAudio { result ->
                val (denoisedAudio, vadProbability, isSpeech) = result

                denoisedAudioBuffer.addAll(denoisedAudio.toList())
                frameCount++

                // Update UI every 10 frames (100ms) to avoid performance issues
                if (frameCount % 10 == 0) {
                    _state.update {
                        it.copy(
                            vadProbability = vadProbability,
                            isSpeechDetected = isSpeech
                        )
                    }
                    updateDenoisedBuffer()
                }
            }
            .build()
    }

    fun stopRecording() {
        viewModelScope.launch {
            recordingJob?.cancel()
            recordingJob = null
            audioRecorder.release()

            // Flush any remaining audio from the denoiser's internal buffers
            denoiser?.flush()
            denoiser?.close()
            denoiser = null

            _state.update {
                it.copy(
                    recordingState = RecordingState.Idle,
                    vadProbability = 0f,
                    isSpeechDetected = false
                )
            }
        }
    }

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
            _state.update { it.copy(error = "No audio to play") }
            return
        }

        viewModelScope.launch {
            try {
                audioPlayer = AudioPlayer().apply {
                    // Pass the correct sample rate to the player
                    initialize(currentSampleRate, audioData).getOrThrow()
                }

                _state.update { it.copy(recordingState = RecordingState.Playing(mode)) }
                Log.i(TAG, "Playing ${mode.name} audio (${audioData.size} samples) at $currentSampleRate Hz")

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

    fun stopPlayback() {
        audioPlayer?.stop()
        audioPlayer?.release()
        audioPlayer = null
        _state.update { it.copy(recordingState = RecordingState.Idle) }
        Log.i(TAG, "Playback stopped")
    }

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

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun updateRawBuffer() {
        _state.update { it.copy(rawAudioBuffer = rawAudioBuffer.toList()) }
    }

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