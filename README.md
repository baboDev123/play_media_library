# PlayMedia

FFmpeg decoding, media info and thumbnails for Android **Media3 (ExoPlayer)**.

- **Video**: H.264, HEVC (8 and 10-bit), VP8, VP9, AV1 (dav1d), MPEG-1/2, MPEG-4 Part 2, H.263, VC-1, MJPEG
- **Audio**: AC-3, E-AC-3 (incl. JOC), DTS, DTS-HD (core), TrueHD, FLAC, Opus, Vorbis, ALAC, AAC, MP1/2/3, AMR-NB/WB, G.711
- **Media info**: streams, codecs, resolution, frame rate, HDR type and bit depth, rotation, languages, chapters, attachments (subtitle fonts), cover art
- **Thumbnails**: key frame or exact frame, upright, scaled to fit, cover art for audio

Min SDK 23 · Media3 1.11.1 · arm64-v8a, armeabi-v7a, x86_64, x86 · 16 KB page aligned

## Modules

| Module | What it holds |
|---|---|
| `media-core` | The native engine: one `libplaymedia.so` with FFmpeg and dav1d linked in statically. Pulled in by the other two. |
| `media-decoder` | Media3 renderers: `PlayMeRenderersFactory`, `DecoderManager`, `DecoderMode`, `SubtitleTiming`, `PlayMeVideoRenderer`, `PlayMeAudioRenderer`, `DecoderController` |
| `media-info` | `MediaInfoExtractor`, `ThumbnailExtractor` |

## Setup

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.baboDev123.play_media_library:media-decoder:<version>")
    implementation("com.github.baboDev123.play_media_library:media-info:<version>")
}
```

To build it yourself, run `ffmpeg/build.sh` once (it downloads FFmpeg and dav1d and builds them
for every ABI into `ffmpeg/output`), then build with Gradle as usual.

## Playback

```kotlin
val renderers = PlayMeRenderersFactory(context, DecoderMode.AUTO)
val player = ExoPlayer.Builder(context, renderers).build()
```

| `DecoderMode` | Video | Audio |
|---|---|---|
| `AUTO` | Device decoders, FFmpeg for what they can't play | Device decoders, FFmpeg for the rest |
| `HARDWARE` | Hardware decoders only | Device decoders, FFmpeg for the rest |
| `SYSTEM_SOFTWARE` | Android's software decoders only | Device decoders, FFmpeg for the rest |
| `FFMPEG` | FFmpeg first | FFmpeg first |

FFmpeg video is drawn straight onto the player's `Surface` (`SurfaceView` or `TextureView`) as
YV12: 8-bit 4:2:0 video, nearly all of it, is copied plane by plane with no colour conversion on
the CPU. Media3 times every frame and drops late ones. Audio comes out as 16-bit PCM, so Media3's
speed, pitch and volume processing still apply.

### Subtitle timing

```kotlin
val timing = SubtitleTiming()
val renderers = PlayMeRenderersFactory(context, DecoderMode.AUTO, timing)

timing.delayMs = 500   // show subtitles half a second later
timing.speed = 1.042f  // subtitles made for 25 fps on a 23.976 fps video
```

Share one `SubtitleTiming` between factories and the setting survives a player rebuild.

### Switching decoders while playing

`DecoderManager` changes decoders in place: the player, its media session and the playlist
stay, and Media3 moves the track to the new decoder at the current position.

```kotlin
val decoders = DecoderManager()
val player = ExoPlayer.Builder(context, PlayMeRenderersFactory(context, decoderManager = decoders)).build()
decoders.attach(player)

decoders.selectVideoDecoder(DecoderMode.FFMPEG)
decoders.activeVideoMode // FFMPEG once it took over; AUTO while the device's decoders play
```

`attach` needs ExoPlayer's default track selector (`DefaultTrackSelector`). With no decoder of
the asked-for kind (say `HARDWARE` for a format the hardware can't decode) the track stays
unplayed; the app decides what to fall back to.

`DecoderController` instead rebuilds the player for each change, carrying over position,
play/pause, speed and track selection, for apps without a media session.

## Media info

```kotlin
val info = MediaInfoExtractor.extract(context, uri)      // suspend; also file paths and descriptors
info.primaryVideo?.hdrType                               // NONE, HDR10, HLG, DOLBY_VISION
info.audioStreams.map { it.language }
info.chapters
info.embeddedFonts                                       // for ASS subtitles
```

Network files are read through an `android.media.MediaDataSource` with random access, so
containers that keep their index at the end (most MP4s) work too:

```kotlin
val info = MediaInfoExtractor.extract(object : MediaDataSource() {
    override fun getSize() = remoteFile.length
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) =
        remoteFile.read(position, buffer, offset, size)
    override fun close() = Unit
})
```

A plain `InputStream` works too, but only forwards. Every read gives up after a timeout
(`timeoutMs`, 15 s by default) and errors are thrown as `PlayMediaException`.

## Thumbnails

```kotlin
val bitmap = ThumbnailExtractor.getFrameAt(context, uri, timeUs = 5_000_000, targetWidth = 320, targetHeight = 180)
```

The default is the key frame at or before the time, which is fast; `exact = true` decodes on to
the frame at the time. Frames come out upright and fitted inside the target size. Audio files give
their cover picture.

## Java

The blocking (`extractSync`, `getFrameAtSync`) and callback (`extractAsync`, `getFrameAtAsync`)
functions are `@JvmStatic` with `@JvmOverloads`.

## Tests

- `./gradlew :media-decoder:testDebugUnitTest`: subtitle timing
- `./gradlew :media-tests:connectedDebugAndroidTest`: plays AndroidX Media's sample media on a
  device. FFmpeg must give as many video frames as the device's own decoder and the full length
  of audio. It also checks media info from every kind of input, thumbnails, timeouts, and that
  native memory stays flat over repeated use.

## Licences

PlayMedia's own code is under the Apache License 2.0 (`LICENSE`). It links
[FFmpeg](https://ffmpeg.org) under the **LGPL 2.1 or later** and
[dav1d](https://code.videolan.org/videolan/dav1d) under the BSD 2-Clause licence. FFmpeg is built
without `--enable-gpl` and `--enable-nonfree`, and `ffmpeg/build.sh` refuses a GPL configuration.
Apps that ship PlayMedia must follow those licences: see `NOTICE`.
