# Sample App Rework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current single-screen sample app with a 3-screen layout (landing → single-stream OR 2×2 grid) that reads camera configuration from `streams.json`.

**Architecture:** Single `MainActivity` hosts a sealed `Screen` state; the three screens are top-level composables in `ui/`. Stream configuration is read once at startup from `assets/streams.json` (sourced from `sample/streams.json` via a Gradle copy task into a generated assets dir). The library API is untouched.

**Tech Stack:** Kotlin 2.3, Jetpack Compose, AGP 9.2.1, `org.json` (built into Android), JUnit 4, project's existing `rtsp`/`videorendering` library modules.

**Spec:** `docs/superpowers/specs/2026-05-19-sample-app-rework-design.md`

---

## Reference: key library APIs the sample consumes

These exist in the project today — do not modify them. Use them as documented below.

| Symbol | Location | Notes |
| --- | --- | --- |
| `class RtspSession(config: RtspSessionConfiguration)` | `rtsp/.../RtspSession.kt` | `.start()`, `.stop()`, `.state: StateFlow<RtspSessionState>`, `.statistics: StateFlow<SessionStatistics>`, `.audioRenderer.isMuted = Boolean`, `suspend fun snapshot(): Bitmap?`, `fun startRecording(file: File): RtspRecorder?`, `fun stopRecording()` |
| `data class RtspSessionConfiguration(url, credentials, ...)` | `rtsp/.../RtspSessionConfiguration.kt` | Only `url` and `credentials` matter for the sample |
| `data class Credentials(username, password)` | `rtspcore/.../Credentials.kt` | Pass `null` for no auth |
| `data class SessionStatistics(bitrateBps, fps, videoWidth, videoHeight, framesDecoded, framesDropped)` | `rtspcore/.../SessionStatistics.kt` | All fields default to 0; `videoWidth`/`videoHeight` are 0 until SPS is parsed |
| `sealed class RtspSessionState` | `rtspcore/.../RtspSessionState.kt` | `Idle`, `Connecting`, `Playing`, `Failed(error)`, etc. |
| `@Composable fun RtspVideoSurface(session, modifier)` | `videorendering/.../RtspVideoSurface.kt` | Sized by caller; the composable just fills its constraints |
| `class RtspRecorder` | `rtsp/.../RtspRecorder.kt` | `.file: File`, `.state: StateFlow<...>`, `.bytesWritten: StateFlow<Long>` |

---

## Task 1: Add `streams.json` + Gradle copy + assets source-set wiring

This task makes the asset file exist in the APK without yet reading it. The app keeps running its current UI.

**Files:**
- Create: `sample/streams.json`
- Modify: `sample/build.gradle.kts` (add `sourceSets` wiring + copy task)

- [ ] **Step 1: Create `sample/streams.json`**

Write to `sample/streams.json`:

```json
[
  {
    "url": "rtsp://192.168.0.203/Streaming/Channels/101?transportmode=unicast&profile=Profile_1",
    "username": "admin",
    "password": "Admin12345"
  },
  {
    "url": "rtsp://lab.nittbit.com:60005/Streaming/Channels/101?transportmode=unicast&profile=Profile_1",
    "username": "admin",
    "password": "Admin12345"
  },
  {
    "url": "rtsp://lab.nittbit.com:60005/Streaming/Channels/101?transportmode=unicast&profile=Profile_1",
    "username": "admin",
    "password": "Admin12345"
  },
  {
    "url": "rtsp://lab.nittbit.com:60005/Streaming/Channels/101?transportmode=unicast&profile=Profile_1",
    "username": "admin",
    "password": "Admin12345"
  }
]
```

The two URLs that already live in `MainActivity.kt` (`SAMPLE_H264_RTSP_URL`, `SAMPLE_H265_RTSP_URL`) are entries 0 and 1; entries 2 and 3 duplicate the H.265 URL as placeholders so 2×2 grid is usable out of the box.

