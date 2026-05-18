# RTSPKit-Android

A pure-Kotlin RTSP client library for Android. Opens RTSP streams from IP
cameras and plays them back using the Android platform's native media
stack — no FFmpeg, no libVLC, no C/C++, no JNI.

The protocol layer (RTSP/RTP/RTCP/SDP) is hand-rolled in Kotlin on top of
`java.net.Socket` and `DatagramSocket`. Decoding goes through
`MediaCodec`. Video renders to a `Surface` (zero-copy); audio plays via
`AudioTrack`. The only software codec is G.711 (a trivial lookup table).

> **Status**: pre-1.0. API may change without notice. Tested against
> Hikvision IP cameras over LAN and WAN. Ships in this repo's `:sample`
> app.

## Features

| Area              | What works                                                            |
|-------------------|-----------------------------------------------------------------------|
| Video codecs      | H.264 (RFC 6184), H.265 / HEVC (RFC 7798)                             |
| Audio codecs      | AAC (RFC 3640), G.711 µ-law / A-law (RFC 3551), L16                   |
| RTSP control      | OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER (keepalive)   |
| Auth              | Basic (RFC 7617), Digest MD5 / MD5-sess (RFC 7616 vector verified)    |
| Transport         | TCP-interleaved (RFC 2326 §10.12), UDP unicast                        |
| Reconnect         | Exponential backoff on stall, keepalive failure, or socket drop       |
| A/V sync          | Cross-track via RTCP Sender Reports + audio-anchored presentation     |
| Live stats        | fps, kbps, frames decoded, frames dropped, resolution                 |
| Recording         | MP4 via `MediaMuxer`, no re-encode (Annex-B → AVCC remux)             |
| Snapshot          | `PixelCopy` to `Bitmap` at native resolution                          |
| Multi-stream      | N independent sessions; validated with 2×2 / 3×3 grid in the sample   |

## Module layout

```
rtsp                  public entry point (RtspSession, RtspSessionConfiguration, RtspRecorder)
├── rtspsignaling     RTSP control + SDP + auth
├── rtsptransport     TCP-interleaved + UDP transports
├── h264depacketizer  RFC 6184 NAL reassembly
├── h265depacketizer  RFC 7798 NAL reassembly
├── audiodepacketizer AAC AU-headers / G.711 / L16
├── videodecoder      MediaCodec H.264 / H.265 → Surface
├── audiodecoder      MediaCodec AAC + Kotlin G.711 lookup tables
├── videorendering    RtspVideoView (SurfaceView) + Compose wrapper
├── audiorendering    RtspAudioRenderer (AudioTrack)
├── clocksync         AvSyncClock + RTP timestamp unwrap
└── rtspcore          shared types (RtpPacket, RtcpPacket, codecs, errors)
```

Most consumers only depend on `:rtsp` and `:videorendering` — the
former pulls everything else in transitively.

## Install

The library publishes to MavenLocal out of the box. Run once from the
repo root:

```bash
./gradlew publishToMavenLocal
```

Then in your consuming app's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

