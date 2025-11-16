# Audx - Android Real-time Audio Denoising Library

[![](https://jitpack.io/v/rizukirr/audx-android.svg)](https://jitpack.io/#rizukirr/audx-android)

A streaming, real-time audio denoising library for Android with Voice Activity Detection (VAD). It wraps a native C++ library ([audx-realtime](https://github.com/rizukirr/audx-realtime)) with a clean, easy-to-use Kotlin API.

## Features

- **Real-time Noise Suppression**: Based on the excellent [Xiph.Org RNNoise](https://github.com/xiph/rnnoise) algorithm.
- **Streaming API**: Process audio in chunks of any size. The library handles all internal buffering.
- **Support for Any Sample Rate**: Automatically resamples from any standard input rate (e.g., 8kHz, 16kHz, 44.1kHz) to the required 48kHz and back.
- **Voice Activity Detection (VAD)**: Each processed chunk includes a probability score for speech presence.
- **Fluent Builder API**: A simple, chainable builder for easy configuration.
- **Lightweight & Performant**: Efficient C++ core with no external dependencies outside of the Android NDK.

## Quick Start

The library uses a simple `build` -> `process` -> `flush` -> `close` lifecycle.

```kotlin
// 1. Build the denoiser instance
val denoiser = AudxDenoiser.Builder()
    .inputSampleRate(16000) // Set the sample rate of your source audio
    .onProcessedAudio { result ->
        // This callback runs on a background thread.
        // 'result' contains the denoised audio and VAD statistics.
        val cleanAudio: ShortArray = result.audio
        val speechProbability: Float = result.vadProbability

        Log.d("Audx", "Received ${cleanAudio.size} denoised samples.")
        Log.d("Audx", "Speech probability: $speechProbability")

        // You can now play, save, or stream the cleanAudio
    }
    .build()

// 2. Process audio chunks as they arrive (e.g., from AudioRecord)
// This is non-blocking and can be called from any thread.
denoiser.processAudio(myAudioChunk)
denoiser.processAudio(anotherAudioChunk)

// 3. At the end of the stream, flush the internal buffers
// This is a blocking call to ensure all audio is processed.
denoiser.flush()

// 4. Close the denoiser to release native resources
denoiser.close()
```

## Installation

1.  Add the JitPack repository to your project's `settings.gradle.kts` file:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

2.  Add the dependency to your module's `build.gradle.kts` file:

```kotlin
dependencies {
    implementation("com.github.rizukirr:audx-android:1.1.0")
}
```
> Always check the [Releases](https://github.com/rizukirr/audx-android/releases) page for the latest version.

## API Documentation

For a complete guide to all classes, methods, and constants, please see the **[API Reference](docs/API.md)**.

## Requirements

- **Min SDK**: 24 (Android 7.0)
- **Audio Format**: 16-bit signed PCM, Mono.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

---

**Support This Project**

If you find this project helpful, consider ☕ [Buy Me a Coffee](https://ko-fi.com/rizukirr)