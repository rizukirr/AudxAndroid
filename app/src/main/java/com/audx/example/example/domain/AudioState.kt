package com.audx.example.example.domain


/**
 * Immutable UI state for audio recording and playback functionality.
 *
 * This data class holds all state information for the Audx example app, including:
 * - Recording/playback state machine
 * - Audio buffers for both raw and denoised audio
 * - Real-time Voice Activity Detection metrics
 * - Error messages and permission status
 *
 * The state is managed by the MainViewModel and exposed to the UI as a StateFlow,
 * following the unidirectional data flow pattern.
 */
data class AudioState(
    /**
     * Current recording or playback state (Idle, Recording, or Playing).
     */
    val recordingState: RecordingState = RecordingState.Idle,

    /**
     * Raw audio buffer captured from the microphone without processing.
     *
     * Contains PCM16 samples at 16kHz. This buffer is populated during recording
     * to allow comparison with the denoised version.
     */
    val rawAudioBuffer: List<Short> = emptyList(),

    /**
     * Denoised audio buffer processed by the Audx library.
     *
     * Contains PCM16 samples at 16kHz, matching the input rate. Each sample has been
     * processed by the Audx denoiser to reduce background noise while preserving voice.
     */
    val denoisedAudioBuffer: List<Short> = emptyList(),

    /**
     * Current Voice Activity Detection probability (0.0 to 1.0).
     *
     * This value is computed by the Audx denoiser in real-time during recording.
     * Values closer to 1.0 indicate higher confidence that voice is present.
     * Only updated when recording with DENOISED mode.
     */
    val vadProbability: Float = 0f,

    /**
     * Whether speech is currently detected based on VAD threshold.
     *
     * True when vadProbability > 0.5, indicating likely presence of voice.
     */
    val isSpeechDetected: Boolean = false,

    /**
     * Error message to display to the user, if any operation failed.
     */
    val error: String? = null,

    /**
     * Whether the RECORD_AUDIO permission has been granted by the user.
     *
     * Recording is disabled when this is false.
     */
    val hasRecordPermission: Boolean = false
) {
    /**
     * Calculates the duration of raw audio in milliseconds.
     *
     * Uses 16kHz sample rate: 16 samples = 1ms.
     * Returns 0 if the buffer is empty.
     */
    val rawDurationMs: Int
        get() = if (rawAudioBuffer.isEmpty()) 0
        else (rawAudioBuffer.size / 16.0).toInt() // 16 samples = 1ms at 16kHz

    /**
     * Calculates the duration of denoised audio in milliseconds.
     *
     * Uses 16kHz sample rate: 16 samples = 1ms.
     * Returns 0 if the buffer is empty.
     */
    val denoisedDurationMs: Int
        get() = if (denoisedAudioBuffer.isEmpty()) 0
        else (denoisedAudioBuffer.size / 16.0).toInt() // 16 samples = 1ms at 16kHz

    /**
     * Calculates the number of audio frames in the raw buffer.
     *
     * Uses 160 samples per frame at 16kHz (10ms frames).
     */
    val rawFrameCount: Int
        get() = rawAudioBuffer.size / 160

    /**
     * Calculates the number of audio frames in the denoised buffer.
     *
     * Uses 160 samples per frame at 16kHz (10ms frames).
     */
    val denoisedFrameCount: Int
        get() = denoisedAudioBuffer.size / 160
}
