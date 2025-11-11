package com.example.audx.domain

/**
 * Represents the current state of audio recording/playback
 */
sealed class RecordingState {
    /**
     * No recording or playback in progress
     */
    data object Idle : RecordingState()

    /**
     * Currently recording audio
     * @param mode The recording mode (RAW or DENOISED)
     */
    data class Recording(val mode: RecordingMode) : RecordingState()

    /**
     * Currently playing back recorded audio
     * @param mode The recording mode being played
     */
    data class Playing(val mode: RecordingMode) : RecordingState()
}
