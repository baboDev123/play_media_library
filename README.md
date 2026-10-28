# PlayMe Media Engine

High-performance, zero-copy media decoding and processing engine for Android Media3 (ExoPlayer).

## Features
- **Zero-Copy Direct Surface Rendering**: Direct `ANativeWindow` video output for ultra-smooth 60fps playback without memory copy bottlenecks.
- **Full Codec Support**:
  - **Audio**: AC3, E-AC3, DTS, DTS-HD, TrueHD, FLAC, Vorbis, Opus, ALAC.
  - **Video**: H.264, HEVC (H.265), VP8, VP9, AV1 (dav1d), MPEG-2.
- **Dynamic Hardware/Software Decoder Switching**: Switch seamlessly between Android `MediaCodec` and native FFmpeg at runtime without position loss.
- **Multi-threaded Stream Probing**: Ultra-fast container and stream metadata extraction with Kotlin Coroutines and Java callbacks.
- **High-speed Native Thumbnail Extractor**: Decode and scale video frames directly into Android Bitmaps.
- **100% Clean Architecture**: Zero third-party GPL dependency, clean Apache 2.0 license.

---

## Installation

### Kotlin DSL (`build.gradle.kts`)
```kotlin
dependencies {
    // Media3 Decoders (Audio & Video) + Dynamic Switching
    implementation("io.github.playme:media-decoder:1.0.0")

    // High-speed Metadata & Thumbnail Extractor
    implementation("io.github.playme:media-info:1.0.0")
}
```

### Groovy DSL (`build.gradle`)
```groovy
dependencies {
    // Media3 Decoders (Audio & Video) + Dynamic Switching
    implementation 'io.github.playme:media-decoder:1.0.0'

    // High-speed Metadata & Thumbnail Extractor
    implementation 'io.github.playme:media-info:1.0.0'
}
```

---

## Usage

### 1. ExoPlayer Setup with `PlayMeRenderersFactory`

#### Kotlin DSL
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

#### Groovy DSL
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

Switch between Hardware and Software on the fly without stopping playback:

```kotlin
val controller = DecoderController(
    playerProvider = { currentPlayer },
    playerRebuilder = { newMode ->
        // Rebuild player with new renderersFactory
    }
)

// Switch to software decoder
controller.switchDecoderMode(DecoderMode.SOFTWARE)
```

---

### 3. Extracting Media Stream Info

#### Kotlin (Coroutines)
```kotlin
lifecycleScope.launch {
    val info = MediaInfoExtractor.extract("/path/to/video.mkv")
    info?.let {
        println("Duration: ${it.durationMs}ms, Bitrate: ${it.bitRate}")
        println("Resolution: ${it.resolutionFormatted}")
        it.audioStreams.forEach { audio ->
            println("Audio: ${audio.codecName}, Channels: ${audio.channels}, Lang: ${audio.language}")
        }
    }
}
```

#### Groovy / Java (Sync & Callback)
```groovy
// Synchronous
def info = MediaInfoExtractor.extractSync("/path/to/video.mkv")

// Asynchronous callback
MediaInfoExtractor.extractAsync("/path/to/video.mkv", new MediaInfoExtractor.Callback() {
    @Override
    void onSuccess(MediaInfo info) {
        println "Format: ${info.formatName}"
    }

    @Override
    void onError(Throwable error) {
        error.printStackTrace()
    }
})
```

---

### 4. Extracting Video Thumbnails

#### Kotlin (Coroutines)
```kotlin
lifecycleScope.launch {
    // Extract thumbnail at timestamp 5.0 seconds, scaled to 320x180
    val bitmap = ThumbnailExtractor.getFrameAt(
        filePath = "/path/to/video.mp4",
        timeUs = 5_000_000L,
        targetWidth = 320,
        targetHeight = 180
    )
}
```

#### Groovy / Java
```groovy
def bitmap = ThumbnailExtractor.getFrameAtSync("/path/to/video.mp4", 5000000L, 320, 180)
```

---

## License
Licensed under the Apache License, Version 2.0.