- [ ] **Step 2: Add copy task + source-set wiring to `sample/build.gradle.kts`**

Open `sample/build.gradle.kts`. Inside the existing `android { ... }` block, after the `buildFeatures { compose = true }` block, add:

```kotlin
    sourceSets["main"].assets.srcDir(
        layout.buildDirectory.dir("generated/sample-assets")
    )
```

Then below the `android { ... }` block (above `dependencies { ... }`), add:

```kotlin
val syncStreamsJsonToAssets by tasks.registering(Copy::class) {
    from(layout.projectDirectory.file("streams.json"))
    into(layout.buildDirectory.dir("generated/sample-assets"))
}

tasks.named("preBuild") {
    dependsOn(syncStreamsJsonToAssets)
}
```

- [ ] **Step 3: Verify the asset is bundled**

Run:

```bash
./gradlew :sample:assembleDebug
unzip -p sample/build/outputs/apk/debug/sample-debug.apk assets/streams.json | head -5
```

Expected: prints the first few lines of `streams.json`. If you get `caution: filename not matched`, the source-set wiring isn't picked up — verify Step 2 was added inside the `android { ... }` block.

- [ ] **Step 4: Verify the existing sample still runs**

Run:

```bash
./gradlew :sample:installDebug
```

Expected: BUILD SUCCESSFUL. App is installed and launches as it did before (URL/username/password inputs, mode toggles). We haven't changed runtime behavior yet.

- [ ] **Step 5: Commit**

```bash
git add sample/streams.json sample/build.gradle.kts
git commit -m "chore(sample): add streams.json + build-time copy to assets"
```

---

## Task 2: `StreamEntry` data class

**Files:**
- Create: `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/streams/StreamEntry.kt`

- [ ] **Step 1: Create `StreamEntry.kt`**

Write to the file above:

```kotlin
// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.streams

data class StreamEntry(
    val url: String,
    val username: String,
    val password: String,
)
```

- [ ] **Step 2: Verify it compiles**

Run:

```bash
./gradlew :sample:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/streams/StreamEntry.kt
git commit -m "feat(sample): add StreamEntry data class"
```

---

## Task 3: `StreamsResult` sealed type + `StreamsLoader.parse(String)` with TDD

This is the only piece of the sample with unit tests. The parser runs on plain JVM (no Android dependencies beyond what unit tests have).

**Files:**
- Create: `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/streams/StreamsResult.kt`
- Create: `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/streams/StreamsLoader.kt`
- Create: `sample/src/test/kotlin/com/skrdzavac/rtspnative/sample/streams/StreamsLoaderTest.kt`
- Modify: `sample/build.gradle.kts` (add `org.json:json` test dependency)

### Why a test dependency?

The default Android JAR ships `org.json` as stubs that throw "not mocked" in JVM unit tests. We add a real `org.json:json` only on the test classpath — the runtime APK is unchanged.

- [ ] **Step 1: Add `org.json` test dependency**

In `sample/build.gradle.kts`, inside the `dependencies { ... }` block, find the line `testImplementation(libs.junit)` and add right below it:

```kotlin
    testImplementation("org.json:json:20240303")
```

- [ ] **Step 2: Create `StreamsResult.kt`**

Write to `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/streams/StreamsResult.kt`:

```kotlin
// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.streams

sealed interface StreamsResult {
    data class Ok(val entries: List<StreamEntry>) : StreamsResult
    data class Error(val message: String) : StreamsResult
}
```

- [ ] **Step 3: Write the failing test file**

Write to `sample/src/test/kotlin/com/skrdzavac/rtspnative/sample/streams/StreamsLoaderTest.kt`:

