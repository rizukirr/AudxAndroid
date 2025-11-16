# Audx - Quick Start Guide

This guide provides a practical, step-by-step example of how to integrate the Audx denoising library into a modern Android application. For a complete API reference, please see the [API Reference](API.md).

## 1. Installation

First, add the JitPack repository to your project's `settings.gradle.kts` file:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Next, add the Audx dependency to your module's `build.gradle.kts` file:

```kotlin
dependencies {
    implementation("com.github.rizukirr:audx-android:1.0.0")
}
```
> Always check the [Releases](https://github.com/rizukirr/audx-android/releases) page for the latest version.

## 2. Core Concepts

### Streaming Pipeline
Audx is designed to process audio in a stream. You continuously feed it audio chunks (e.g., from an `AudioRecord` buffer), and it processes them asynchronously. The library handles all internal buffering, so you can send chunks of any size.

### Threading Model
All audio processing, including resampling and denoising, happens on a dedicated background thread. This ensures that your UI thread is never blocked. Denoised audio and statistics are delivered back to you via callbacks that execute on this same background thread.

### Lifecycle
The denoiser has a simple but strict lifecycle:
1.  **Build**: Configure and create an instance using `AudxDenoiser.Builder`.
2.  **Process**: Call `processAudio()` repeatedly with new audio chunks.
3.  **Flush (Optional)**: Call `flush()` to force the processing of any buffered audio. This is useful if you need to ensure all audio up to a certain point is processed before `close()` is called.
4.  **Close**: Call `close()` to release all native and thread resources. This method includes a final, implicit flush to ensure no audio is lost. The instance is unusable after being closed.

## 3. Complete Example: Denoising in a ViewModel

This example demonstrates how to manage `AudioRecord` and `AudxDenoiser` within a `ViewModel`, which is the recommended approach for handling background tasks and surviving configuration changes.

### Step 1: Add Permissions
Ensure your `AndroidManifest.xml` includes the `RECORD_AUDIO` permission:
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

### Step 2: Create the ViewModel

```kotlin
package com.example.audx

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.audx.AudxDenoiser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class DenoisingViewModel(private val outputFile: File) : ViewModel() {

    companion object {
        private const val TAG = "DenoisingViewModel"
        private const val RECORDING_SAMPLE_RATE = 16000
    }

    private var denoiser: AudxDenoiser? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    @SuppressLint("MissingPermission")
    fun startDenoising() {
        if (recordingJob?.isActive == true) return
        Log.d(TAG, "Starting denoising process...")

        // 1. Build the denoiser instance
        denoiser = AudxDenoiser.Builder()
            .inputSampleRate(RECORDING_SAMPLE_RATE)
            .collectStatistics(true) // Enable performance stats
            .onProcessedAudio { result ->
                // This callback executes on a background thread.
                // The denoised audio is in result.audio.
                // You can save it, play it, or stream it.
                // Note: Be mindful of thread safety if updating UI state.
                Log.d(TAG, "Received ${result.audio.size} denoised samples. VAD: ${result.vadProbability}")
            }
            .onCollectStats { stats ->
                // This callback executes when flush() or close() is called.
                Log.d(TAG, "Denoising stats: ${stats.speechDetectedPercent}% speech, avg time: ${stats.processTimeAvg}ms")
            }
            .build()

        // 2. Set up and start AudioRecord
        val minBufferSize = AudioRecord.getMinBufferSize(
            RECORDING_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            RECORDING_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2
        )

        audioRecord?.startRecording()

        // 3. Start a background coroutine to read from the microphone
        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            val buffer = ShortArray(RECORDING_SAMPLE_RATE / 10) // 100ms buffer
            while (isActive) {
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readSize > 0) {
                    // 4. Feed the audio chunks to the denoiser
                    denoiser?.processAudio(buffer.copyOf(readSize))
                }
            }
        }
    }

    fun stopDenoising() {
        if (recordingJob == null) return
        Log.d(TAG, "Stopping denoising process...")

        // Stop the recording coroutine
        recordingJob?.cancel()
        recordingJob = null

        // Stop AudioRecord
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // 5. Close the denoiser to release resources.
        // This will automatically flush any remaining audio and trigger the onCollectStats callback.
        denoiser?.close()
        denoiser = null
    }

    override fun onCleared() {
        // Ensure cleanup happens if the ViewModel is destroyed
        stopDenoising()
        super.onCleared()
    }
}
```

## 4. API Highlights

The `AudxDenoiser.Builder` allows you to configure the denoiser. Key methods include:

- `.inputSampleRate(Int)`: **Crucial.** Must match the sample rate of your audio source.
- `.onProcessedAudio(callback)`: **Required.** Receives the denoised audio.
- `.collectStatistics(Boolean)`: Enables performance stat collection.
- `.onCollectStats(callback)`: **Required if `collectStatistics(true)`.** Receives the final statistics report.
- `.resampleQuality(Int)`: Adjusts the trade-off between resampler speed and quality.

Once built, the main `AudxDenoiser` instance has a few key methods:

- `processAudio(ShortArray)`: The main method for sending audio to the denoiser.
- `flush()`: A blocking call to process any remaining audio in the buffer.
- `close()`: A blocking call to release all resources.

For a full list of methods and constants, see the **[API Reference](API.md)**.