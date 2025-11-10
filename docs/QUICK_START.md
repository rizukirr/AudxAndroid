# Audio Denoiser - Quick Start Guide

## Requirements Checklist

- **Sample Rate**: 48kHz (48000 Hz)
- **Audio Format**: 16-bit signed PCM
- **Frame Size**: 480 samples/channel (10ms)
- **Channels**: 1 (mono) or 2 (stereo, interleaved)

## Basic Usage (3 Steps)

### Step 1: Create Denoiser

```kotlin
val denoiser = Denoiser.Builder()
    .numChannels(1)              // 1 = mono, 2 = stereo
    .vadThreshold(0.5f)          // Speech detection threshold (0.0-1.0)
    .onProcessedAudio { audio, result ->
        // Handle denoised audio (called every 10ms)
        if (result.isSpeech) {
            Log.d("VAD", "Speech: ${result.vadProbability}")
        }
        // Use 'audio' (denoised ShortArray)
    }
    .build()
```

### Step 2: Feed Audio

```kotlin
// audioData must be 48kHz 16-bit PCM samples (ShortArray)
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

```kotlin
class MyActivity : AppCompatActivity() {
    private lateinit var denoiser: Denoiser
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    @SuppressLint("MissingPermission")
    fun startRecording() {
        // 1. Create denoiser
        denoiser = Denoiser.Builder()
            .numChannels(1)
            .vadThreshold(0.5f)
            .onProcessedAudio { denoisedAudio, result ->
                // Handle 10ms of denoised audio
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
            48000,  // Must be 48kHz!
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

## Key Constants

```kotlin
Denoiser.SAMPLE_RATE          // 48000
Denoiser.FRAME_SIZE           // 480 samples/channel
Denoiser.getFrameDurationMs() // 10ms
Denoiser.getFrameSizeInSamples(1)  // 480 (mono)
Denoiser.getFrameSizeInSamples(2)  // 960 (stereo)
Denoiser.getRecommendedBufferSize(1, 10)  // Buffer for 10ms mono
```

## Configuration Options

### VAD Threshold

```kotlin
.vadThreshold(0.5f)  // Default: 0.5
// 0.0 = very sensitive (detects whispers)
// 0.5 = balanced (normal speech)
// 1.0 = strict (only loud speech)
```

### Custom Model

```kotlin
.modelPreset(Denoiser.ModelPreset.CUSTOM)
.modelPath("/path/to/custom.rnnn")
```

### Disable VAD

```kotlin
.enableVadOutput(false)  // Skips VAD calculation
```

## AudioRecord Setup for 48kHz

```kotlin
val audioRecord = AudioRecord(
    MediaRecorder.AudioSource.VOICE_RECOGNITION,  // Best for speech
    48000,                                         // Must be 48kHz
    AudioFormat.CHANNEL_IN_MONO,                   // Mono or STEREO
    AudioFormat.ENCODING_PCM_16BIT,                // Must be 16-bit
    bufferSize
)
```

## Processing Flow

```
AudioRecord (48kHz PCM)
    ↓
processChunk(ShortArray)
    ↓
[Internal buffering to 480-sample frames]
    ↓
Audio Processing
    ↓
onProcessedAudio callback
    ↓
Denoised audio + VAD result
```

## Example Files

- [Simple Example](https://github.com/rizukirr/audx-android/blob/main/examples/SimpleDenoiserExample.kt)
- [AudioRecord Integration Example](https://github.com/rizukirr/audx-android/blob/main/examples/DenoiserAudioRecordingExample.kt)

## Common Mistakes

### ❌ Wrong sample rate

```kotlin
AudioRecord(..., 16000, ...)  // WRONG! Must be 48000
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

### Check if 48kHz is supported

```kotlin
val bufferSize = AudioRecord.getMinBufferSize(
    48000,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT
)
if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
    Log.e(TAG, "48kHz not supported on this device!")
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

**Ready to start?** Copy `SimpleDenoiserExample.kt` and run it! 🚀
