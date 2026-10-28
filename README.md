# PlayMedia Library

[![Release](https://jitpack.io/v/baboDev123/play_media_library.svg)](https://jitpack.io/#baboDev123/play_media_library)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Media3](https://img.shields.io/badge/Media3-1.11.1-orange.svg)](https://github.com/androidx/media)
[![API](https://img.shields.io/badge/API-23%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=23)

**PlayMedia Library** is an enterprise-grade media engine and codec extension built for Android **Media3 (ExoPlayer)**. Engineered with a high-throughput native pipeline, it delivers direct surface video rendering, comprehensive multi-channel audio/video decoding, seamless runtime decoder switching, and non-blocking stream intelligence.

---

## Key Capabilities

* **Direct Hardware Surface Pipeline**: Utilizes optimized native `ANativeWindow` display pipelines, delivering high frame rates (up to 60fps/120fps) with minimal CPU and battery consumption.
* **Extended Codec Matrix**:
  * **Audio**: AC-3 (Dolby Digital), E-AC-3 (Dolby Digital Plus), DTS, DTS-HD, TrueHD, FLAC, Opus, Vorbis, ALAC, AAC.
  * **Video**: H.264 (AVC), H.265 (HEVC), VP8, VP9, AV1 (dav1d), MPEG-2.
* **Intelligent Decoder Switching**: Effortlessly transition between Android `MediaCodec` (Hardware) and native software fallback at runtime without playback interruption or timestamp desynchronization.
* **Asynchronous Stream Intelligence**: Multi-threaded container analysis for instant retrieval of tracks, sample rates, color spaces, languages, subtitles, and chapters.
* **High-Precision Frame Extraction**: Native keyframe extraction and downsampling engine that produces Android `Bitmap` representations directly for gallery thumbnails and timeline scrubbing.
* **Clean & Modern Architecture**: Clean-room implementation engineered with strict Apache 2.0 compliance and full interop across Kotlin and Java/Groovy codebases.

---

## Installation

### 1. Configure Repository

Add JitPack to your project repository configuration:

#### **Kotlin DSL (`settings.gradle.kts`)**
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

#### **Groovy DSL (`settings.gradle` or root `build.gradle`)**
```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

---

### 2. Add Dependencies

Add the required modules to your application or feature module:

#### **Kotlin DSL (`build.gradle.kts`)**
```kotlin
dependencies {
    // Core Media3 Audio/Video Decoders & Dynamic Switcher
    implementation("com.github.baboDev123.play_media_library:media-decoder:v1.0.0")

    // High-Performance Metadata Analyzer & Frame Extractor
    implementation("com.github.baboDev123.play_media_library:media-info:v1.0.0")
}
```

#### **Groovy DSL (`build.gradle`)**
```groovy
dependencies {
    // Core Media3 Audio/Video Decoders & Dynamic Switcher
    implementation 'com.github.baboDev123.play_media_library:media-decoder:v1.0.0'

    // High-Performance Metadata Analyzer & Frame Extractor
    implementation 'com.github.baboDev123.play_media_library:media-info:v1.0.0'
}
```

---

## Quick Start

### 1. Initializing ExoPlayer with `PlayMeRenderersFactory`

Integrate native software decoders into your ExoPlayer instance with custom renderer prioritization:

#### **Kotlin DSL**
```kotlin
import androidx.media3.exoplayer.ExoPlayer
import io.github.playme.media.decoder.DecoderMode
import io.github.playme.media.decoder.PlayMeRenderersFactory

val renderersFactory = PlayMeRenderersFactory(context)
    .setDecoderMode(DecoderMode.AUTO) // Options: AUTO, HARDWARE, SOFTWARE

val player = ExoPlayer.Builder(context)
    .setRenderersFactory(renderersFactory)
    .build()
```

#### **Groovy DSL**
```groovy
import androidx.media3.exoplayer.ExoPlayer
import io.github.playme.media.decoder.DecoderMode
import io.github.playme.media.decoder.PlayMeRenderersFactory

def renderersFactory = new PlayMeRenderersFactory(context)
    .setDecoderMode(DecoderMode.AUTO)

def player = new ExoPlayer.Builder(context)
    .setRenderersFactory(renderersFactory)
    .build()
```

---

### 2. Runtime Decoder Switching

Seamlessly toggle between Hardware acceleration and Software decoding while maintaining playback position, audio sync, and speed:

```kotlin
val controller = DecoderController(
    playerProvider = { activePlayer },
    playerRebuilder = { selectedMode ->
        // Re-initialize player instance with the updated DecoderMode
    }
)

// Dynamically transition to native software decoding
controller.switchDecoderMode(DecoderMode.SOFTWARE)
```

---

### 3. Stream Metadata Analysis

Extract comprehensive container metadata, multi-audio tracks, subtitle formats, and chapter markers. Supports **Android 10+ Scoped Storage (`content://` Uri)**, `ParcelFileDescriptor`, and direct file paths.

#### **Kotlin Coroutines (`suspend`)**
```kotlin
lifecycleScope.launch {
    // Android 10+ Scoped Storage (content:// Uri)
    val info = MediaInfoExtractor.extract(context, contentUri)

    // Or via direct path / network URL:
    // val info = MediaInfoExtractor.extract("/path/to/media.mkv")

    info?.let { media ->
        Log.d("MediaInfo", "Format: ${media.formatName}, Duration: ${media.durationMs}ms")
        Log.d("MediaInfo", "Resolution: ${media.resolutionFormatted}")
        
        media.audioStreams.forEach { stream ->
            Log.d("MediaInfo", "Audio: ${stream.codecName}, Channels: ${stream.channels}, Lang: ${stream.language}")
        }
    }
}
```

#### **Java / Groovy (Callbacks & Synchronous API)**
```groovy
// Synchronous invocation with Content Uri or Path
def info = MediaInfoExtractor.extractSync(context, contentUri)

// Asynchronous callback invocation
MediaInfoExtractor.extractAsync(context, contentUri, new MediaInfoExtractor.Callback() {
    @Override
    void onSuccess(MediaInfo info) {
        println "Detected resolution: ${info.resolutionFormatted}"
    }

    @Override
    void onError(Throwable error) {
        error.printStackTrace()
    }
})
```

---

### 4. Fast Thumbnail Extraction

Extract exact timestamp frames scaled directly to target dimensions. Fully supports **`content://` Uris (Android 10+)**:

#### **Kotlin Coroutines**
```kotlin
lifecycleScope.launch {
    // Android 10+ Scoped Storage (content:// Uri)
    val thumbnailBitmap = ThumbnailExtractor.getFrameAt(
        context = context,
        uri = contentUri,
        timeUs = 5_000_000L, // 5.0 seconds
        targetWidth = 320,
        targetHeight = 180
    )
}
```

#### **Groovy / Java**
```groovy
def thumbnail = ThumbnailExtractor.getFrameAtSync(context, contentUri, 5000000L, 320, 180)
```

---

## Technical Specifications

| Parameter | Specification |
| :--- | :--- |
| **Minimum SDK** | Android 6.0 (API level 23) |
| **Target SDK** | Android 16 (API level 36) |
| **Compile SDK** | Android API level 36 |
| **Native Media Core** | FFmpeg Native Engine (`libavcodec`, `libavformat`, `libswresample`, `libswscale`, `libavutil`) |
| **Architecture** | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| **Media3 Base** | AndroidX Media3 `1.11.1` |
| **Toolchain** | Android NDK `r28+`, C++17 Standard |

---

## License

This project is licensed under the **Apache License, Version 2.0**.
See the [LICENSE](LICENSE) file for more information.
