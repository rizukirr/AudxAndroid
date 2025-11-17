# Audx - Android Real-time Audio Denoising Library

[![](https://jitpack.io/v/rizukirr/audx-android.svg)](https://jitpack.io/#rizukirr/audx-android)

A streaming, real-time audio denoising library for Android with Voice Activity Detection (VAD). It wraps a native C++ library ([audx-realtime](https://github.com/rizukirr/audx-realtime)) with a clean, easy-to-use Kotlin API.

## Features

- **Real-time Noise Suppression**: Based on the excellent [Xiph.Org RNNoise](https://github.com/xiph/rnnoise) algorithm.
- **Streaming API**: Process audio in chunks of any size. The library handles all internal buffering.
- **Support for Any Sample Rate**: Automatically resamples from any standard input rate (e.g., 8kHz, 16kHz, 44.1kHz) to the required 48kHz and back.
- **Voice Activity Detection (VAD)**: Each processed chunk includes a probability score for speech presence.
- **Performance Statistics**: Collect detailed metrics on CPU usage and speech activity.
- **Fluent Builder API**: A simple, chainable builder for easy configuration.
- **Lightweight & Performant**: Efficient C++ core with no external dependencies outside of the Android NDK.
- **Zero-Copy API**: A new `processAudio(ByteBuffer)` API for high-throughput scenarios, offering true zero-copy processing when used with a `DirectByteBuffer`.

## Performance

Version `1.1.2` introduces significant performance improvements, including pre-allocated memory buffers and cached JNI lookups, resulting in:
- ~25-35% faster processing per frame.
- ~20-25% lower CPU usage.
- Zero memory allocations on the audio processing hot path.

For high-performance use cases, the new **zero-copy API** is recommended. See the [Quick Start Guide](docs/QUICK_START.md#5-advanced-zero-copy-processing-with-bytebuffer) for an integration example.

## Getting Started

For a detailed guide on integrating Audx with `AudioRecord` in a modern Android app, please see the **[Quick Start Guide](docs/QUICK_START.md)**.

Here is a minimal example:

```kotlin
// 1. Build the denoiser instance
val denoiser = AudxDenoiser.Builder()
    .inputSampleRate(16000) // Match your source's sample rate
    .onProcessedAudio { result ->
        // This callback runs on a background thread.
        // 'result.audio' contains the denoised audio.
        Log.d("Audx", "VAD Probability: ${result.vadProbability}")
    }
    .build()

// 2. Process audio chunks as they arrive (e.g., from AudioRecord)
denoiser.processAudio(myAudioChunk)

// 3. At the end of the stream, close the denoiser to release resources.
// This is a blocking call that performs a final flush before cleaning up.
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
    implementation("com.github.rizukirr:audx-android:1.1.2")
}
```
> Always check the [Releases](https://github.com/rizukirr/audx-android/releases) page for the latest version.

## Documentation

- **[Quick Start Guide](docs/QUICK_START.md)**: A detailed, step-by-step integration guide.
- **[API Reference](docs/API.md)**: A complete reference for all classes, methods, and constants.

## Requirements

- **Min SDK**: 24 (Android 7.0)
- **Audio Format**: 16-bit signed PCM, Mono.

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

---

**Support This Project**

If you find this project helpful, consider ☕ [Buy Me a Coffee](https://ko-fi.com/rizukirr)
