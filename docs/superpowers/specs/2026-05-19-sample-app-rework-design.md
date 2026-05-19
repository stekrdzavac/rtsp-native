# Sample App Rework — Design

**Date:** 2026-05-19
**Scope:** `sample/` module only. The library modules (`rtsp`, `rtspcore`,
`videorendering`, …) are not touched.

## Why

The current sample is a single scrolling screen with URL/username/password
inputs, mode toggles (Single / 2×2 / 3×3), and snapshot/record buttons. It
demonstrates everything in one cramped surface and doesn't reflect how a real
app would consume the library.

The reworked sample should:

1. Show how to feed a multi-camera config (`streams.json`) into the library at
   startup, instead of typing a URL each time.
2. Demonstrate two realistic surfaces: a single-camera full-width player and a
   2×2 multi-camera grid.
3. Use ordinary in-app navigation between a landing chooser and the two player
   screens.

## Non-goals

- No changes to the public library API.
- No new library features (snapshot/record already exist and are reused).
- No 3×3 / NxM grid — the rework deletes that mode.
- No persistent settings, no per-stream edit UI, no add/remove streams at
  runtime. `streams.json` is the source of truth and is edited on disk.
- No tests beyond a small unit test for the JSON loader. The sample app
  has no existing test coverage and this rework doesn't change that.

## User-facing flow

```
                 ┌──────────────────────────┐
                 │       LandingScreen      │
                 │                          │
                 │  [ Open 2x2 Grid ]       │
                 │                          │
                 │  ───── Streams ─────     │
                 │  • 192.168.0.203 …       │ ──tap row──┐
                 │  • lab.nittbit.com …     │            │
                 │  • …                     │            │
                 └──────────────────────────┘            │
                                                         ▼
                  ┌─────────────────────────────────────────┐
                  │           SingleStreamScreen            │
                  │                                         │
                  │  ┌───────────────────────────────────┐  │
                  │  │   video (full width, stream      │  │
                  │  │   aspect; 16:9 until resolution  │  │
                  │  │   is known)                       │  │
                  │  └───────────────────────────────────┘  │
                  │   status: 1920x1080 25fps 2048kbps      │
                  │   [Snapshot] [Record] [Stop Rec]        │
                  │   (back = system back)                  │
                  └─────────────────────────────────────────┘

           tap "Open 2x2 Grid"
                          │
                          ▼
                  ┌─────────────────────────────────────────┐
                  │             GridStreamScreen            │
                  │  ┌─────────────┐  ┌─────────────┐       │
                  │  │  stream[0]  │  │  stream[1]  │       │
                  │  │  25fps …    │  │  25fps …    │       │
                  │  └─────────────┘  └─────────────┘       │
                  │  ┌─────────────┐  ┌─────────────┐       │
                  │  │  stream[2]  │  │  stream[3]  │       │
                  │  │  25fps …    │  │  25fps …    │       │
                  │  └─────────────┘  └─────────────┘       │
                  └─────────────────────────────────────────┘
```

The system back button (BackHandler) returns from Single or Grid to Landing.

## Architecture

Single `MainActivity` hosting a sealed `Screen` state. No Navigation-Compose
dependency, no extra Activities.

```kotlin
sealed interface Screen {
    object Landing : Screen
    data class Single(val entry: StreamEntry) : Screen
    object Grid : Screen
}
```

### File layout

```
sample/
├── streams.json                                   ← editable, committed
├── build.gradle.kts                               ← + copy task
└── src/main/
    └── kotlin/com/skrdzavac/rtspnative/sample/
        ├── MainActivity.kt                        ← hosts Screen state
        ├── streams/
        │   ├── StreamEntry.kt                     ← data class
        │   ├── StreamsLoader.kt                   ← AssetManager + org.json
        │   └── StreamsResult.kt                   ← sealed: Ok / Error
        └── ui/
            ├── LandingScreen.kt
            ├── SingleStreamScreen.kt
            └── GridStreamScreen.kt
```

The Gradle build also generates `build/generated/sample-assets/streams.json`
from the module-root `streams.json`, and that directory is registered as an
additional assets `srcDir` — see "Asset copy strategy" below.

### Why one `streams.json` source + a Gradle copy

