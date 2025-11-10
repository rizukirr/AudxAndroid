# Audx - Android Audio Denoising Library

[![](https://jitpack.io/v/rizukirr/audx-android.svg)](https://jitpack.io/#rizukirr/audx-android)
[![Version](https://img.shields.io/badge/version-v1.0.0--dev01-blue.svg)](https://github.com/rizukirr/audx-android/releases)
[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://android-arsenal.com/api?level=24)

An Android library for real-time audio denoising with built-in Voice Activity Detection (VAD), based on Recurrent Neural Networks. The core of this project is written in C, which you can find at this [link](https://github.com/rizukirr/audx-realtime).

## Quick Start

Get started in 3 simple steps:

```kotlin
// 1. Create denoiser
val denoiser = Denoiser.Builder()
    .numChannels(1)
    .vadThreshold(0.5f)
    .onProcessedAudio { audio, result ->
        // Handle denoised audio
    }
    .build()

// 2. Feed audio (48kHz 16-bit PCM)
lifecycleScope.launch {
    denoiser.processChunk(audioData)
}

// 3. Cleanup
denoiser.flush()
denoiser.destroy()
```

## Requirements

- **Sample Rate**: 48kHz (48000 Hz)
- **Audio Format**: 16-bit signed PCM
- **Frame Size**: 480 samples/channel (10ms)
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

- ✅ Real-time audio denoising
- ✅ Built-in Voice Activity Detection (VAD)
- ✅ Mono and stereo support
- ✅ Automatic internal buffering
- ✅ Suspend function support for Kotlin coroutines
- ✅ Custom model support
- ✅ Zero-copy native processing

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
