package com.audx.example.example.domain

/**
 * Sealed class representing the current state of audio recording or playback operations.
 *
 * This state machine ensures that only one operation can be active at a time:
 * the app can either be recording, playing audio, or idle. This prevents conflicts
 * between simultaneous operations and provides clear UI feedback.
 */
sealed class RecordingState {
    /**
     * Idle state - no recording or playback in progress.
     *
     * In this state, the app is ready to start a new recording or play existing audio.
     */
    data object Idle : RecordingState()

    /**
     * Recording state - actively capturing audio from the microphone.
     *
     * When in DENOISED mode, both raw and denoised audio are captured simultaneously,
     * allowing for comparison during playback.
     *
     * @param mode The recording mode (DENOISED captures both raw and processed audio)
     */
    data class Recording(val mode: RecordingMode) : RecordingState()

    /**
     * Playing state - actively playing back previously recorded audio.
     *
     * @param mode The audio mode being played (RAW or DENOISED) for comparison
     */
    data class Playing(val mode: RecordingMode) : RecordingState()
}