```kotlin
// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamsLoaderTest {

    @Test
    fun parses_valid_array_with_credentials() {
        val json = """
            [
              {"url":"rtsp://host/a","username":"u","password":"p"},
              {"url":"rtsp://host/b","username":"x","password":"y"}
            ]
        """.trimIndent()

        val result = StreamsLoader.parse(json)

        assertTrue(result is StreamsResult.Ok)
        val entries = (result as StreamsResult.Ok).entries
        assertEquals(2, entries.size)
        assertEquals(StreamEntry("rtsp://host/a", "u", "p"), entries[0])
        assertEquals(StreamEntry("rtsp://host/b", "x", "y"), entries[1])
    }

    @Test
    fun missing_username_and_password_default_to_empty_string() {
        val json = """[{"url":"rtsp://host/a"}]"""

        val result = StreamsLoader.parse(json)

        assertTrue(result is StreamsResult.Ok)
        val entry = (result as StreamsResult.Ok).entries.single()
        assertEquals("rtsp://host/a", entry.url)
        assertEquals("", entry.username)
        assertEquals("", entry.password)
    }

    @Test
    fun null_username_and_password_become_empty_string() {
        val json = """[{"url":"rtsp://host/a","username":null,"password":null}]"""

        val result = StreamsLoader.parse(json)

        assertTrue(result is StreamsResult.Ok)
        val entry = (result as StreamsResult.Ok).entries.single()
        assertEquals("", entry.username)
        assertEquals("", entry.password)
    }

    @Test
    fun top_level_object_is_rejected() {
        val json = """{"url":"rtsp://host/a"}"""

        val result = StreamsLoader.parse(json)

        assertTrue(result is StreamsResult.Error)
    }

    @Test
    fun entry_without_url_is_rejected() {
        val json = """[{"username":"u","password":"p"}]"""

        val result = StreamsLoader.parse(json)

        assertTrue(result is StreamsResult.Error)
        assertTrue((result as StreamsResult.Error).message.contains("url"))
    }

    @Test
    fun entry_with_blank_url_is_rejected() {
        val json = """[{"url":"   "}]"""

        val result = StreamsLoader.parse(json)

        assertTrue(result is StreamsResult.Error)
        assertTrue((result as StreamsResult.Error).message.contains("url"))
    }

    @Test
    fun malformed_json_returns_error() {
        val json = "[ this is not json"

        val result = StreamsLoader.parse(json)

        assertTrue(result is StreamsResult.Error)
    }

    @Test
    fun empty_array_is_ok_with_no_entries() {
        val result = StreamsLoader.parse("[]")

        assertTrue(result is StreamsResult.Ok)
        assertEquals(0, (result as StreamsResult.Ok).entries.size)
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run:

```bash
./gradlew :sample:testDebugUnitTest --tests com.skrdzavac.rtspnative.sample.streams.StreamsLoaderTest
```

Expected: compilation failure ("Unresolved reference: StreamsLoader"). That is the expected failing state — we haven't written the loader yet.

- [ ] **Step 5: Write `StreamsLoader.kt`**

Write to `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/streams/StreamsLoader.kt`:

```kotlin
// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.streams

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object StreamsLoader {

    fun parse(json: String): StreamsResult {
        val array = try {
            JSONArray(json)
        } catch (e: JSONException) {
            return StreamsResult.Error(
                "streams.json must be a JSON array: ${e.message}"
            )
        }

        val out = ArrayList<StreamEntry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
                ?: return StreamsResult.Error(
                    "streams.json entry $i is not a JSON object"
                )
            val entry = parseEntry(i, obj) ?: return entryError(i, obj)
            out += entry
        }
        return StreamsResult.Ok(out)
    }

    private fun parseEntry(index: Int, obj: JSONObject): StreamEntry? {
        val url = obj.optString("url", "").trim()
        if (url.isBlank()) return null
        val username = if (obj.isNull("username")) "" else obj.optString("username", "")
        val password = if (obj.isNull("password")) "" else obj.optString("password", "")
        return StreamEntry(url = url, username = username, password = password)
    }

    private fun entryError(index: Int, obj: JSONObject): StreamsResult.Error {
        val hasUrlKey = obj.has("url")
        val reason = if (!hasUrlKey) "missing \"url\"" else "blank \"url\""
        return StreamsResult.Error("streams.json entry $index has $reason")
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run:

```bash
./gradlew :sample:testDebugUnitTest --tests com.skrdzavac.rtspnative.sample.streams.StreamsLoaderTest
```

Expected: all 8 tests pass.

If the failure is `java.lang.RuntimeException: Method ... in org.json.JSONArray not mocked.`, the test dependency from Step 1 wasn't applied — re-check `sample/build.gradle.kts` and run `./gradlew :sample:dependencies --configuration debugUnitTestCompileClasspath | grep org.json` to confirm.

- [ ] **Step 7: Commit**

```bash
git add sample/build.gradle.kts \
        sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/streams/StreamsResult.kt \
        sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/streams/StreamsLoader.kt \
        sample/src/test/kotlin/com/skrdzavac/rtspnative/sample/streams/StreamsLoaderTest.kt
git commit -m "feat(sample): add StreamsLoader with parse(String) + tests"
```

---

## Task 4: `StreamsLoader.fromAssets(Context)`

Thin wrapper that reads the asset and forwards to `parse`. Not unit tested (trivial I/O).

**Files:**
- Modify: `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/streams/StreamsLoader.kt`

- [ ] **Step 1: Add the asset-loading function**

Open `StreamsLoader.kt` and add the following imports at the top:

```kotlin
import android.content.Context
import java.io.FileNotFoundException
```

Inside the `object StreamsLoader { ... }` block, above `fun parse(...)`, add:

```kotlin
    fun fromAssets(context: Context, fileName: String = "streams.json"): StreamsResult {
        val text = try {
            context.assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            return StreamsResult.Error(
                "No $fileName found in assets. Add $fileName at the sample " +
                    "module root with [{\"url\":..., \"username\":..., \"password\":...}, ...]."
            )
        }
        return parse(text)
    }
```

- [ ] **Step 2: Verify it compiles**

Run:

```bash
./gradlew :sample:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/streams/StreamsLoader.kt
git commit -m "feat(sample): add StreamsLoader.fromAssets(context)"
```

---

## Task 5: Strip `MainActivity` to skeleton + introduce `Screen` state + placeholder screens

This task is the structural pivot. We delete the entire current `SampleScreen` / `GridStreams` / `StreamTile` content and replace it with a small `MainActivity` that loads `streams.json` once and dispatches to one of three placeholder composables. The next three tasks fill in each placeholder.

**Files:**
- Create: `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/LandingScreen.kt`
- Create: `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/SingleStreamScreen.kt`
- Create: `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/GridStreamScreen.kt`
- Modify (overwrite): `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/MainActivity.kt`

- [ ] **Step 1: Create placeholder `LandingScreen.kt`**

Write to `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/LandingScreen.kt`:

```kotlin
// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.skrdzavac.rtspnative.sample.streams.StreamEntry
import com.skrdzavac.rtspnative.sample.streams.StreamsResult

@Composable
fun LandingScreen(
    result: StreamsResult,
    onOpenSingle: (StreamEntry) -> Unit,
    onOpenGrid: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(text = "LandingScreen placeholder", modifier = modifier.fillMaxSize())
}
```

- [ ] **Step 2: Create placeholder `SingleStreamScreen.kt`**

Write to `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/SingleStreamScreen.kt`:

```kotlin
// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.skrdzavac.rtspnative.sample.streams.StreamEntry

@Composable
fun SingleStreamScreen(
    entry: StreamEntry,
    modifier: Modifier = Modifier,
) {
    Text(text = "SingleStreamScreen: ${entry.url}", modifier = modifier.fillMaxSize())
}
```

- [ ] **Step 3: Create placeholder `GridStreamScreen.kt`**

Write to `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/GridStreamScreen.kt`:

```kotlin
// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.skrdzavac.rtspnative.sample.streams.StreamEntry

@Composable
fun GridStreamScreen(
    entries: List<StreamEntry>,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "GridStreamScreen: ${entries.size} streams",
        modifier = modifier.fillMaxSize(),
    )
}
```

- [ ] **Step 4: Overwrite `MainActivity.kt` with the new skeleton**

Replace the entire contents of `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/MainActivity.kt` with:

```kotlin
// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.skrdzavac.rtspnative.sample.streams.StreamEntry
import com.skrdzavac.rtspnative.sample.streams.StreamsLoader
import com.skrdzavac.rtspnative.sample.streams.StreamsResult
import com.skrdzavac.rtspnative.sample.ui.GridStreamScreen
import com.skrdzavac.rtspnative.sample.ui.LandingScreen
import com.skrdzavac.rtspnative.sample.ui.SingleStreamScreen

private sealed interface Screen {
    data object Landing : Screen
    data class Single(val entry: StreamEntry) : Screen
    data object Grid : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SampleApp()
                }
            }
        }
    }
}

@Composable
private fun SampleApp() {
    val context = LocalContext.current
    val streamsResult = remember { StreamsLoader.fromAssets(context) }
    var screen: Screen by remember { mutableStateOf(Screen.Landing) }

    BackHandler(enabled = screen !is Screen.Landing) {
        screen = Screen.Landing
    }

    when (val current = screen) {
        Screen.Landing -> LandingScreen(
            result = streamsResult,
            onOpenSingle = { screen = Screen.Single(it) },
            onOpenGrid = { screen = Screen.Grid },
            modifier = Modifier.fillMaxSize(),
        )
        is Screen.Single -> SingleStreamScreen(
            entry = current.entry,
            modifier = Modifier.fillMaxSize(),
        )
        Screen.Grid -> {
            val entries = (streamsResult as? StreamsResult.Ok)?.entries.orEmpty()
            GridStreamScreen(
                entries = entries.take(4),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
```

- [ ] **Step 5: Build and verify it compiles**

Run:

```bash
./gradlew :sample:assembleDebug
```

Expected: BUILD SUCCESSFUL. You will see no warnings about unused imports related to the removed code — the file is fresh.

- [ ] **Step 6: Install and verify placeholder UI shows**

Run:

```bash
./gradlew :sample:installDebug
```

Then launch the app on a connected device. Expected: the screen displays "LandingScreen placeholder". The app no longer has URL inputs, mode toggles, or any player UI — that is correct for this commit.

- [ ] **Step 7: Commit**

```bash
git add sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/MainActivity.kt \
        sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/LandingScreen.kt \
        sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/SingleStreamScreen.kt \
        sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/GridStreamScreen.kt
git commit -m "refactor(sample): split MainActivity into Screen state + placeholder screens"
```

---

## Task 6: Implement `LandingScreen`

Build the real landing UI: a "Open 2x2 Grid" button at the top, a list of streams, and error/empty states.

**Files:**
- Modify (overwrite): `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/LandingScreen.kt`

- [ ] **Step 1: Replace `LandingScreen.kt` with the real implementation**

Overwrite `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/LandingScreen.kt` with:

```kotlin
// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.skrdzavac.rtspnative.sample.streams.StreamEntry
import com.skrdzavac.rtspnative.sample.streams.StreamsResult
import java.net.URI

@Composable
fun LandingScreen(
    result: StreamsResult,
    onOpenSingle: (StreamEntry) -> Unit,
    onOpenGrid: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = (result as? StreamsResult.Ok)?.entries.orEmpty()
    val gridEnabled = entries.size >= 4

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("RTSPKit sample", style = MaterialTheme.typography.titleLarge)

        Button(
            enabled = gridEnabled,
            onClick = onOpenGrid,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (gridEnabled) "Open 2x2 Grid" else "Open 2x2 Grid (need 4+ streams)")
        }

        HorizontalDivider()
        Text("Streams", style = MaterialTheme.typography.titleMedium)

        when (result) {
            is StreamsResult.Error -> ErrorMessage(result.message)
            is StreamsResult.Ok ->
                if (entries.isEmpty()) {
                    ErrorMessage("streams.json is empty - add at least one entry.")
                } else {
                    StreamsList(entries = entries, onTap = onOpenSingle)
                }
        }
    }
}

@Composable
private fun StreamsList(
    entries: List<StreamEntry>,
    onTap: (StreamEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(entries) { entry ->
            StreamRow(entry = entry, onTap = { onTap(entry) })
        }
    }
}

@Composable
private fun StreamRow(entry: StreamEntry, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        val (title, subtitle) = displayLabel(entry.url)
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorMessage(message: String) {
    Text(
        text = message,
        color = Color.Red,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun displayLabel(url: String): Pair<String, String> {
    return try {
        val uri = URI(url)
        val host = uri.host ?: return url to ""
        val portSuffix = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().ifBlank { "/" }
        (host + portSuffix) to path
    } catch (_: Exception) {
        url to ""
    }
}
```

- [ ] **Step 2: Build and install**

```bash
./gradlew :sample:installDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Launch the app and verify the landing UI**

On the device, launch the app. Verify:

- Title "RTSPKit sample" is visible.
- "Open 2x2 Grid" button is enabled (because the starter `streams.json` has 4 entries).
- Four list rows appear, each showing host (and port if present) on the first line and path on the second.
- Tapping a row navigates away from the landing screen — at this point you'll see the `SingleStreamScreen` placeholder text. That's expected; Task 7 fills it in.
- Pressing system back returns to the landing screen.
- Tapping "Open 2x2 Grid" navigates to the `GridStreamScreen` placeholder text. Back returns to landing.

If you want to test error states, temporarily edit `sample/streams.json` to be `{}` (object not array). Rebuild and confirm the red error message shows. Then revert the file.

- [ ] **Step 4: Commit**

```bash
git add sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/LandingScreen.kt
git commit -m "feat(sample): implement LandingScreen with stream list and grid button"
```

---

## Task 7: Implement `SingleStreamScreen`

Real player UI: full-width video with stream-aspect sizing, status line, and snapshot/record controls.

**Files:**
- Modify (overwrite): `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/SingleStreamScreen.kt`

- [ ] **Step 1: Replace `SingleStreamScreen.kt` with the real implementation**

Overwrite `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/SingleStreamScreen.kt` with:

```kotlin
// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.skrdzavac.rtspnative.RtspRecorder
import com.skrdzavac.rtspnative.RtspSession
import com.skrdzavac.rtspnative.RtspSessionConfiguration
import com.skrdzavac.rtspnative.core.Credentials
import com.skrdzavac.rtspnative.core.RtspSessionState
import com.skrdzavac.rtspnative.sample.streams.StreamEntry
import com.skrdzavac.rtspnative.videorendering.RtspVideoSurface
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SingleStreamScreen(
    entry: StreamEntry,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val session: RtspSession = remember(entry) {
        val creds = if (entry.username.isNotBlank()) {
            Credentials(entry.username, entry.password)
        } else null
        RtspSession(RtspSessionConfiguration(url = entry.url, credentials = creds)).also {
            it.start()
        }
    }

    DisposableEffect(session) {
        onDispose { session.stop() }
    }

    val state by session.state.collectAsState()
    val stats by session.statistics.collectAsState()

    var lastSnapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    var recorder by remember { mutableStateOf<RtspRecorder?>(null) }
    var lastRecordingPath by remember { mutableStateOf<String?>(null) }

    val streamAspect: Float = if (stats.videoWidth > 0 && stats.videoHeight > 0) {
        stats.videoWidth.toFloat() / stats.videoHeight.toFloat()
    } else {
        16f / 9f
    }

    Column(
        modifier = modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(streamAspect)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            RtspVideoSurface(session = session, modifier = Modifier.fillMaxSize())
        }

        Text(text = statusLine(state, stats))

        val isPlaying = state is RtspSessionState.Playing
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = isPlaying,
                onClick = {
                    coroutineScope.launch {
                        val bmp = session.snapshot()
                        lastSnapshot = bmp?.asImageBitmap()
                    }
                },
            ) { Text("Snapshot") }

            Button(
                enabled = isPlaying && recorder == null,
                onClick = {
                    val dir = context.getExternalFilesDir(null) ?: context.filesDir
                    val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                    val file = File(dir, "rtsp-$ts.mp4")
                    recorder = session.startRecording(file)
                    if (recorder != null) lastRecordingPath = file.absolutePath
                },
            ) { Text("Record") }

            Button(
                enabled = recorder != null,
                onClick = {
                    session.stopRecording()
                    recorder = null
                },
            ) { Text("Stop Rec") }
        }

        recorder?.let { rec ->
            val recState by rec.state.collectAsState()
            val recBytes by rec.bytesWritten.collectAsState()
            Text("Recording (${recState.name}): ${recBytes / 1024} KB -> ${rec.file.name}")
        } ?: lastRecordingPath?.let {
            Text("Last recording: $it")
        }

        lastSnapshot?.let { snap ->
            Text("Last snapshot: ${snap.width}x${snap.height}")
            Image(
                bitmap = snap,
                contentDescription = "Snapshot",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(Color.DarkGray),
            )
        }
    }
}

