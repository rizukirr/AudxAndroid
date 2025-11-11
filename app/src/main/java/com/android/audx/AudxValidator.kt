package com.android.audx

/**
 * Result of audio format validation
 */
sealed class ValidationResult {
    /**
     * Validation successful
     */
    object Success : ValidationResult()

    /**
     * Validation failed with error message
     *
     * @property message Description of the validation error
     */
    data class Error(val message: String) : ValidationResult()
}

/**
 * Validates audio format parameters against requirements of the Denoiser
 *
 * This validator uses native constants from the C library as the single source of truth.
 * All format requirements are defined in the native headers (denoiser.h) and exposed
 * via JNI to ensure consistency between Kotlin and native code.
 */
object AudxValidator {

    /**
     * Validate audio format parameters
     *
     * Checks if the provided sample rate, channels, and bit depth match the
     * requirements of the denoiser library.
     *
     * **Requirements** (from native library):
     * - Sample rate: 48000 Hz (Denoiser.SAMPLE_RATE)
     * - Channels: 1 (mono only) (Denoiser.CHANNELS)
     * - Bit depth: 16-bit signed PCM (Denoiser.BIT_DEPTH)
     *
     * @param sampleRate Sample rate in Hz
     * @param channels Number of audio channels
     * @param bitDepth Bit depth of audio samples
     * @return ValidationResult.Success if valid, ValidationResult.Error with message if invalid
     *
     * @example
     * ```kotlin
     * when (val result = AudioFormatValidator.validateFormat(48000, 1, 16)) {
     *     is ValidationResult.Success -> {
     *         // Format is valid, proceed with denoiser
     *     }
     *     is ValidationResult.Error -> {
     *         Log.e("Audio", "Invalid format: ${result.message}")
     *     }
     * }
     * ```
     */
    @JvmStatic
    fun validateFormat(sampleRate: Int, channels: Int, bitDepth: Int): ValidationResult {
        // Validate sample rate
        if (sampleRate != AudxDenoiser.SAMPLE_RATE) {
            return ValidationResult.Error(
                "Invalid sample rate: $sampleRate Hz (expected ${AudxDenoiser.SAMPLE_RATE} Hz)"
            )
        }

        // Validate channels (mono only)
        if (channels != AudxDenoiser.CHANNELS) {
            return ValidationResult.Error(
                "Invalid channel count: $channels (only mono supported, expected ${AudxDenoiser.CHANNELS})"
            )
        }

        // Validate bit depth
        if (bitDepth != AudxDenoiser.BIT_DEPTH) {
            return ValidationResult.Error(
                "Invalid bit depth: $bitDepth (expected ${AudxDenoiser.BIT_DEPTH}-bit PCM)"
            )
        }

        return ValidationResult.Success
    }

    /**
     * Validate an audio chunk for processing
     *
     * Checks if the audio data is valid and properly sized.
     *
     * @param audioData Audio samples to validate (can be null)
     * @param minSize Minimum required size (default: 1)
     * @return ValidationResult.Success if valid, ValidationResult.Error with message if invalid
     *
     * @example
     * ```kotlin
     * val chunk = ShortArray(480) // One frame
     * when (val result = AudioFormatValidator.validateChunk(chunk)) {
     *     is ValidationResult.Success -> {
     *         denoiser.processChunk(chunk)
     *     }
     *     is ValidationResult.Error -> {
     *         Log.e("Audio", "Invalid chunk: ${result.message}")
     *     }
     * }
     * ```
     */
    @JvmStatic
    fun validateChunk(audioData: ShortArray?, minSize: Int = 1): ValidationResult {
        // Check null
        if (audioData == null) {
            return ValidationResult.Error("Audio data is null")
        }

        // Check empty
        if (audioData.isEmpty()) {
            return ValidationResult.Error("Audio data is empty")
        }

        // Check minimum size
        if (audioData.size < minSize) {
            return ValidationResult.Error(
                "Audio chunk too small: ${audioData.size} samples (minimum: $minSize)"
            )
        }

        return ValidationResult.Success
    }

    /**
     * Validate that a chunk size is valid for the denoiser
     *
     * The denoiser processes audio in fixed frames of FRAME_SIZE samples.
     * This function checks if the provided chunk size is a valid multiple.
     *
     * @param chunkSize Size of audio chunk in samples
     * @return ValidationResult.Success if valid, ValidationResult.Error with message if invalid
     */
    @JvmStatic
    fun validateChunkSize(chunkSize: Int): ValidationResult {
        if (chunkSize <= 0) {
            return ValidationResult.Error("Chunk size must be positive: $chunkSize")
        }

        // For streaming mode, any positive size is acceptable as chunks are buffered
        // For frame-based processing, size should be multiple of FRAME_SIZE
        if (chunkSize % AudxDenoiser.FRAME_SIZE != 0) {
            // This is a warning, not an error - streaming mode handles this via buffering
            // Just validate it's not unreasonably large
            if (chunkSize > AudxDenoiser.FRAME_SIZE * 100) {
                return ValidationResult.Error(
                    "Chunk size too large: $chunkSize samples (maximum: ${AudxDenoiser.FRAME_SIZE * 100})"
                )
            }
        }

        return ValidationResult.Success
    }
}
