package com.example.audx.domain

/**
 * UI state for audio recording and playback
 */
data class AudioState(
    /**
     * Current recording/playback state
     */
    val recordingState: RecordingState = RecordingState.Idle,

    /**
     * Raw audio buffer (without denoising)
     */
    val rawAudioBuffer: List<Short> = emptyList(),

    /**
     * Denoised audio buffer (processed by AudxDenoiser)
     */
    val denoisedAudioBuffer: List<Short> = emptyList(),

    /**
     * Current VAD (Voice Activity Detection) probability from denoiser (0.0-1.0)
     * Only available when recording with DENOISED mode
     */
    val vadProbability: Float = 0f,

    /**
     * Whether speech is currently detected (VAD above threshold)
     */
    val isSpeechDetected: Boolean = false,

    /**
     * Error message if any operation failed
     */
    val error: String? = null,

    /**
     * Whether RECORD_AUDIO permission is granted
     */
    val hasRecordPermission: Boolean = false
) {
    /**
     * Calculate duration in milliseconds for raw audio
     * Uses AudxDenoiser.SAMPLE_RATE constant (48000 Hz)
     */
    val rawDurationMs: Int
        get() = if (rawAudioBuffer.isEmpty()) 0
        else (rawAudioBuffer.size / 48.0).toInt() // 48 samples = 1ms at 48kHz

    /**
     * Calculate duration in milliseconds for denoised audio
     * Uses AudxDenoiser.SAMPLE_RATE constant (48000 Hz)
     */
    val denoisedDurationMs: Int
        get() = if (denoisedAudioBuffer.isEmpty()) 0
        else (denoisedAudioBuffer.size / 48.0).toInt() // 48 samples = 1ms at 48kHz

    /**
     * Calculate number of frames in raw audio
     * Uses AudxDenoiser.FRAME_SIZE constant (480 samples)
     */
    val rawFrameCount: Int
        get() = rawAudioBuffer.size / 480

    /**
     * Calculate number of frames in denoised audio
     * Uses AudxDenoiser.FRAME_SIZE constant (480 samples)
     */
    val denoisedFrameCount: Int
        get() = denoisedAudioBuffer.size / 480
}
