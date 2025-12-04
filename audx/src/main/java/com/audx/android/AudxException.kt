package com.audx.android

/**
 * Base exception for all Audx-related errors.
 *
 * This is a sealed class with the following subclasses:
 * - [AudxProcessingException]: Audio processing failures
 * - [AudxInitializationException]: Instance creation failures
 */
sealed class AudxException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when audio processing fails in the native layer.
 *
 * Common causes:
 * - Output buffer smaller than input buffer
 * - Corrupted or invalid audio data
 * - Native state corruption
 * - Processing on a closed Audx instance
 */
class AudxProcessingException(message: String, cause: Throwable? = null) : AudxException(message, cause)

/**
 * Thrown when Audx instance creation fails.
 *
 * Common causes:
 * - Invalid configuration (negative sample rate, invalid quality range)
 * - Missing or incompatible native library (audx-android.so)
 * - Insufficient system resources
 * - Failed native memory allocation
 */
class AudxInitializationException(message: String, cause: Throwable? = null) : AudxException(message, cause)
