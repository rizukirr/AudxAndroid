# Audio Denoiser - Quick Start Guide

## Requirements Checklist

- **Sample Rate**: Any positive sample rate (automatically resampled to 48kHz internally)
  - Native processing: 48kHz (48000 Hz)
  - Common rates: 8kHz, 16kHz, 44.1kHz, 48kHz
- **Audio Format**: 16-bit signed PCM
- **Frame Size**: Varies based on input sample rate (10ms chunks)
- **Channels**: Mono only (1 channel)

## Basic Usage (3 Steps)

### Step 1: Create Denoiser

```kotlin
// Option 1: For 48kHz audio (no resampling)
val denoiser = AudxDenoiser.Builder()
    .vadThreshold(0.5f)          // Speech detection threshold (0.0-1.0)
    .onProcessedAudio { audio, result ->
        // Handle denoised audio (called every 10ms)
        if (result.isSpeech) {
            Log.d("VAD", "Speech: ${result.vadProbability}")
        }
        // Use 'audio' (denoised ShortArray at 48kHz)
    }
    .build()

// Option 2: For non-48kHz audio (with automatic resampling)
val denoiser = AudxDenoiser.Builder()
    .inputSampleRate(16000)      // Input is 16kHz
    .resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_VOIP)  // Quality level
    .vadThreshold(0.5f)
    .onProcessedAudio { audio, result ->
        // Handle denoised audio (called every 10ms)
        // Audio is resampled back to 16kHz
        if (result.isSpeech) {
            Log.d("VAD", "Speech: ${result.vadProbability}")
        }
    }
    .build()
```

### Step 2: Feed Audio

```kotlin
// audioData can be any sample rate (16-bit PCM mono)
// If inputSampleRate is specified, audio will be resampled automatically
lifecycleScope.launch {
    denoiser.processChunk(audioData)  // Any size, buffered internally
}
```

### Step 3: Cleanup

```kotlin
denoiser.flush()    // Process remaining buffered audio
denoiser.destroy()  // Release resources
```

## Complete Android Example

### Example 1: 48kHz Audio (No Resampling)

```kotlin
class MyActivity : AppCompatActivity() {
    private lateinit var denoiser: AudxDenoiser
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    @SuppressLint("MissingPermission")
    fun startRecording() {
        // 1. Create denoiser (48kHz, no resampling)
        denoiser = AudxDenoiser.Builder()
            .vadThreshold(0.5f)
            .onProcessedAudio { denoisedAudio, result ->
                // Handle 10ms of denoised audio at 48kHz
                handleAudio(denoisedAudio, result.isSpeech)
            }
            .build()

        // 2. Create AudioRecord at 48kHz
        val bufferSize = AudioRecord.getMinBufferSize(
            48000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            48000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        // 3. Start recording
        isRecording = true
        audioRecord?.startRecording()

        // 4. Process in background
        lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ShortArray(960)  // 20ms buffer
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    denoiser.processChunk(buffer.copyOf(read))
                }
            }
        }
    }

    fun stopRecording() {
        isRecording = false
        lifecycleScope.launch {
            denoiser.flush()
            delay(50)
            audioRecord?.stop()
            audioRecord?.release()
            denoiser.destroy()
        }
    }

    private fun handleAudio(audio: ShortArray, isSpeech: Boolean) {
        // Your audio processing here
        // audio is 48kHz 16-bit PCM, denoised
    }
}
```

### Example 2: 16kHz Audio (With Automatic Resampling)

```kotlin
class MyActivity : AppCompatActivity() {
    private lateinit var denoiser: AudxDenoiser
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    @SuppressLint("MissingPermission")
    fun startRecording() {
        // 1. Create denoiser with resampling for 16kHz input
        denoiser = AudxDenoiser.Builder()
            .inputSampleRate(16000)  // Specify input is 16kHz
            .resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_VOIP)  // VoIP quality
            .vadThreshold(0.5f)
            .onProcessedAudio { denoisedAudio, result ->
                // Handle 10ms of denoised audio at 16kHz (resampled back)
                handleAudio(denoisedAudio, result.isSpeech)
            }
            .build()

        // 2. Create AudioRecord at 16kHz
        val bufferSize = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            16000,  // 16kHz input
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        // 3. Start recording
        isRecording = true
        audioRecord?.startRecording()

        // 4. Process in background
        lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ShortArray(320)  // 20ms buffer at 16kHz
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    // Audio is automatically resampled to 48kHz for processing,
                    // then resampled back to 16kHz in the callback
                    denoiser.processChunk(buffer.copyOf(read))
                }
            }
        }
    }

    fun stopRecording() {
        isRecording = false
        lifecycleScope.launch {
            denoiser.flush()
            delay(50)
            audioRecord?.stop()
            audioRecord?.release()
            denoiser.destroy()
        }
    }

    private fun handleAudio(audio: ShortArray, isSpeech: Boolean) {
        // Your audio processing here
        // audio is 16kHz 16-bit PCM, denoised
    }
}
```

