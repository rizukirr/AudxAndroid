# API Reference

Complete API documentation for Audx Android audio denoising library.

## Table of Contents

- [AudxDenoiser](#audxdenoiser)
  - [Builder](#builder)
  - [Constants](#constants)
  - [Methods](#methods)
- [AudxValidator](#audxvalidator)
- [Data Classes](#data-classes)
- [Custom Models](#custom-models)

---

## AudxDenoiser

Main class for real-time audio denoising with Voice Activity Detection.

### Builder

Fluent API for configuring the denoiser.

#### `.inputSampleRate(Int)`

Set the input audio sample rate. If different from 48kHz, audio will be automatically resampled to 48kHz for denoising, then resampled back to the original rate.

```kotlin
.inputSampleRate(16000)  // Input is 16kHz, will be resampled
```

**Parameters:**
- `sampleRate`: Input sample rate in Hz (must be positive)
  - Common values: `8000`, `16000`, `44100`, `48000`
  - Default: `48000` (no resampling)

**Behavior:**
- If `inputSampleRate == 48000`: No resampling (optimal performance)
- If `inputSampleRate != 48000`: Automatic bidirectional resampling
  - Input → 48kHz for denoising
  - Output → original sample rate

**Default:** 48000 (no resampling)

---

#### `.resampleQuality(Int)`

Set the quality level for audio resampling. Only used when `inputSampleRate != 48000`.

```kotlin
.resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_VOIP)  // VoIP quality
```

**Parameters:**
- `quality`: Quality level (0-10)
  - `RESAMPLER_QUALITY_MIN` (0): Fastest, lower quality
  - `RESAMPLER_QUALITY_VOIP` (3): Optimized for voice/VoIP
  - `RESAMPLER_QUALITY_DEFAULT` (4): Balanced quality/speed (recommended)
  - `RESAMPLER_QUALITY_MAX` (10): Best quality, slower

**Trade-offs:**
- Higher quality = Better audio fidelity, more CPU usage
- Lower quality = Faster processing, potential artifacts

**Default:** `RESAMPLER_QUALITY_DEFAULT` (4)

**Note:** Quality setting has no effect when `inputSampleRate == 48000`

---

#### `.vadThreshold(Float)`

Set Voice Activity Detection sensitivity threshold.

```kotlin
.vadThreshold(0.5f)  // Default: 0.5
```

**Parameters:**
- `threshold`: Float between 0.0 and 1.0
  - `0.0-0.3`: Very sensitive, detects whispers and background noise
  - `0.5`: Balanced, detects normal speech (recommended)
  - `0.7-1.0`: Strict, only loud/clear speech

**Default:** 0.5f

---

#### `.modelPreset(ModelPreset)`

Select denoising model preset.

```kotlin
.modelPreset(AudxDenoiser.ModelPreset.EMBEDDED)  // Default
.modelPreset(AudxDenoiser.ModelPreset.CUSTOM)    // Requires modelPath()
```

**Options:**
- `EMBEDDED`: Built-in RNNoise model (default)
- `CUSTOM`: Load custom trained model (must also call `.modelPath()`)

**Default:** `EMBEDDED`

---

#### `.modelPath(String)`

Path to custom model file. Required when using `ModelPreset.CUSTOM`.

```kotlin
.modelPath("/sdcard/Download/my_model.rnn")
```

**Parameters:**
- `path`: Absolute path to RNNoise-compatible model file

**Requirements:**
- Must be compatible with [Xiph.Org RNNoise](https://github.com/xiph/rnnoise) format
- Must be trained for 48kHz audio
- File must be readable
- Only required when `modelPreset` is `CUSTOM`

---

#### `.enableVadOutput(Boolean)`

Enable or disable VAD probability calculation.

```kotlin
.enableVadOutput(true)   // Default
.enableVadOutput(false)  // Skip VAD computation for performance
```

**Parameters:**
- `enabled`: `true` to calculate VAD, `false` to skip

**Default:** `true`

**Note:** Disabling VAD output slightly improves performance but `DenoiserResult.vadProbability` will always be 0.0 and `isSpeech` will always be false.

---

#### `.onProcessedAudio(ProcessedAudioCallback)`

Callback for receiving denoised audio frames. **Required for streaming mode.**

```kotlin
.onProcessedAudio { denoisedAudio, result ->
    // denoisedAudio: ShortArray - denoised samples at input sample rate
    // result: DenoiserResult - VAD info
    Log.d("VAD", "Speech: ${result.isSpeech}, prob: ${result.vadProbability}")
    playAudio(denoisedAudio)
}
```

**Parameters:**
- `callback`: `(ShortArray, DenoiserResult) -> Unit`
  - `denoisedAudio`: Denoised audio samples at input sample rate
    - If `inputSampleRate == 48000`: 480 samples
    - If `inputSampleRate == 16000`: 160 samples
    - Generally: `(inputSampleRate * 10 / 1000)` samples per frame
  - `result`: VAD result and processing metadata

**Callback invoked:**
- Every time internal buffer accumulates ≥ frame size
- On `flush()` for remaining samples (zero-padded if needed)
- Called sequentially in processing order
- Runs on `Dispatchers.Default` (background thread)
- Audio is automatically resampled back to input sample rate

---

#### `.build()`

Build and initialize the denoiser.

```kotlin
val denoiser = AudxDenoiser.Builder()
    .vadThreshold(0.5f)
    .onProcessedAudio { audio, result -> }
    .build()
```

**Returns:** `AudxDenoiser` instance

**Throws:**
- `IllegalArgumentException` if callback is not set
- `IllegalStateException` if native initialization fails
- `IllegalArgumentException` if custom model path is invalid

---

### Constants

Audio format constants from native library (single source of truth).

#### Resampling Quality Constants

```kotlin
AudxDenoiser.RESAMPLER_QUALITY_MAX      // 10 - Best quality
AudxDenoiser.RESAMPLER_QUALITY_DEFAULT  // 4 - Balanced (default)
AudxDenoiser.RESAMPLER_QUALITY_VOIP     // 3 - Optimized for VoIP
AudxDenoiser.RESAMPLER_QUALITY_MIN      // 0 - Fastest
```

**Usage:**
```kotlin
.resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_VOIP)
```

**Guidelines:**
- **VoIP applications**: Use `RESAMPLER_QUALITY_VOIP` (3)
- **General purpose**: Use `RESAMPLER_QUALITY_DEFAULT` (4)
- **High quality recording**: Use `RESAMPLER_QUALITY_MAX` (10)
- **Low latency/embedded**: Use `RESAMPLER_QUALITY_MIN` (0)

---

#### `SAMPLE_RATE: Int`

Native processing sample rate in Hz.

```kotlin
val sampleRate = AudxDenoiser.SAMPLE_RATE  // 48000
```

**Value:** 48000 (48kHz)
**Source:** `AUDX_SAMPLE_RATE_48KHZ` from native library
**Note:** This is the internal processing rate. Input can be any sample rate via automatic resampling.

---

#### `CHANNELS: Int`

Number of audio channels supported.

```kotlin
val channels = AudxDenoiser.CHANNELS  // 1
```

**Value:** 1 (mono only)
**Source:** `AUDX_CHANNELS_MONO` from native library

---

#### `BIT_DEPTH: Int`

Audio sample bit depth.

```kotlin
val bitDepth = AudxDenoiser.BIT_DEPTH  // 16
```

**Value:** 16 (16-bit signed PCM)
**Source:** `AUDX_BIT_DEPTH_16` from native library

---

#### `FRAME_SIZE: Int`

Native processing frame size in samples.

```kotlin
val frameSize = AudxDenoiser.FRAME_SIZE  // 480
```

**Value:** 480 samples (10ms at 48kHz for mono)
**Source:** `AUDX_FRAME_SIZE` from native library
**Note:** This is the native frame size at 48kHz. Actual input frame size varies based on `inputSampleRate`:
- 48kHz: 480 samples per frame
- 16kHz: 160 samples per frame
- 8kHz: 80 samples per frame
- Formula: `(inputSampleRate * 10 / 1000)` samples per 10ms frame

---

### Methods

#### `processChunk(ShortArray): suspend`

Process audio chunk (streaming mode). Automatically buffers and processes complete frames.

```kotlin
lifecycleScope.launch {
    denoiser.processChunk(audioData)  // Any size accepted
}
```

**Parameters:**
- `audioData`: ShortArray of 16-bit PCM samples at input sample rate (any size)

**Behavior:**
- Suspends on `Dispatchers.Default` (CPU-intensive work)
- Thread-safe: Multiple coroutines can call concurrently
- Accumulates samples in internal buffer
- Processes frames when buffer is full:
  - 48kHz input: processes when ≥480 samples buffered
  - 16kHz input: processes when ≥160 samples buffered
  - Other rates: `(inputSampleRate * 10 / 1000)` samples
- Automatically resamples to 48kHz if needed
- Invokes callback for each processed frame with resampled output
- Returns after processing all complete frames

**Throws:**
- `IllegalArgumentException` if chunk is empty or too large
- `IllegalStateException` if denoiser was destroyed

**Example with AudioRecord (48kHz):**
```kotlin
val buffer = ShortArray(960)  // 20ms buffer at 48kHz
lifecycleScope.launch(Dispatchers.IO) {
    while (isRecording) {
        val read = audioRecord.read(buffer, 0, buffer.size)
        if (read > 0) {
            denoiser.processChunk(buffer.copyOf(read))
        }
    }
}
```

**Example with AudioRecord (16kHz with resampling):**
```kotlin
val denoiser = AudxDenoiser.Builder()
    .inputSampleRate(16000)
    .resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_VOIP)
    .onProcessedAudio { audio, result ->
        // audio is at 16kHz (160 samples per frame)
    }
    .build()

val buffer = ShortArray(320)  // 20ms buffer at 16kHz
lifecycleScope.launch(Dispatchers.IO) {
    while (isRecording) {
        val read = audioRecord.read(buffer, 0, buffer.size)
        if (read > 0) {
            denoiser.processChunk(buffer.copyOf(read))
        }
    }
}
```

---

#### `flush()`

Process remaining buffered audio samples.

```kotlin
denoiser.flush()
```

**Behavior:**
- Processes any samples remaining in buffer (< 480)
- Zero-pads incomplete frame to 480 samples
- Invokes callback with final processed audio
- Should be called before `destroy()`

**Important:** Always call `flush()` before `destroy()` to avoid losing the last 10-20ms of audio.

---

#### `destroy()`

Release native resources and cleanup.

```kotlin
denoiser.destroy()
```

**Behavior:**
- Frees native memory
- Invalidates denoiser instance
- Cannot be reused after calling

**Important:**
- Call `flush()` first to process remaining samples
- Consider adding delay after `flush()` to allow callbacks to complete:
  ```kotlin
  denoiser.flush()
  delay(50)  // Allow callbacks to finish
  denoiser.destroy()
  ```

---

#### `close()` (AutoCloseable)

Alternative cleanup via AutoCloseable interface.

```kotlin
AudxDenoiser.Builder().build().use { denoiser ->
    denoiser.processChunk(audioData)
    denoiser.flush()
}  // destroy() called automatically
```

**Behavior:**
- Calls `destroy()` automatically
- Recommended pattern for resource management

---

#### `getStats(): DenoiserStats?`

Get current denoiser statistics.

```kotlin
val stats = denoiser.getStats()
Log.i(TAG, "Processed ${stats?.frameProcessed} frames, " +
           "${stats?.speechDetectedPercent}% speech detected")
```

**Returns:** `DenoiserStats?` - Current statistics snapshot, or null if retrieval fails

**Behavior:**
- Returns comprehensive statistics about denoiser performance and behavior
- Statistics accumulate over the lifetime of the denoiser instance
- Thread-safe: Can be called from any thread
- Does not affect processing or reset counters

**Statistics include:**
- Frame count and speech detection rate
- VAD score statistics (average, min, max)
- Processing time metrics (total, average, last frame)

**Example:**
```kotlin
val stats = denoiser.getStats()
if (stats != null) {
    println("Frames: ${stats.frameProcessed}")
    println("Speech: ${stats.speechDetectedPercent}%")
    println("Avg VAD: ${stats.vadScoreAvg}")
    println("Avg time: ${stats.processingTimeAvg}ms")
}
```

**Throws:**
- `IllegalStateException` if denoiser has been destroyed

---

#### `resetStats()`

Reset all statistics counters to zero.

```kotlin
denoiser.resetStats()  // Start measuring new session
```

**Behavior:**
- Clears all accumulated statistics
- Frame counters reset to 0
- VAD scores reset to initial values
- Processing times reset to 0
- Use for measuring per-session or per-recording statistics

**Thread-safe:** Can be called from any thread

**Example - Per-Session Statistics:**
```kotlin
// Session 1
denoiser.resetStats()
processRecording1()
val session1Stats = denoiser.getStats()
Log.i(TAG, "Session 1: $session1Stats")

// Session 2
denoiser.resetStats()
processRecording2()
val session2Stats = denoiser.getStats()
Log.i(TAG, "Session 2: $session2Stats")
```

**Throws:**
- `IllegalStateException` if denoiser has been destroyed

---

## AudxValidator

Utility for validating audio format parameters.

### `validateFormat(Int, Int, Int): ValidationResult`

Validate audio format against denoiser requirements.

```kotlin
val result = AudxValidator.validateFormat(
    sampleRate = 48000,
    channels = 1,
    bitDepth = 16
)

when (result) {
    is ValidationResult.Success -> {
        // Format is valid
    }
    is ValidationResult.Error -> {
        Log.e(TAG, "Invalid: ${result.message}")
    }
}
```

**Parameters:**
- `sampleRate`: Sample rate in Hz
- `channels`: Number of channels
- `bitDepth`: Bit depth of samples

**Returns:** `ValidationResult.Success` or `ValidationResult.Error(message)`

---

### `validateChunk(ShortArray, Int): ValidationResult`

Validate audio chunk before processing.

```kotlin
val result = AudxValidator.validateChunk(audioData)
```

**Parameters:**
- `audioData`: Audio samples to validate
- `minSize`: Minimum chunk size (default: 1)

**Returns:** `ValidationResult.Success` or `ValidationResult.Error(message)`

**Checks:**
- Chunk is not empty
- Chunk is not excessively large
- Sample values are within 16-bit range

---

### `validateChunkSize(Int): ValidationResult`

Validate chunk size without audio data.

```kotlin
val result = AudxValidator.validateChunkSize(chunkSize)
```

**Parameters:**
- `chunkSize`: Size to validate

**Returns:** `ValidationResult.Success` or `ValidationResult.Error(message)`

---

## Data Classes

### DenoiserResult

Result of processing one audio frame.

```kotlin
data class DenoiserResult(
    val vadProbability: Float,      // 0.0 to 1.0
    val isSpeech: Boolean,          // true if > threshold
    val samplesProcessed: Int       // Always 480
)
```

**Properties:**
- `vadProbability`: Voice Activity Detection probability (0.0 = silence, 1.0 = speech)
- `isSpeech`: `true` if `vadProbability` exceeds configured threshold
- `samplesProcessed`: Number of samples processed (always 480 for mono)

---

### DenoiserStats

Comprehensive statistics for denoiser performance and behavior.

```kotlin
data class DenoiserStats(
    val frameProcessed: Int,
    val speechDetectedPercent: Float,
    val vadScoreAvg: Float,
    val vadScoreMin: Float,
    val vadScoreMax: Float,
    val processingTimeTotal: Float,
    val processingTimeAvg: Float,
    val processingTimeLast: Float
)
```

**Properties:**

- `frameProcessed: Int` - Total number of frames processed (each frame = 480 samples = 10ms)
- `speechDetectedPercent: Float` - Percentage of frames classified as speech (0-100)
- `vadScoreAvg: Float` - Average VAD score across all frames (0.0-1.0)
- `vadScoreMin: Float` - Minimum VAD score observed (0.0-1.0)
- `vadScoreMax: Float` - Maximum VAD score observed (0.0-1.0)
- `processingTimeTotal: Float` - Total processing time in milliseconds for all frames
- `processingTimeAvg: Float` - Average processing time per frame in milliseconds
- `processingTimeLast: Float` - Processing time for the most recent frame in milliseconds

**Usage:**

```kotlin
val stats = denoiser.getStats()
if (stats != null) {
    // Frame statistics
    println("Total frames: ${stats.frameProcessed}")
    println("Duration: ${stats.frameProcessed * 10}ms")

    // Speech detection
    println("Speech detected: ${stats.speechDetectedPercent}%")

    // VAD statistics
    println("VAD range: ${stats.vadScoreMin} to ${stats.vadScoreMax}")
    println("VAD average: ${stats.vadScoreAvg}")

    // Performance metrics
    println("Total time: ${stats.processingTimeTotal}ms")
    println("Avg per frame: ${stats.processingTimeAvg}ms")

    // Validate real-time performance (should be < 10ms per 10ms frame)
    if (stats.processingTimeAvg > 10.0f) {
        Log.w(TAG, "Processing too slow for real-time!")
    }
}
```

**Lifetime Behavior:**
- Statistics accumulate from denoiser creation (or last `resetStats()` call)
- Persist across `flush()` calls
- Only reset when `resetStats()` is explicitly called
- Cleared when denoiser is destroyed

**toString() Output:**
```
DenoiserStats(frames=150, speech=72.3%, vad=[avg=0.625, min=0.124, max=0.982],
              time=[total=185.43ms, avg=1.236ms, last=1.198ms])
```

---

### ValidationResult

Result of validation operation.

```kotlin
sealed class ValidationResult {
    object Success : ValidationResult()
    data class Error(val message: String) : ValidationResult()
}
```

---

### ModelPreset

Denoising model selection.

```kotlin
enum class ModelPreset {
    EMBEDDED,  // Built-in model (default)
    CUSTOM     // Load from file (requires modelPath)
}
```

---

## Custom Models

### Loading Custom Models

```kotlin
val denoiser = AudxDenoiser.Builder()
    .modelPreset(AudxDenoiser.ModelPreset.CUSTOM)
    .modelPath("/sdcard/Download/my_custom_model.rnn")
    .vadThreshold(0.5f)
    .onProcessedAudio { audio, result ->
        // Handle denoised audio
    }
    .build()
```

### Model Requirements

Custom models must meet these requirements:

1. **Format:** Compatible with [Xiph.Org RNNoise](https://github.com/xiph/rnnoise) binary format
2. **Training:** Must be trained for 48kHz audio
3. **Accessibility:** File must have read permissions
4. **Compatibility:** Must work with audx-realtime native library

### Training Custom Models

Refer to [RNNoise documentation](https://github.com/xiph/rnnoise) for training custom models:
- Use RNNoise training pipeline
- Train on 48kHz audio samples
- Export in binary model format
- Test with your specific noise environment

### Model Performance

- **EMBEDDED**: General-purpose noise reduction, optimized for speech
- **CUSTOM**: Specialized for specific noise environments (office, car, outdoor, etc.)

### Troubleshooting Custom Models

**Model fails to load:**
- Verify file path is absolute and accessible
- Check file permissions (must be readable)
- Ensure model format is compatible with RNNoise
- Confirm model was trained for 48kHz audio

**Poor denoising quality:**
- Model may not be trained for your noise environment
- Try adjusting VAD threshold
- Verify audio input is 48kHz mono 16-bit PCM
- Test with EMBEDDED model first to isolate issues

---

## Thread Safety

### Thread-Safe Operations

- `processChunk()` - Protected by ReentrantLock, multiple threads can call concurrently
- Multiple coroutines can process chunks simultaneously

### Non-Thread-Safe Operations

- `destroy()` - Only call from one thread after processing stops
- Builder methods - Not thread-safe, build on single thread

### Best Practices

```kotlin
// GOOD: Multiple coroutines processing
lifecycleScope.launch {
    denoiser.processChunk(chunk1)
}
lifecycleScope.launch {
    denoiser.processChunk(chunk2)
}

// GOOD: Single thread cleanup
lifecycleScope.launch {
    // ... processing done
    denoiser.flush()
    delay(50)
    denoiser.destroy()
}
```

---

## Performance Considerations

### Latency

- **Processing Time**: ~1-2ms per 10ms frame on modern ARM devices
- **Buffering**: Adds 0-10ms depending on chunk size
- **Resampling Overhead**:
  - No resampling (48kHz): ~0ms overhead
  - With resampling: +0.5-2ms depending on quality setting
  - VOIP quality (3): ~0.5-1ms
  - MAX quality (10): ~1-2ms
- **Recommended Buffer**:
  - 48kHz: 480-960 samples (10-20ms)
  - 16kHz: 160-320 samples (10-20ms)

### Memory

- **Per Instance**: ~50KB base (denoiser state + buffers)
- **With Resampling**: +10-20KB (resampler state)
- **Optimization**: Mono-only reduces memory footprint vs stereo

### CPU Usage

- **Single Channel (48kHz)**: ~5-10% on mid-range ARM devices
- **With Resampling (16kHz→48kHz→16kHz)**:
  - VOIP quality: +2-3% CPU
  - DEFAULT quality: +3-5% CPU
  - MAX quality: +5-8% CPU
- **Optimizations**:
  - ARM NEON intrinsics in native libraries
  - Speex resampler for efficient rate conversion
- **Best Practice**: Use single shared instance

### Quality vs Performance Trade-offs

| Quality Setting | CPU Impact | Latency | Use Case |
|----------------|-----------|---------|----------|
| MIN (0) | Lowest | ~0.5ms | Embedded/low-power devices |
| VOIP (3) | Low | ~0.7ms | VoIP, real-time communication |
| DEFAULT (4) | Moderate | ~1ms | General purpose (recommended) |
| MAX (10) | High | ~2ms | High-quality recording |

---

## Common Patterns

### Pattern 1: AudioRecord Integration (48kHz)

```kotlin
val audioRecord = AudioRecord(
    MediaRecorder.AudioSource.VOICE_RECOGNITION,
    48000,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    bufferSize
)

val denoiser = AudxDenoiser.Builder()
    .vadThreshold(0.5f)
    .onProcessedAudio { audio, result ->
        // Handle denoised audio at 48kHz
    }
    .build()

lifecycleScope.launch(Dispatchers.IO) {
    val buffer = ShortArray(960)
    while (isRecording) {
        val read = audioRecord.read(buffer, 0, buffer.size)
        if (read > 0) {
            denoiser.processChunk(buffer.copyOf(read))
        }
    }
}
```

### Pattern 1b: AudioRecord Integration (16kHz with Resampling)

```kotlin
val audioRecord = AudioRecord(
    MediaRecorder.AudioSource.VOICE_RECOGNITION,
    16000,  // 16kHz input
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    bufferSize
)

val denoiser = AudxDenoiser.Builder()
    .inputSampleRate(16000)  // Must match AudioRecord
    .resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_VOIP)
    .vadThreshold(0.5f)
    .onProcessedAudio { audio, result ->
        // Handle denoised audio at 16kHz (resampled back)
    }
    .build()

lifecycleScope.launch(Dispatchers.IO) {
    val buffer = ShortArray(320)  // 20ms at 16kHz
    while (isRecording) {
        val read = audioRecord.read(buffer, 0, buffer.size)
        if (read > 0) {
            denoiser.processChunk(buffer.copyOf(read))
        }
    }
}
```

### Pattern 2: Resource Management

```kotlin
// Option 1: Manual cleanup
denoiser.flush()
delay(50)
denoiser.destroy()

// Option 2: AutoCloseable (preferred)
AudxDenoiser.Builder().build().use { denoiser ->
    // Process audio
    denoiser.flush()
}  // destroy() called automatically
```

### Pattern 3: VAD-Based Recording

```kotlin
var isSpeechDetected = false
val denoiser = AudxDenoiser.Builder()
    .vadThreshold(0.5f)
    .onProcessedAudio { audio, result ->
        if (result.isSpeech) {
            isSpeechDetected = true
            saveAudio(audio)  // Only save speech
        }
    }
    .build()
```

### Pattern 4: Performance Monitoring with Statistics

```kotlin
val denoiser = AudxDenoiser.Builder()
    .vadThreshold(0.5f)
    .onProcessedAudio { audio, result ->
        // Handle denoised audio
    }
    .build()

// Option 1: Per-Session Statistics
fun recordAudioSession() {
    denoiser.resetStats()  // Start fresh measurement

    // ... process audio for this session ...

    denoiser.flush()
    val sessionStats = denoiser.getStats()

    Log.i(TAG, "Session complete: $sessionStats")
    // DenoiserStats(frames=150, speech=72.3%, vad=[avg=0.625, min=0.124, max=0.982],
    //               time=[total=185.43ms, avg=1.236ms, last=1.198ms])

    // Save session metadata
    saveRecordingMetadata(
        duration = sessionStats?.frameProcessed?.times(10) ?: 0,
        speechPercent = sessionStats?.speechDetectedPercent ?: 0f
    )
}

// Option 2: Continuous Production Monitoring
lifecycleScope.launch {
    while (isActive) {
        delay(30_000)  // Check every 30 seconds

        val stats = denoiser.getStats()
        if (stats != null) {
            // Report to analytics
            analytics.track("denoiser_performance", mapOf(
                "frames_processed" to stats.frameProcessed,
                "speech_percent" to stats.speechDetectedPercent,
                "avg_processing_time_ms" to stats.processingTimeAvg
            ))

            // Alert if performance degrades
            if (stats.processingTimeAvg > 5.0f) {
                Log.w(TAG, "Denoising slow: ${stats.processingTimeAvg}ms/frame")
            }
        }
    }
}

// Option 3: Real-Time Performance Validation
val stats = denoiser.getStats()
if (stats != null && stats.processingTimeAvg > 10.0f) {
    Log.e(TAG, "Cannot maintain real-time processing! " +
              "Avg: ${stats.processingTimeAvg}ms per 10ms frame")
    // Consider reducing quality or disabling denoising
}

// Option 4: VAD Threshold Tuning
val stats = denoiser.getStats()
if (stats != null) {
    when {
        stats.speechDetectedPercent > 95 -> {
            Log.w(TAG, "VAD threshold may be too low (${stats.speechDetectedPercent}% speech)")
            // Consider increasing threshold
        }
        stats.speechDetectedPercent < 5 -> {
            Log.w(TAG, "VAD threshold may be too high (${stats.speechDetectedPercent}% speech)")
            // Consider decreasing threshold
        }
    }
}
```

---

## Error Handling

### Common Errors

**IllegalArgumentException: "Callback must be set"**
- Solution: Call `.onProcessedAudio { }` in Builder

**IllegalStateException: "Denoiser was destroyed"**
- Solution: Don't reuse denoiser after `destroy()`, create new instance

**IllegalArgumentException: "inputSampleRate must be positive"**
- Solution: Ensure sample rate is greater than 0

**IllegalArgumentException: "resampleQuality must be between 0 and 10"**
- Solution: Use valid quality constants (RESAMPLER_QUALITY_MIN to RESAMPLER_QUALITY_MAX)

**Mismatched sample rates (distorted audio)**
- Symptom: Audio sounds distorted or incorrect
- Cause: AudioRecord sample rate doesn't match `inputSampleRate` in builder
- Solution: Ensure `inputSampleRate` matches AudioRecord configuration:
  ```kotlin
  val audioRecord = AudioRecord(..., 16000, ...)  // 16kHz
  val denoiser = AudxDenoiser.Builder()
      .inputSampleRate(16000)  // Must match!
      .build()
  ```

**"Custom model file not found"**
- Solution: Verify absolute path and file permissions

### Validation Best Practices

```kotlin
// Validate format before creating denoiser
val formatResult = AudxValidator.validateFormat(
    sampleRate = AudxDenoiser.SAMPLE_RATE,
    channels = AudxDenoiser.CHANNELS,
    bitDepth = AudxDenoiser.BIT_DEPTH
)

if (formatResult is ValidationResult.Error) {
    Log.e(TAG, "Invalid format: ${formatResult.message}")
    return
}

// Validate chunks before processing
val chunkResult = AudxValidator.validateChunk(audioData)
if (chunkResult is ValidationResult.Error) {
    Log.e(TAG, "Invalid chunk: ${chunkResult.message}")
    return
}
```
