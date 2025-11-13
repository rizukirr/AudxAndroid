# Audx - Android Realtime Audio Denoising Library

[![](https://jitpack.io/v/rizukirr/audx-android.svg)](https://jitpack.io/#rizukirr/audx-android)
[![Version](https://img.shields.io/badge/version-v1.0.0-blue.svg)](https://github.com/rizukirr/audx-android/releases)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://android-arsenal.com/api?level=24)

Real-time audio denoising library for Android with Voice Activity Detection (VAD). Wraps [audx-realtime](https://github.com/rizukirr/audx-realtime) native library with a clean Kotlin/Java API.

## Features

- ✅ Real-time audio denoising with RNNoise algorithm
- ✅ Voice Activity Detection (VAD) with configurable threshold
- ✅ Audio format validation with clear error messages
- ✅ Mono audio processing optimized for performance
- ✅ Automatic internal buffering for variable chunk sizes
- ✅ A richer statistics API for real-time monitoring
- ✅ Custom model support for specialized environments
- ✅ ARM NEON & x86 SIMD optimizations

## Quick Start

```kotlin
// 1. Create denoiser
val denoiser = AudxDenoiser.Builder()
    .vadThreshold(0.5f)
    .onProcessedAudio { audio, result ->
        // Handle denoised audio + VAD result
        Log.d("VAD", "Speech: ${result.isSpeech}, prob: ${result.vadProbability}")
    }
    .build()

// 2. Feed audio (48kHz 16-bit PCM mono)
lifecycleScope.launch {
    denoiser.processChunk(audioData)
}

// 3. Cleanup
denoiser.flush()
denoiser.destroy()
```

## Requirements

- **Sample Rate**: 48kHz (48000 Hz)
- **Channels**: Mono only (1 channel)
- **Audio Format**: 16-bit signed PCM
- **Frame Size**: 480 samples (10ms)
- **Min SDK**: 24 (Android 7.0)

All constants available from native library: `AudxDenoiser.SAMPLE_RATE`, `AudxDenoiser.CHANNELS`, `AudxDenoiser.BIT_DEPTH`, `AudxDenoiser.FRAME_SIZE`

## Installation

Add JitPack repository to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add dependency in `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.rizukirr:audx-android:1.0.0")
}
```

> **Latest version**: Check [Releases](https://github.com/rizukirr/audx-android/releases)

## Documentation

- 📖 **[API Reference](docs/API.md)** - Complete API documentation
- 🚀 **[Quick Start Guide](docs/QUICK_START.md)** - Step-by-step tutorial with examples
- 💡 **[Examples](examples/)** - Real-world usage examples

## License

See [LICENSE](LICENSE) file for details.

## Credits

Built with [audx-realtime](https://github.com/rizukirr/audx-realtime) - Audio denoising library based on [Xiph.Org RNNoise](https://github.com/xiph/rnnoise).

---

💖 **Support This Project**

If you find this project helpful, consider supporting its development:  
☕ [Buy Me a Coffee](https://ko-fi.com/rizukirr)

Made with ❤️ by [rizukirr](https://github.com/rizukirr)
