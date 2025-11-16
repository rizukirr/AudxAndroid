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
  - [Companion Object Constants](#companion-object-constants)

---

## Overview

`AudxDenoiser` is the main class for real-time audio denoising. It is built around a streaming model, allowing you to process audio in chunks of any size. The denoiser manages an internal buffer and a dedicated worker thread to ensure that audio is processed efficiently without blocking the calling thread.

### Core Concepts

- **Streaming Pipeline**: You feed the denoiser audio chunks as you receive them (e.g., from `AudioRecord`). The denoiser buffers this audio internally and processes it in the fixed-size frames required by the underlying RNNoise engine.
- **Asynchronous Processing**: All audio processing happens on a background thread. Denoised audio is delivered back to you via a callback.
- **Arbitrary Sample Rates**: The denoiser can accept any standard sample rate (e.g., 8000, 16000, 44100 Hz). It automatically resamples the audio to the required 48 kHz for processing and then resamples it back to the original input rate.
- **VAD (Voice Activity Detection)**: For each processed chunk, the denoiser provides a VAD score indicating the probability of speech.

### Lifecycle

The denoiser has a clear lifecycle that must be followed to ensure all audio is processed and resources are released correctly.

1.  **Build**: Create an instance using `AudxDenoiser.Builder`.
2.  **Process**: Call `processAudio()` repeatedly with incoming audio chunks.
3.  **Flush**: At the end of the stream, call `flush()` to process any audio remaining in the internal buffer. This is a crucial step to avoid losing the last fraction of a second of audio.
4.  **Close**: Call `close()` to stop the worker thread and release all native resources. The instance cannot be used after this.

```
[Create with Builder] -> [processAudio()] -> [processAudio()] -> ... -> [flush()] -> [close()]
```

---

## Example Usage

Here is a complete example of integrating `AudxDenoiser` with `AudioRecord` in a ViewModel.

```kotlin
class MyViewModel : ViewModel() {
    private var denoiser: AudxDenoiser? = null
    private var recordingJob: Job? = null
    private val denoisedAudioBuffer = mutableListOf<Short>()

    fun startDenoising() {
        // 1. Build the denoiser
        denoiser = AudxDenoiser.Builder()
            .inputSampleRate(16000) // The sample rate of our source
            .onProcessedAudio { result ->
                // This callback is on a background thread
                // result contains the denoised audio and VAD stats
                denoisedAudioBuffer.addAll(result.audio.toList())
                Log.d("VAD", "Speech probability: ${result.vadProbability}")
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

        // 3. Flush the remaining audio
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
| `.inputSampleRate(Int)`   | The sample rate of your source audio (e.g., 16000).                                                     | `48000`            |
| `.resampleQuality(Int)`   | Sets the quality level for audio resampling. Use constants like `AudxDenoiser.RESAMPLER_QUALITY_VOIP`.  | `4`                |
| `.vadThreshold(Float)`    | VAD sensitivity (0.0-1.0). Higher is stricter.                                                          | `0.5`              |
| `.collectStatistics(Boolean)` | Enables VAD statistics. Must be `true` to get VAD results.                                       | `true`             |
| `.modelPreset(ModelPreset)` | Selects the built-in model (`EMBEDDED`) or a custom one.                                            | `EMBEDDED`         |
| `.modelPath(String?)`     | The file path to a custom `.rnnn` model file.                                                           | `null`             |
| `.workerThreadName(String)` | A custom name for the internal processing thread.                                                     | `"audx-worker"`    |
| `.onProcessedAudio(callback)` | **Required.** Sets the callback for receiving `DenoiseStreamResult` objects.                      | `null`             |
| `.build()`                | Constructs the `AudxDenoiser` instance.                                                                 | -                  |

### AudxDenoiser (Instance)

The public methods available on an `AudxDenoiser` instance.

| Method                        | Description                                                                                             | Thread Safety |
| ----------------------------- | ------------------------------------------------------------------------------------------------------- | ------------- |
| `processAudio(ShortArray)`    | Asynchronously submits an audio chunk for processing. Non-blocking.                                     | Thread-safe   |
| `flush()`                     | Synchronously processes any remaining audio in the buffer. Blocks until complete.                       | Thread-safe   |
| `close()`                     | Flushes remaining audio and releases all resources. Blocks until complete. The instance is unusable after this. | Thread-safe   |
| `setCallback(callback)`       | Updates the `onProcessedAudio` callback at runtime.                                                     | Thread-safe   |

### DenoiseStreamResult

This data class is the container for the output of the denoiser.

| Property           | Type        | Description                                                              |
| ------------------ | ----------- | ------------------------------------------------------------------------ |
| `audio`            | `ShortArray`| The chunk of denoised audio samples.                                     |
| `vadProbability`   | `Float`     | The VAD probability (0.0 to 1.0) of the last 10ms frame in this chunk.   |
| `isSpeech`         | `Boolean`   | `true` if `vadProbability` is above the configured `vadThreshold`.         |

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