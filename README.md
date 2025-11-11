# Audx - Android Audio Denoising Library

[![](https://jitpack.io/v/rizukirr/audx-android.svg)](https://jitpack.io/#rizukirr/audx-android)
[![Version](https://img.shields.io/badge/version-v1.0.0--dev01-blue.svg)](https://github.com/rizukirr/audx-android/releases)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://android-arsenal.com/api?level=24)

An Android library for real-time audio denoising with built-in Voice Activity Detection (VAD) and audio format validation. Uses [audx-realtime](https://github.com/rizukirr/audx-realtime) native library with RNNoise algorithm, exposed via Kotlin/Java API with JNI.

## Quick Start

Get started in 3 simple steps:

```kotlin
// 1. Validate audio format (optional but recommended)
val formatResult = AudioFormatValidator.validateFormat(
    sampleRate = Denoiser.SAMPLE_RATE,  // 48000 Hz from native
    channels = Denoiser.CHANNELS,        // 1 (mono) from native
    bitDepth = Denoiser.BIT_DEPTH        // 16-bit from native
)

if (formatResult is ValidationResult.Success) {
    // 2. Create denoiser
    val denoiser = Denoiser.Builder()
        .vadThreshold(0.5f)
        .onProcessedAudio { audio, result ->
            // Handle denoised audio + VAD result
            Log.d("VAD", "Speech: ${result.isSpeech}, prob: ${result.vadProbability}")
        }
        .build()

    // 3. Feed audio (48kHz 16-bit PCM mono)
    lifecycleScope.launch {
        denoiser.processChunk(audioData)
    }

    // 4. Cleanup
    denoiser.flush()
    denoiser.destroy()
}
```

## Requirements

- **Sample Rate**: 48kHz (48000 Hz) - `Denoiser.SAMPLE_RATE`
- **Channels**: Mono only (1 channel) - `Denoiser.CHANNELS`
- **Audio Format**: 16-bit signed PCM - `Denoiser.BIT_DEPTH`
- **Frame Size**: 480 samples (10ms) - `Denoiser.FRAME_SIZE`
- **Min SDK**: 24 (Android 7.0)

## Documentation

For complete documentation, examples, and integration guides, see:

📚 **[Full Documentation →](docs/QUICK_START.md)**

The documentation includes:

- Complete API reference
- Android AudioRecord integration examples
- Configuration options
- Troubleshooting guide
- Common mistakes and solutions

## Features

- ✅ **Real-time audio denoising** with RNNoise algorithm
- ✅ **Voice Activity Detection (VAD)** with configurable threshold
- ✅ **Audio format validation** with clear error messages
- ✅ **Single source of truth** - constants from native C library exposed via JNI
- ✅ **Mono audio processing** optimized for minimal overhead
- ✅ **Automatic internal buffering** handles variable chunk sizes
- ✅ **Kotlin coroutines** support with suspend functions
- ✅ **Custom model support** for specialized noise environments
- ✅ **Zero-copy native processing** for optimal performance
- ✅ **SIMD optimizations** (NEON for ARM, SSE/AVX for x86)

### Audio Format Validation

```kotlin
// Utility class for format validation
val result = AudioFormatValidator.validateFormat(
    sampleRate = 48000,
    channels = 1,
    bitDepth = 16
)

when (result) {
    is ValidationResult.Success -> {
        // Format is valid
    }
    is ValidationResult.Error -> {
        // Get descriptive error message
        Log.e(TAG, "Invalid format: ${result.message}")
    }
}

// Validate audio chunks
AudioFormatValidator.validateChunk(audioData)
```

**Constants from Native Library:**

All format requirements are defined in the native C library and exposed via JNI:

```kotlin
Denoiser.SAMPLE_RATE  // 48000 (from AUDX_SAMPLE_RATE_48KHZ)
Denoiser.CHANNELS     // 1      (from AUDX_CHANNELS_MONO)
Denoiser.BIT_DEPTH    // 16     (from AUDX_BIT_DEPTH_16)
Denoiser.FRAME_SIZE   // 480    (from AUDX_FRAME_SIZE)
```

This ensures format requirements stay in sync between native and Kotlin layers.

## Installation

Add JitPack repository to your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency in your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.rizukirr:audx-android:v1.0.0-dev01")
}
```

> **Latest version**: Check [Releases](https://github.com/rizukirr/audx-android/releases) for the latest version.

## License

See [LICENSE](LICENSE) file for details.

## Credits

Built with [audx-realtime](https://github.com/rizukirr/audx-realtime) audio processing library.