private fun statusLine(
    state: RtspSessionState,
    stats: com.skrdzavac.rtspnative.core.SessionStatistics,
): String {
    val stateLabel = when (state) {
        is RtspSessionState.Failed -> "Failed: ${state.error.message}"
        else -> state::class.simpleName ?: "Idle"
    }
    val w = stats.videoWidth
    val h = stats.videoHeight
    val kbps = stats.bitrateBps / 1000
    return "${w}x$h  ${stats.fps.toInt()}fps  ${kbps}kbps  state=$stateLabel"
}
```

- [ ] **Step 2: Build and install**

```bash
./gradlew :sample:installDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Launch and verify single-stream playback**

On the device:

- Open the app. Tap one of the rows on the landing screen (preferably the one pointing at a reachable camera).
- Video should appear, full screen width, with letterbox top/bottom if the stream isn't 16:9. The status line should update from `0x0 0fps 0kbps state=Connecting` to the real resolution / fps / kbps once playback starts.
- Snapshot button: tap it after playback starts; a small image appears below.
- Record / Stop Rec: tap Record, see the "Recording (...)" line tick up, tap Stop Rec, see "Last recording: ..." with the file path.
- System back: returns to landing. Inspect logcat — there should be no errors and the session should be stopped (no continued bandwidth use).