## Key Constants

```kotlin
// Audio format constants
AudxDenoiser.SAMPLE_RATE          // 48000 Hz (native processing rate)
AudxDenoiser.CHANNELS             // 1 (mono only)
AudxDenoiser.BIT_DEPTH            // 16 (16-bit PCM)
AudxDenoiser.FRAME_SIZE           // 480 samples (at 48kHz)
AudxDenoiser.getFrameDurationMs() // 10ms
AudxDenoiser.getRecommendedBufferSize(10)  // Buffer size in bytes for 10ms

// Resampling quality constants
AudxDenoiser.RESAMPLER_QUALITY_MAX      // 10 - Best quality
AudxDenoiser.RESAMPLER_QUALITY_DEFAULT  // 4 - Balanced (default)
AudxDenoiser.RESAMPLER_QUALITY_VOIP     // 3 - Optimized for VoIP
AudxDenoiser.RESAMPLER_QUALITY_MIN      // 0 - Fastest
```

## Configuration Options

### Input Sample Rate

```kotlin
.inputSampleRate(16000)  // Default: 48000 (no resampling)
// Automatically resamples to 48kHz for processing, then back to original rate
// Common values: 8000, 16000, 44100, 48000
```

### Resampling Quality

```kotlin
.resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_VOIP)  // Default: RESAMPLER_QUALITY_DEFAULT
// RESAMPLER_QUALITY_MIN (0) = fastest, lower quality
// RESAMPLER_QUALITY_VOIP (3) = optimized for voice
// RESAMPLER_QUALITY_DEFAULT (4) = balanced
// RESAMPLER_QUALITY_MAX (10) = best quality, slower
```

### VAD Threshold

```kotlin
.vadThreshold(0.5f)  // Default: 0.5
// 0.0 = very sensitive (detects whispers)
// 0.5 = balanced (normal speech)
// 1.0 = strict (only loud speech)
```

### Custom Model

```kotlin
.modelPreset(AudxDenoiser.ModelPreset.CUSTOM)
.modelPath("/path/to/custom.rnnn")
```

### Disable VAD

```kotlin
.enableVadOutput(false)  // Skips VAD calculation
```

## AudioRecord Setup

### For 48kHz (No Resampling)

```kotlin
val audioRecord = AudioRecord(
    MediaRecorder.AudioSource.VOICE_RECOGNITION,  // Best for speech
    48000,                                         // 48kHz
    AudioFormat.CHANNEL_IN_MONO,                   // Mono only
    AudioFormat.ENCODING_PCM_16BIT,                // Must be 16-bit
    bufferSize
)
```

### For 16kHz (With Resampling)

```kotlin
val audioRecord = AudioRecord(
    MediaRecorder.AudioSource.VOICE_RECOGNITION,  // Best for speech
    16000,                                         // 16kHz input
    AudioFormat.CHANNEL_IN_MONO,                   // Mono only
    AudioFormat.ENCODING_PCM_16BIT,                // Must be 16-bit
    bufferSize
)

// Configure denoiser with matching input rate
val denoiser = AudxDenoiser.Builder()
    .inputSampleRate(16000)  // Must match AudioRecord sample rate
    .resampleQuality(AudxDenoiser.RESAMPLER_QUALITY_VOIP)
    .build()
```

## Processing Flow

### Without Resampling (48kHz)

```
AudioRecord (48kHz PCM)
    ↓
processChunk(ShortArray)
    ↓
[Internal buffering to 480-sample frames]
    ↓
Audio Processing (48kHz)
    ↓
onProcessedAudio callback
    ↓
Denoised audio + VAD result (48kHz)
```