…and in your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.skrdzavac.rtspnative:rtsp:0.1.0")
    implementation("com.skrdzavac.rtspnative:videorendering:0.1.0")
}
```

For JitPack consumption, tag a release and JitPack will run
`publishToMavenLocal` for you.

## Quick start (Jetpack Compose)

```kotlin
@Composable
fun CameraView(url: String, username: String, password: String) {
    val session = remember(url) {
        RtspSession(
            RtspSessionConfiguration(
                url = url,
                credentials = Credentials(username, password),
            )
        ).also { it.start() }
    }
    DisposableEffect(session) {
        val captured = session
        onDispose { captured.stop() }
    }
    val state by session.state.collectAsState()
    val stats by session.statistics.collectAsState()

    Column {
        Text("State: ${state::class.simpleName}")
        Text("${stats.videoWidth}x${stats.videoHeight}  ${stats.fps.toInt()}fps  ${stats.bitrateBps / 1000}kbps")
        RtspVideoSurface(session = session, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
    }
}
```

## Quick start (Views)

```kotlin
val session = RtspSession(
    RtspSessionConfiguration(
        url = "rtsp://192.168.1.10:554/Streaming/Channels/101",
        credentials = Credentials("admin", "1234"),
    )
)
session.start()
val view = RtspVideoView(context).apply { attach(session) }
// add view to your layout
// later: view.detach(); session.stop()
```

## Public API surface

### Session

- **`RtspSession(config)`** — construct with the URL, credentials, and
  transport preference. Owns its coroutine scope. Idempotent `start()` /
  `stop()`.
- **`session.state: StateFlow<RtspSessionState>`** — `Idle`, `Connecting`,
  `Authenticating`, `Negotiating`, `Playing`, `Stalled`, `Reconnecting`,
  `Failed(error)`, `Stopped`.
- **`session.statistics: StateFlow<SessionStatistics>`** — `fps`,
  `bitrateBps`, `videoWidth`, `videoHeight`, `framesDecoded`,
  `framesDropped`.
- **`session.videoSize: StateFlow<Pair<Int, Int>?>`** — decoded video
  dimensions, populated after first OUTPUT_FORMAT_CHANGED from the
  decoder.
- **`session.audioRenderer.isMuted`** / **`volume`** — runtime audio
  control. Survives reconnects.
- **`session.snapshot(): Bitmap?`** — capture the current frame at
  native resolution. Requires an attached `RtspVideoView`.
- **`session.startRecording(file): RtspRecorder?`** — write the live
  stream to MP4. Waits for an IDR keyframe before the first sample.
  Includes AAC audio when present.
- **`session.stopRecording()`** — finalize the MP4.

### Configuration

```kotlin
RtspSessionConfiguration(
    url: String,                    // rtsp:// URL
    credentials: Credentials? = null,
    transport: TransportPreference = Auto,        // Auto / TcpInterleaved / Udp
    videoOnly: Boolean = false,
    connectTimeoutMs: Int = 8_000,
    firstFrameTimeoutMs: Int = 10_000,
    keepaliveIntervalMs: Long = 30_000,
    reconnect: ReconnectPolicy =                  // .Never or .ExponentialBackoff(...)
        ReconnectPolicy.ExponentialBackoff(500, 30_000, 250),
    preferredVideoCodec: List<VideoCodec> = listOf(H264, H265),
    preferredAudioCodec: List<AudioCodec> = listOf(AAC, PCMU, PCMA),
    bufferingPolicy: BufferingPolicy = LowLatency, // .LowLatency / .Balanced / .HighLatencyTolerant
)
```

### Views

- **`RtspVideoView`** — `FrameLayout` containing a `SurfaceView` configured
  for zero-copy MediaCodec rendering. `attach(session)` / `detach()`.
  Aspect ratio comes from the decoded video size.
- **`@Composable RtspVideoSurface(session, modifier)`** — Compose
  wrapper over `RtspVideoView` via `AndroidView`.

### Recording

- **`RtspRecorder.file: File`** — output path.
- **`RtspRecorder.state: StateFlow<State>`** — `Idle`, `Recording`,
  `Stopped`, `Failed`.
- **`RtspRecorder.bytesWritten: StateFlow<Long>`** — live byte counter.
- **`RtspRecorder.stop()`** — finalize the MP4. Always called
  automatically when the session stops.

## Architectural rules

This library exists specifically to *not* bundle native code. See
`CLAUDE.md` for the complete rules; the highlights:

- No `.cpp` / `.c`, no NDK, no `System.loadLibrary`, no JNI.
- No FFmpeg, no libVLC, no gstreamer, no ExoPlayer's RTSP module, no
  MediaPlayer fallback.
- RTSP/RTP/RTCP/SDP are byte-built and byte-parsed in pure Kotlin.
- Decoding via `MediaCodec`. Rendering via native `Surface` / `AudioTrack`.
- G.711 is the only software codec (~30 lines of lookup-table work).

## Known gaps (out of current scope)

- MJPEG over RTP
- MPEG-4 Part 2 (legacy)
- ONVIF metadata / PTZ / event channels
- RTSP server role (this is a client only)
- NAT punching for UDP-over-WAN — UDP works on LAN; use TCP-interleaved
  (the default) for WAN.

## License

TBD.
