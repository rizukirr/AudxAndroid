package com.audx.example.example.domain

/**
 * Audio mode identifier for playback and recording state tracking.
 *
 * During recording with DENOISED mode, both raw and denoised audio are captured simultaneously.
 * This enum is used to differentiate between raw and denoised audio during playback.
 */
enum class RecordingMode {
    /**
     * Raw audio without any processing - used for playback comparison
     */
    RAW,

    /**
     * Audio processed through Audx denoiser for noise reduction.
     * When recording in this mode, both raw and denoised audio are captured.
     */
    DENOISED
}