### With Resampling (e.g., 16kHz)

```
AudioRecord (16kHz PCM)
    ↓
processChunk(ShortArray)
    ↓
[Internal buffering to 160-sample frames at 16kHz]
    ↓
Resample to 48kHz (480 samples)
    ↓
Audio Processing (48kHz)
    ↓
Resample back to 16kHz (160 samples)
    ↓
onProcessedAudio callback
    ↓
Denoised audio + VAD result (16kHz)
```

## Example Files

- [Full Example Application](https://github.com/rizukirr/audx-android/tree/main/examples/audx_example) - Complete Android app demonstrating real-time audio denoising with AudioRecord integration

## Common Mistakes

### ❌ Mismatched sample rates

```kotlin
// AudioRecord is 16kHz
AudioRecord(..., 16000, ...)

// But denoiser is configured for 48kHz (default)
val denoiser = AudxDenoiser.Builder()
    // Missing .inputSampleRate(16000)
    .build()

// WRONG! Sample rates must match
```

✅ **Correct:**

```kotlin
// AudioRecord is 16kHz
AudioRecord(..., 16000, ...)

// Denoiser configured with matching input rate
val denoiser = AudxDenoiser.Builder()
    .inputSampleRate(16000)  // Must match AudioRecord
    .build()
```

### ❌ Not using coroutines for processChunk

```kotlin
denoiser.processChunk(audio)  // WRONG! Missing suspend context
```

✅ **Correct:**

```kotlin
lifecycleScope.launch {
    denoiser.processChunk(audio)
}
```

### ❌ Forgetting to flush

```kotlin
denoiser.destroy()  // WRONG! May lose buffered audio
```

✅ **Correct:**

```kotlin
denoiser.flush()
delay(50)
denoiser.destroy()
```

### ❌ Using wrong buffer type

```kotlin
val buffer = ByteArray(1024)  // WRONG! Denoiser needs ShortArray
```

✅ **Correct:**

```kotlin
val buffer = ShortArray(480)  // ShortArray for 16-bit PCM
```

## Testing

### Check if sample rate is supported

```kotlin
// Check if 48kHz is supported
val bufferSize48k = AudioRecord.getMinBufferSize(
    48000,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT
)
if (bufferSize48k == AudioRecord.ERROR_BAD_VALUE) {
    Log.e(TAG, "48kHz not supported on this device!")
    // Use lower sample rate with resampling
}

// Check if 16kHz is supported
val bufferSize16k = AudioRecord.getMinBufferSize(
    16000,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT
)
if (bufferSize16k == AudioRecord.ERROR_BAD_VALUE) {
    Log.e(TAG, "16kHz not supported on this device!")
}
```

### Monitor VAD

```kotlin
.onProcessedAudio { audio, result ->
    Log.d("VAD", "Probability: ${result.vadProbability}, " +
                 "Speech: ${result.isSpeech}")
}
```

### Save to file for testing

```kotlin
val file = File(context.cacheDir, "denoised.pcm")
val fos = FileOutputStream(file)
denoiser.onProcessedAudio { audio, _ ->
    val buffer = ByteBuffer.allocate(audio.size * 2)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    audio.forEach { buffer.putShort(it) }
    fos.write(buffer.array())
}
// Convert to WAV: ffmpeg -f s16le -ar 48000 -ac 1 -i denoised.pcm denoised.wav
```

## Additional Resources

- **Frame size explanation**: 480 samples = 10ms at 48kHz
- **VAD**: Voice Activity Detection (0.0 = silence, 1.0 = speech)

## Troubleshooting

| Issue                | Solution                                                        |
| -------------------- | --------------------------------------------------------------- |
| No callback firing   | Check you're using `lifecycleScope.launch` for `processChunk()` |
| Distorted audio      | Verify 48kHz sample rate, check buffer isn't being modified     |
| High latency         | Use smaller read buffers (480-960 samples)                      |
| Memory leak          | Always call `destroy()` when done                               |
| "State check failed" | Denoiser was already destroyed, don't reuse                     |

---

**Ready to start?** Check out the [full example application](https://github.com/rizukirr/audx-android/tree/main/examples/audx_example) to see it in action! 🚀
