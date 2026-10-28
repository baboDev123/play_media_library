# Changelog

All notable changes to PlayMedia. Versions follow [Semantic Versioning](https://semver.org):
a major version for breaking API changes, a minor one for new features, a patch for fixes.

## [1.1.0] - 2026-10-28

### Added
- **`media-extractor`**: FFmpeg reads containers Media3 can't.
  - `PlayMeExtractorsFactory` keeps Media3's extractors first and adds `FfmpegExtractor` for
    local files: after them for unknown containers, before them for MP4s whose index exceeds
    40 MB (Media3 keeps it in the Java heap and runs out of memory) and for files marked with
    `FfmpegFallback.prefer(uri)` after a failure.
  - Codec configuration in Media3's form (H.264/HEVC, AAC, Opus, Vorbis, FLAC, ALAC), NAL
    length prefixes to Annex-B, edit-list cuts, colour information for HDR, index-based seeking.
  - A 27-hour MP4 with a 114 MB index plays within a 192 MB Java heap (5-16 MB used), where
    Media3 failed with `OutOfMemoryError`.
- `PlayMediaNative.version`.
- Windows Media Video 9 (`video/x-ms-wmv`) for files read by FFmpeg.

### Tests
- 17 device tests: FFmpeg reads MP4, Matroska, WebM, MPEG-TS, AVI, FLV, 3GP, AMR and FLAC
  to the same frames and audio as Media3; seeking; files Media3 reads stay with Media3.

## [1.0.0] - 2026-10-28

First release of the rebuilt engine.

### Added
- **`media-core`**: one native library (`libplaymedia.so`) with FFmpeg 7.1.1 and dav1d 1.5.1
  linked statically, LGPL-only (`ffmpeg/build.sh` refuses GPL or nonfree), 16 KB pages.
- **`media-decoder`**
  - `PlayMeVideoRenderer`: H.264, HEVC (8/10-bit), VP8, VP9, AV1, MPEG-1/2/4, H.263, VC-1,
    MJPEG; every frame handed over, including the last ones; drawn with OpenGL ES using the
    frame's colour matrix.
  - `PlayMeAudioRenderer`: AC-3, E-AC-3, DTS, TrueHD, FLAC, Opus, Vorbis, ALAC, AAC, MPEG
    audio, AMR, G.711, as 16-bit PCM up to 8 channels.
  - `DecoderManager`: switches decoders (`AUTO`, `HARDWARE`, `SYSTEM_SOFTWARE`, `FFMPEG`)
    while playing, keeping the player and its media session.
  - `SubtitleTiming`: subtitle delay and speed.
- **`media-info`**: `MediaInfoExtractor` (streams, HDR, chapters, attachments, cover art) and
  `ThumbnailExtractor`, from paths, content URIs, descriptors or `MediaDataSource`, with
  timeouts.

### Tests
- 45 device tests against AndroidX Media's sample media: frame counts equal to the device
  decoders', full-length audio, on-screen colours, decoder switching, native memory.

## [0.x] - before 2026-10-28

Prototype. Its native code never built against FFmpeg (it compiled in stub mode) and was
replaced in 1.0.0.

[1.1.0]: https://github.com/baboDev123/play_media_library/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/baboDev123/play_media_library/releases/tag/v1.0.0
