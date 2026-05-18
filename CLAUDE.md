# RTSPKit-Android

A pure-Kotlin RTSP client library for Android. It opens RTSP streams from IP
cameras and plays them back using the Android platform's native media stack —
no C/C++, no JNI, no bundled codecs.

## What this project is

- A reusable Android library (the `rtsp` module is the public entry point)
- Plus a `sample` app that demonstrates a minimal player
- Target: Android `minSdk 24`, `compileSdk 35`, Kotlin 2.0, Java 17

## What it does

1. Speaks the RTSP/1.0 control protocol against an IP camera server
   (OPTIONS / DESCRIBE / SETUP / PLAY / TEARDOWN), including Basic and MD5
   Digest authentication.
2. Receives RTP media + RTCP control over either UDP or RTSP TCP-interleaved
   transport (RFC 2326 §10.12), depending on what the server allows.
3. Reassembles RTP payloads into encoded access units / audio frames
   (H.264 / H.265 NAL reassembly: single, STAP-A/AP, FU-A/FU).
4. Hands encoded frames to Android's `MediaCodec` for hardware decode
   (H.264, H.265, AAC) or decodes G.711 μ-law / A-law in Kotlin (trivial).
5. Renders video to a `Surface` (composed by SurfaceFlinger) and audio to
   `AudioTrack`.
6. Resolves wall-clock PTS from RTCP Sender Reports for A/V sync, with 32-bit
   RTP timestamp unwrapping for long-running streams.

## The reason this exists

Android does not expose a complete public RTSP client API that handles the
variety of formats and quirks of real IP cameras. `MediaPlayer`'s RTSP support
is limited and opaque; ExoPlayer's RTSP module has format/auth gaps. This
library fills that gap **without** falling back to bundled native code
(FFmpeg / libVLC / gstreamer).

## Non-negotiable architectural rules

These rules define what this project IS. Do not violate them without an
explicit conversation with the maintainer.

### 1. No native code in the library

- No `.cpp` / `.c` / `.h` files
- No `CMakeLists.txt`, no `externalNativeBuild` block, no `ndk` config
- No `System.loadLibrary`, no `external fun`, no JNI
- No native-backed dependencies: **never** add FFmpeg, libVLC, gstreamer,
  ExoPlayer's RTSP module, MediaPlayer-based fallbacks, or any artifact that
  ships a `.so`
- If a feature seems to require native code, stop and discuss before adding it

### 2. RTSP / RTP / RTCP / SDP are implemented in Kotlin

- Protocol bytes are built and parsed by hand using `java.net.Socket`,
  `ByteBuffer`, and Kotlin
- Do not pull in a third-party RTSP/RTP/SDP library
- Hand-rolled is the point — that's what makes this library possible without
  native code

### 3. Decoding goes through `android.media.MediaCodec`

- H.264, H.265, AAC → `MediaCodec.createDecoderByType(...)`
- Video decoder output goes directly to a `Surface` (zero-copy render path)
- The only exception is G.711 μ-law / A-law, which is a trivial lookup-table
  conversion done in Kotlin in `audiodecoder/G711Decoder.kt`. Don't add more
  software codecs — if a new codec is needed, route it through MediaCodec
- Don't write a custom decoder for any format MediaCodec already supports

### 4. Rendering uses Android's native surfaces

- Video: `Surface` from `SurfaceTexture` / `SurfaceView`, fed to MediaCodec
  as the decoder output surface
- Audio: `android.media.AudioTrack` with PCM data
- Don't introduce a custom GL renderer, a `Bitmap`-per-frame path, or anything
  that copies decoded frames through user space

### 5. Module boundaries follow the pipeline

The codebase mirrors the streaming pipeline. Each layer depends only on
`rtspcore` and on layers below it — never on layers above.

```
sample
  └─ rtsp                             (orchestration: RtspSession)
       ├─ rtspsignaling               (RTSP control + SDP + auth)
       ├─ rtsptransport               (UDP / TCP-interleaved RTP/RTCP I/O)
       ├─ h264depacketizer            (NAL reassembly: single / STAP-A / FU-A)
       ├─ h265depacketizer            (NAL reassembly: single / AP / FU)
       ├─ audiodepacketizer           (AAC / G.711 / L16 payload framing)
       ├─ videodecoder                (MediaCodec wrapper + SpsParser)
       ├─ audiodecoder                (MediaCodec for AAC, Kotlin for G.711)
       ├─ videorendering              (Surface / SurfaceTexture views)
       ├─ audiorendering              (AudioTrack wrapper)
       ├─ clocksync                   (RTCP SR → wall-clock PTS, ts unwrap)
       └─ rtspcore                    (RtpPacket / RtcpPacket / Codec / models)
```

Rules of thumb:
- A depacketizer must not know about MediaCodec
- A decoder must not know about RTP
- A transport must not know about codecs
- `rtspcore` is the only module everyone can depend on
- New protocol-level concerns live in `rtspsignaling` or `rtspcore`, not in
  `rtsp`

### 6. Concurrency model

- Kotlin coroutines + `Flow` for async I/O and frame plumbing
- `RtspSession` owns its `CoroutineScope` and is responsible for cleanup on
  stop/error
- Don't introduce raw `Thread`s or `Executor`s unless a Java/Android API
  forces it

## Public API surface

The library's public entry points (anything outside these is internal):

- `RtspSession` — create, configure, `start()`, observe `state` and
  `statistics`, `stop()`
  (`rtsp/src/main/kotlin/com/skrdzavac/rtspnative/RtspSession.kt`)
- `RtspSessionConfiguration`, `ReconnectPolicy`, `BufferingPolicy` — knobs
- `RtspVideoView` / `RtspTextureView` — view to `attach(session)`
- `RtspAudioRenderer` — audio output sink
- `SessionStatistics` — live frame-rate, bitrate, resolution flow

The sample app (`sample/`) is the reference for how to wire these together.

## Build & tooling

- Gradle Kotlin DSL (`build.gradle.kts`)
- Kotlin 2.0.0, AGP per root `build.gradle.kts`, JVM target 17
- `./gradlew test` runs unit tests across modules
- `./gradlew :sample:installDebug` builds and installs the demo app

## Working in this codebase

- **Pre-implementation search:** before writing a new utility, grep for an
  existing one. The pipeline modules are small and focused — duplicate
  parsing / buffer helpers tend to drift
- **Don't bleed layers:** if a fix in `videodecoder` seems to need RTP
  timestamps, plumb them through `rtspcore` types instead of importing the
  depacketizer
- **Tests:** unit tests live next to each module under `src/test/kotlin/`.
  Add a test for any new depacketizer fragmentation case or auth header
  variation
- **Camera quirks:** real IP cameras deviate from spec. When fixing a
  compatibility issue, capture the offending header / packet pattern in the
  test that locks the fix in
- **No emoji in source, no narrating comments.** Comments explain *why* only.

## Known gaps (not bugs — out of current scope)

- MJPEG over RTP (legacy / lower-end cameras)
- MPEG-4 Part 2 (legacy)
- ONVIF metadata / PTZ / event channels
- Recording / re-muxing to a file
- RTSP server role (this is a client only)

Adding any of these must preserve the pure-Kotlin rule above.
