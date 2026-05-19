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
                    coroutineScope.launch {
                        val dir = context.getExternalFilesDir(null) ?: context.filesDir
                        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        val file = File(dir, "rtsp-$ts.mp4")
                        val rec = session.startRecording(file)
                        recorder = rec
                        if (rec != null) lastRecordingPath = file.absolutePath
                    }
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