Keeping the file at `sample/streams.json` (module root) makes it the obvious
place to edit. But Android only ships files from `src/main/assets/`. A
`tasks.register<Copy>("syncStreamsJsonToAssets")` task wired into
`preBuild` copies the root file into the assets directory before compile.
That avoids two committed copies that drift, and avoids checking in a
generated artifact (the asset copy goes under `build/` … see "Asset copy
strategy" below for the actual decision).

### Asset copy strategy

There are two viable spots for the build-time copy destination:

| Destination | Pros | Cons |
| --- | --- | --- |
| `src/main/assets/` | Picked up automatically by the default assets source set. | The copied file sits inside the source tree; must be `.gitignore`d to avoid committing it. |
| `build/generated/assets/` + add to `android.sourceSets["main"].assets.srcDir(...)` | Stays out of the source tree; nothing to gitignore. | One extra line of source-set wiring. |

**Decision:** Use `build/generated/sample-assets/` and register it as an
additional assets source dir. This keeps the source tree clean and is the
standard pattern for build-generated assets.

## Data model

```kotlin
data class StreamEntry(
    val url: String,
    val username: String,    // "" if absent in JSON
    val password: String,    // "" if absent in JSON
)
```

`StreamsLoader` parses an array of JSON objects:

```json
[
  {
    "url": "rtsp://192.168.0.203/Streaming/Channels/101",
    "username": "admin",
    "password": "Admin12345"
  },
  {
    "url": "rtsp://lab.nittbit.com:60005/Streaming/Channels/101",
    "username": "admin",
    "password": "Admin12345"
  }
]
```

Parsing rules:

- Top level must be a JSON array.
- Each element must be a JSON object with a non-blank `url` field.
- `username` / `password` are optional. Missing or `null` → empty string.
- Any other parse failure → `StreamsResult.Error(message)`.

The loader has no Android dependencies beyond `AssetManager`; the actual JSON
parsing is `org.json.JSONArray` / `JSONObject`. The pure-string parsing
function (`parse(json: String): StreamsResult`) is separately testable on the
JVM.

## Credential merging

`RtspSessionConfiguration` already accepts a `Credentials?` argument and the
URL itself may also carry `user:pass@`. We pass:

- `url` as-is from the JSON entry,
- `Credentials(username, password)` only when `username.isNotBlank()`; else
  `null`.

This matches the existing `MainActivity` behavior. The library is the
authority on how the two credential sources combine; the sample doesn't
re-implement that logic.

## Screens

### LandingScreen

State it observes: `StreamsResult` produced once by `MainActivity` on first
composition.

UI:

- Top button: **"Open 2x2 Grid"**. Enabled only when `streams.size >= 4`.
  Tapping it sets `currentScreen = Screen.Grid`.
- A divider labelled "Streams".
- A `LazyColumn` of one row per stream. Row text = `host[:port]` of the
  parsed URL, followed by the path. Example:
  `192.168.0.203 — /Streaming/Channels/101`. The display label uses
  `java.net.URI` to split the URL; if the URL fails to parse, the row shows
  the raw URL string.
- Tapping a row → `currentScreen = Screen.Single(entry)`.

Error/edge states (rendered in place of the list):

- Missing `streams.json` (FileNotFoundException) → "No `streams.json` found
  in assets. Add a `streams.json` at the sample module root with
  `[{url, username, password}, …]`."
- Malformed JSON → "Failed to parse streams.json: {message}".
- Empty array → "streams.json is empty — add at least one entry."

The Grid button is also disabled (with a hint "Need 4+ streams") whenever
`streams.size < 4`, including the malformed/empty cases.

### SingleStreamScreen

Inputs: a single `StreamEntry`. Owns one `RtspSession`.

Layout (portrait, top to bottom):

1. Video surface: `Modifier.fillMaxWidth()` and an `aspectRatio` derived from
   `SessionStatistics.videoWidth / videoHeight`. Until the stats arrive (or
   if either is zero), default to `16f / 9f`. Background is black so the
   pre-stream and any letterbox area look consistent.
2. Status line: `"${w}x${h}  ${fps}fps  ${kbps}kbps  state=${state}"`.
3. Three buttons in a row:
   - **Snapshot** — same logic as today's sample. Last snapshot rendered
     below as a small `Image`.
   - **Record** — disabled unless `state is Playing && recorder == null`.
     Writes to `getExternalFilesDir(null) ?: filesDir` with filename
     `rtsp-yyyyMMdd-HHmmss.mp4`.
   - **Stop Rec** — visible/enabled when a recorder exists.
4. Recording status line when active: `"Recording (state): N KB → file.mp4"`.

Lifecycle: `DisposableEffect(session)` stops the session on dispose, mirroring
the current pattern. Back button (system or gesture) → Landing.

### GridStreamScreen

Inputs: `List<StreamEntry>` (always exactly 4 — Landing won't navigate here
otherwise).

Layout: a fixed 2×2 grid using nested `Row(Column(...))`. Each cell:

- `Modifier.weight(1f).aspectRatio(16f/9f)`. The grid uses a *fixed* 16:9
  aspect, not the stream's aspect — this keeps tiles aligned even if streams
  report different resolutions.
- Black background, full-cell `RtspVideoSurface`.
- A bottom-left overlay with `"${fps}fps ${kbps}kbps"` while playing, or the
  state name otherwise.

All four sessions are created once on first composition (`remember(streams)`),
muted by default (`audioRenderer.isMuted = true`), and started immediately.
A `DisposableEffect` stops all four on dispose.

## State / lifecycle rules

- `currentScreen` is a `var ... by remember { mutableStateOf<Screen>(Landing) }`
  on `MainActivity`'s root composable. No saved-state restoration — config
  changes are handled by `android:configChanges` on the activity (already
  present in the manifest), so the state survives rotation in practice.
- `BackHandler(enabled = currentScreen !is Landing) { currentScreen = Landing }`
  inside `MainActivity`'s composable handles back navigation.
- `FLAG_KEEP_SCREEN_ON` stays on at the activity level (matches today's
  behavior).

## What gets deleted

- The old in-screen URL / username / password text inputs.
- The Single / 2×2 / 3×3 toggle row.
- The 3×3 grid mode.
- The single giant `MainActivity.kt` (~360 lines) is split into the files
  listed under "File layout".

## Testing

`StreamsLoaderTest` (JVM, plain JUnit):

- Parses a valid JSON array with `url`, `username`, `password`.
- Parses an entry with only `url` → username and password are empty strings.
- Rejects a top-level JSON object (not an array).
- Rejects an array element missing `url`.
- Rejects an element with a blank `url`.
- Returns `Error` with the underlying `JSONException` message on malformed
  input.

These exercise the `parse(json: String)` overload that takes a String — the
`AssetManager`-backed wrapper is not unit tested (trivial I/O).

## Build changes

`sample/build.gradle.kts`:

```kotlin
android {
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/sample-assets"))
}

val syncStreamsJsonToAssets by tasks.registering(Copy::class) {
    from(layout.projectDirectory.file("streams.json"))
    into(layout.buildDirectory.dir("generated/sample-assets"))
}
tasks.named("preBuild") { dependsOn(syncStreamsJsonToAssets) }
```

The dependency list gains nothing (org.json ships with Android; JUnit is
already there).

A starter `sample/streams.json` is committed with 4 entries so the 2×2 grid
works out of the box: entries 0 and 1 are the existing
`SAMPLE_H264_RTSP_URL` and `SAMPLE_H265_RTSP_URL` from the current
`MainActivity.kt`; entries 2 and 3 duplicate the H.265 URL as placeholders.
Users will edit `streams.json` to point at their own cameras.

`sample/.gitignore` already covers `build/`; no further gitignore changes
needed since the generated asset copy lives under `build/`.

## Risks / open questions

- **`org.json` on the JVM unit-test classpath.** The standard Android JAR
  ships stubs (`org.json` throws "not mocked" at test time). If
  `StreamsLoaderTest` hits that, we add `testImplementation("org.json:json:20240303")`
  to provide a real implementation for tests only. This is the standard
  workaround and is not a runtime change.
- **`URI.parse` for display labels** can throw on weird URLs (spaces, etc.).
  Wrapped in try/catch → fall back to showing the raw URL.
- **Sessions in the grid hammering the same camera.** Today's sample already
  does this in the 2×2 mode; the rework preserves that behavior. Real users
  will edit `streams.json` to point at four different cameras.