If the video does not appear, that is a library issue (check that the URL in `streams.json` is reachable from the device). It is not a plan failure.

- [ ] **Step 4: Commit**

```bash
git add sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/SingleStreamScreen.kt
git commit -m "feat(sample): implement SingleStreamScreen with snapshot and record"
```

---

## Task 8: Implement `GridStreamScreen`

Real 2x2 grid: four `RtspSession` instances, fixed 16:9 cells, small fps/kbps overlay per tile.

**Files:**
- Modify (overwrite): `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/GridStreamScreen.kt`

- [ ] **Step 1: Replace `GridStreamScreen.kt` with the real implementation**

Overwrite `sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/GridStreamScreen.kt` with:

```kotlin
// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skrdzavac.rtspnative.RtspSession
import com.skrdzavac.rtspnative.RtspSessionConfiguration
import com.skrdzavac.rtspnative.core.Credentials
import com.skrdzavac.rtspnative.core.RtspSessionState
import com.skrdzavac.rtspnative.sample.streams.StreamEntry
import com.skrdzavac.rtspnative.videorendering.RtspVideoSurface

@Composable
fun GridStreamScreen(
    entries: List<StreamEntry>,
    modifier: Modifier = Modifier,
) {
    require(entries.size >= 4) { "GridStreamScreen needs at least 4 entries" }
    val visible = entries.take(4)

    val sessions: List<RtspSession> = remember(visible) {
        visible.map { entry ->
            val creds = if (entry.username.isNotBlank()) {
                Credentials(entry.username, entry.password)
            } else null
            RtspSession(RtspSessionConfiguration(url = entry.url, credentials = creds)).also {
                it.audioRenderer.isMuted = true
                it.start()
            }
        }
    }

    DisposableEffect(sessions) {
        onDispose { sessions.forEach { it.stop() } }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in 0 until 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (col in 0 until 2) {
                    val index = row * 2 + col
                    GridTile(
                        session = sessions[index],
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(16f / 9f),
                    )
                }
            }
        }
    }
}

@Composable
private fun GridTile(session: RtspSession, modifier: Modifier) {
    val state by session.state.collectAsState()
    val stats by session.statistics.collectAsState()

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        RtspVideoSurface(session = session, modifier = Modifier.fillMaxSize())
        val label = when (state) {
            is RtspSessionState.Playing ->
                "${stats.fps.toInt()}fps  ${stats.bitrateBps / 1000}kbps"
            is RtspSessionState.Failed -> "Failed"
            else -> state::class.simpleName ?: ""
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(label, color = Color.White, fontSize = 11.sp)
        }
    }
}
```

