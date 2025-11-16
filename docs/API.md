# API Reference

This document provides a complete API reference for the Audx Android audio denoising library. The library is designed for real-time, low-latency noise suppression using a streaming pipeline.

## Table of Contents

- [Overview](#overview)
  - [Core Concepts](#core-concepts)
  - [Lifecycle](#lifecycle)
- [Example Usage](#example-usage)
- [API Details](#api-details)
  - [AudxDenoiser.Builder](#audxdenoiserbuilder)
  - [AudxDenoiser (Instance)](#audxdenoiser-instance)
  - [DenoiseStreamResult](#denoisestreamresult)
  - [DenoiserStatsResult](#denoiserstatsresult)
  - [Type Aliases](#type-aliases)
  - [Companion Object Constants](#companion-object-constants)

---

## Overview

`AudxDenoiser` is the main class for real-time audio denoising. It is built around a streaming model, allowing you to process audio in chunks of any size. The denoiser manages an internal buffer and a dedicated worker thread to ensure that audio is processed efficiently without blocking the calling thread.

### Core Concepts

- **Streaming Pipeline**: You feed the denoiser audio chunks as you receive them (e.g., from `AudioRecord`). The denoiser buffers this audio internally and processes it in the fixed-size frames required by the underlying RNNoise engine.
- **Asynchronous Processing**: All audio processing happens on a background thread. Denoised audio is delivered back to you via a callback.
- **Arbitrary Sample Rates**: The denoiser can accept any standard sample rate (e.g., 8000, 16000, 44100 Hz). It automatically resamples the audio to the required 48 kHz for processing and then resamples it back to the original input rate.
- **VAD (Voice Activity Detection)**: For each processed chunk, the denoiser provides a VAD score indicating the probability of speech.
- **Performance Statistics**: The denoiser can collect detailed performance metrics, suchs as processing time and speech detection percentages.

### Lifecycle

The denoiser has a clear lifecycle that must be followed to ensure all audio is processed and resources are released correctly.

1.  **Build**: Create an instance using `AudxDenoiser.Builder`.
2.  **Process**: Call `processAudio()` repeatedly with incoming audio chunks.
3.  **Flush (Optional)**: Call `flush()` to explicitly process any audio remaining in the internal buffer and trigger the `onCollectStats` callback (if enabled). This is useful for getting intermediate results or statistics without closing the denoiser.
4.  **Close**: Call `close()` to stop the worker thread and release all native resources. `close()` implicitly performs a final flush to ensure all buffered audio is processed before termination. The instance cannot be used after this.

```
[Create with Builder] -> [processAudio()] -> [processAudio()] -> ... -> [flush() (optional)] -> [close()]
```

---

## Example Usage

Here is a complete example of integrating `AudxDenoiser` with `AudioRecord` and collecting statistics.

```kotlin
class MyViewModel : ViewModel() {
    private var denoiser: AudxDenoiser? = null
    private var recordingJob: Job? = null
    private val denoisedAudioBuffer = mutableListOf<Short>()

    fun startDenoising() {
        // 1. Build the denoiser
        denoiser = AudxDenoiser.Builder()
            .inputSampleRate(16000) // The sample rate of our source
            .collectStatistics(true) // Enable statistics collection
            .onProcessedAudio { result ->
                // This callback is on a background thread
                denoisedAudioBuffer.addAll(result.audio.toList())
                Log.d("VAD", "Speech probability: ${result.vadProbability}")
            }
            .onCollectStats { stats ->
                // This callback is on a background thread, typically after flush() or close()
                Log.d("STATS", "Average processing time: ${stats.processTimeAvg} ms")
            }
            .build()

        // Start recording and processing...
        val audioRecord = AudioRecord(...)
        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            val buffer = ShortArray(160 * 2) // 20ms buffer at 16kHz
            while (isActive) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    // 2. Process audio chunks as they arrive
                    denoiser?.processAudio(buffer.copyOf(read))
                }
            }
        }
    }

    fun stopDenoising() {
        recordingJob?.cancel()
        recordingJob = null

        // 3. Flush the remaining audio and receive final stats (optional, close() will also flush)
        denoiser?.flush()

        // 4. Close the denoiser to release resources
        denoiser?.close()
        denoiser = null

        // Now the denoisedAudioBuffer contains the full, clean recording
    }
}
```

---

## API Details

### AudxDenoiser.Builder

Use the builder to configure and create an `AudxDenoiser` instance.

| Method                    | Description                                                                                             | Default Value      |
| ------------------------- | ------------------------------------------------------------------------------------------------------- | ------------------ |
| `.onProcessedAudio(callback)` | **Required.** Sets the callback for receiving `DenoiseStreamResult` objects.                      | `null`             |
| `.inputSampleRate(Int)`   | The sample rate of your source audio (e.g., 16000).                                                     | `48000`            |
| `.vadThreshold(Float)`    | VAD sensitivity (0.0-1.0). Higher is stricter.                                                          | `0.5`              |
| `.collectStatistics(Boolean)` | Enables collection of performance and VAD statistics.                                                 | `false`            |
| `.onCollectStats(callback)` | **Required if `collectStatistics(true)`.** Sets the callback for receiving `DenoiserStatsResult`. Invoked when `flush()` or `close()` is called. | `null`             |
| `.resampleQuality(Int)`   | Sets the quality for audio resampling (0-10). Use constants like `AudxDenoiser.RESAMPLER_QUALITY_VOIP`.  | `4`                |
| `.modelPath(String?)`     | The absolute file path to a custom `.rnnn` model file.                                                  | `null`             |
| `.workerThreadName(String)` | A custom name for the internal processing thread.                                                     | `"audx-worker"`    |
| `.build()`                | Constructs the `AudxDenoiser` instance. Throws `IllegalArgumentException` if callback is missing.       | -                  |

### AudxDenoiser (Instance)

The public methods available on an `AudxDenoiser` instance.

| Method                        | Description                                                                                             | Thread Safety |
| ----------------------------- | ------------------------------------------------------------------------------------------------------- | ------------- |
| `processAudio(ShortArray)`    | Asynchronously submits an audio chunk for processing. Non-blocking.                                     | Thread-safe   |
| `flush()`                     | Synchronously processes any remaining audio in the buffer. Blocks until complete. Invokes `onCollectStats` callback if enabled, then automatically resets statistics. | Thread-safe   |
| `close()`                     | Flushes remaining audio, releases all resources, and stops the worker thread. Blocks until complete. The instance is unusable after this. | Thread-safe   |
| `cleanStats()`                | Synchronously resets all collected statistics to zero. Blocks until complete. Note: `flush()` and `close()` automatically reset statistics after invoking the callback, so manual use is rarely needed. | Thread-safe   |
| `setCallback(callback)`       | Updates the `onProcessedAudio` callback at runtime.                                                     | Thread-safe   |
| `setStatsCallback(callback)`  | Updates the `onCollectStats` callback at runtime.                                                       | Thread-safe   |

### DenoiseStreamResult

This data class is the container for the output of the denoiser for each processed chunk.

| Property           | Type        | Description                                                              |
| ------------------ | ----------- | ------------------------------------------------------------------------ |
| `audio`            | `ShortArray`| The chunk of denoised audio samples.                                     |
| `vadProbability`   | `Float`     | The VAD probability (0.0 to 1.0) of the last 10ms frame in this chunk.   |
| `isSpeech`         | `Boolean`   | `true` if `vadProbability` is above the configured `vadThreshold`.         |

### DenoiserStatsResult

This data class holds runtime performance metrics for the denoiser instance.

| Property                | Type    | Description                                                              |
| ----------------------- | ------- | ------------------------------------------------------------------------ |
| `frameProcessed`        | `Int`   | The total number of 10ms frames processed.                               |
| `speechDetectedPercent` | `Float` | The percentage of processed frames where speech was detected.            |
| `vadScoreAvg`           | `Float` | The average VAD score across all processed frames.                       |
| `vadScoreMin`           | `Float` | The minimum VAD score observed.                                          |
| `vadScoreMax`           | `Float` | The maximum VAD score observed.                                          |
| `processTimeTotal`      | `Float` | The total CPU time in milliseconds spent on denoising.                   |
| `processTimeAvg`        | `Float` | The average processing time in milliseconds per frame.                   |
| `processTimeLast`       | `Float` | The processing time in milliseconds of the most recent frame.            |

### Type Aliases

The library defines type aliases for its callback functions.

| Alias                    | Signature                               | Description                               |
| ------------------------ | --------------------------------------- | ----------------------------------------- |
| `ProcessedAudioCallback` | `(DenoiseStreamResult) -> Unit`         | For receiving denoised audio chunks.      |
| `GetStatsCallback`       | `(DenoiserStatsResult) -> Unit`         | For receiving performance statistics.     |

### Companion Object Constants

Static constants that reflect the properties of the underlying native library.

| Constant      | Type  | Value   | Description                                           |
| ------------- | ----- | ------- | ----------------------------------------------------- |
| `SAMPLE_RATE` | `Int` | `48000` | The internal processing sample rate of the model (48 kHz). |
| `CHANNELS`    | `Int` | `1`     | The number of channels supported (mono).              |
| `BIT_DEPTH`   | `Int` | `16`    | The audio bit depth supported (16-bit PCM).           |
| `FRAME_SIZE`  | `Int` | `480`   | The internal frame size in samples (10ms at 48 kHz).  |
| `RESAMPLER_QUALITY_MAX` | `Int` | `10` | Maximum allowed resampler quality level. Higher quality increases CPU usage. |
| `RESAMPLER_QUALITY_MIN` | `Int` | `0` | Minimum allowed resampler quality level. Fastest but offers lowest audio accuracy. |
| `RESAMPLER_QUALITY_DEFAULT` | `Int` | `4` | Default resampler quality level. Balanced between performance and quality. |
| `RESAMPLER_QUALITY_VOIP` | `Int` | `3` | Recommended resampler quality for VoIP or low-latency scenarios. |
