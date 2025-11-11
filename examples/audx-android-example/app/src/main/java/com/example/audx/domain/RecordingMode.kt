package com.example.audx.domain

/**
 * Recording mode for audio capture
 */
enum class RecordingMode {
    /**
     * Raw audio without any processing
     */
    RAW,

    /**
     * Audio processed through AudxDenoiser for noise reduction
     */
    DENOISED
}