- [ ] **Step 2: Build and install**

```bash
./gradlew :sample:installDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Launch and verify the grid**

On the device:

- Open the app. Tap "Open 2x2 Grid".
- Four tiles appear, arranged 2x2, each 16:9. Each tile shows live video (or the relevant state name in the bottom-left overlay).
- All tiles are muted — only one AudioTrack would otherwise play four overlapping copies.
- System back returns to landing. Watch logcat for "Recording stopped" / cleanup messages — there should be no continued playback after navigating back.

- [ ] **Step 4: Commit**

```bash
git add sample/src/main/kotlin/com/skrdzavac/rtspnative/sample/ui/GridStreamScreen.kt
git commit -m "feat(sample): implement GridStreamScreen 2x2 with muted tiles"
```

---

## Task 9: Run the full test suite + sanity check

A no-code task that runs the full project test + lint check and verifies nothing in the wider codebase regressed.

- [ ] **Step 1: Run all unit tests**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. The new `StreamsLoaderTest` (8 tests) appears in the results.

- [ ] **Step 2: Build the release APK to catch shrinker issues**

```bash
./gradlew :sample:assembleRelease
```

Expected: BUILD SUCCESSFUL. (The sample app has `isMinifyEnabled = false`, so this is mostly a smoke check.)

- [ ] **Step 3: Confirm git log is clean**

Run:

```bash
git log --oneline -n 10
```

Expected: the recent commits are exactly the ones produced by this plan:

```
feat(sample): implement GridStreamScreen 2x2 with muted tiles
feat(sample): implement SingleStreamScreen with snapshot and record
feat(sample): implement LandingScreen with stream list and grid button
refactor(sample): split MainActivity into Screen state + placeholder screens
feat(sample): add StreamsLoader.fromAssets(context)
feat(sample): add StreamsLoader with parse(String) + tests
feat(sample): add StreamEntry data class
chore(sample): add streams.json + build-time copy to assets
```

No commit step here — the work is already on history.

---

## Done criteria

- `./gradlew test` passes including `StreamsLoaderTest`.
- `./gradlew :sample:installDebug` produces an APK that:
  - Opens to a landing screen with the stream list and an "Open 2x2 Grid" button.
  - Tapping a stream row plays that stream full-width with stream-aspect framing, snapshot + record buttons work.
  - Tapping "Open 2x2 Grid" shows a 2x2 grid of four muted streams.
  - System back returns to landing from either player screen, and all sessions are stopped.
- The repo no longer contains the old all-in-one `SampleScreen` / `GridStreams` / `StreamTile` code path (it was removed in Task 5).
