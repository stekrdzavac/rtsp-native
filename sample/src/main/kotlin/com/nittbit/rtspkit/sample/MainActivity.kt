package com.nittbit.rtspkit.sample

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nittbit.rtspkit.RtspSession
import com.nittbit.rtspkit.RtspSessionConfiguration
import com.nittbit.rtspkit.core.Credentials
import com.nittbit.rtspkit.core.RtspSessionState
import com.nittbit.rtspkit.videorendering.RtspVideoSurface
import kotlinx.coroutines.launch

private const val SAMPLE_H264_RTSP_URL =
    "rtsp://admin:Admin12345@192.168.0.203/Streaming/Channels/101?transportmode=unicast&profile=Profile_1"
private const val SAMPLE_H265_RTSP_URL =
    "rtsp://admin:Admin12345@lab.nittbit.com:60005/Streaming/Channels/101?transportmode=unicast&profile=Profile_1"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SampleScreen()
                }
            }
        }
    }
}

private enum class Mode { Single, Grid2x2, Grid3x3 }

@Composable
private fun SampleScreen() {
    var url by remember { mutableStateOf( SAMPLE_H265_RTSP_URL)}
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("Admin12345") }
    var mode by remember { mutableStateOf(Mode.Single) }
    var session by remember { mutableStateOf<RtspSession?>(null) }
    var lastSnapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val currentState = session?.state?.collectAsState()?.value ?: RtspSessionState.Idle
    val stats = session?.statistics?.collectAsState()?.value

    // Single-session lifecycle: stop when Compose reads the *captured* old
    // value at dispose time. See discussion in the Stop/Play race fix.
    DisposableEffect(session) {
        val captured = session
        onDispose { captured?.stop() }
    }

    // When switching away from Single mode, ensure the lone session is stopped.
    DisposableEffect(mode) {
        onDispose {
            if (mode != Mode.Single) {
                session?.stop()
                session = null
                lastSnapshot = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("RTSP URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                enabled = mode != Mode.Single,
                onClick = { mode = Mode.Single },
            ) { Text("Single") }
            Button(
                enabled = mode != Mode.Grid2x2,
                onClick = { mode = Mode.Grid2x2 },
            ) { Text("2x2") }
            Button(
                enabled = mode != Mode.Grid3x3,
                onClick = { mode = Mode.Grid3x3 },
            ) { Text("3x3") }
        }

        when (mode) {
            Mode.Single -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = session == null,
                        onClick = {
                            val creds = if (username.isNotBlank()) Credentials(username, password) else null
                            val cfg = RtspSessionConfiguration(url = url, credentials = creds)
                            val newSession = RtspSession(cfg)
                            session = newSession
                            newSession.start()
                        },
                    ) { Text("Play") }
                    Button(
                        enabled = session != null,
                        onClick = {
                            session?.stop()
                            session = null
                        },
                    ) { Text("Stop") }
                    Button(
                        enabled = session != null && currentState is RtspSessionState.Playing,
                        onClick = {
                            val s = session ?: return@Button
                            coroutineScope.launch {
                                val bmp = s.snapshot()
                                lastSnapshot = bmp?.asImageBitmap()
                            }
                        },
                    ) { Text("Snapshot") }
                }

                val stateLabel = when (val s = currentState) {
                    is RtspSessionState.Failed -> "Failed: ${s.error.message}"
                    else -> s::class.simpleName ?: "Idle"
                }
                Text("State: $stateLabel")
                stats?.let {
                    val kbps = it.bitrateBps / 1000
                    Text(
                        "${it.videoWidth}x${it.videoHeight}  ${it.fps.toInt()}fps  ${kbps}kbps  " +
                            "frames=${it.framesDecoded}  drops=${it.framesDropped}"
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val s = session
                    if (s != null) {
                        RtspVideoSurface(session = s, modifier = Modifier.fillMaxSize())
                    } else {
                        Text("No stream", color = Color.White)
                    }
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
            Mode.Grid2x2 -> GridStreams(
                rows = 2,
                cols = 2,
                url = url,
                username = username,
                password = password,
            )
            Mode.Grid3x3 -> GridStreams(
                rows = 3,
                cols = 3,
                url = url,
                username = username,
                password = password,
            )
        }
    }
}

/**
 * Spawns [rows] × [cols] RtspSession instances pointed at the same URL,
 * all muted by default to avoid the cacophony of N AudioTracks playing
 * the same audio. Each tile shows a live RtspVideoSurface plus a small
 * status overlay (state, fps, kbps). Re-creates the session list any
 * time the URL or grid size changes; the previous list is stopped via
 * DisposableEffect.
 */
@Composable
private fun GridStreams(
    rows: Int,
    cols: Int,
    url: String,
    username: String,
    password: String,
) {
    val count = rows * cols
    val sessions: List<RtspSession> = remember(count, url, username, password) {
        val creds = if (username.isNotBlank()) Credentials(username, password) else null
        List(count) {
            val cfg = RtspSessionConfiguration(url = url, credentials = creds)
            RtspSession(cfg).also { s ->
                s.audioRenderer.isMuted = true
                s.start()
            }
        }
    }
    DisposableEffect(sessions) {
        val captured = sessions
        onDispose { captured.forEach { it.stop() } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (c in 0 until cols) {
                    val i = r * cols + c
                    StreamTile(
                        session = sessions[i],
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
private fun StreamTile(session: RtspSession, modifier: Modifier) {
    val state by session.state.collectAsState()
    val stats by session.statistics.collectAsState()

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        RtspVideoSurface(session = session, modifier = Modifier.fillMaxSize())
        val label = when (val s = state) {
            is RtspSessionState.Playing ->
                "${stats.fps.toInt()}fps  ${stats.bitrateBps / 1000}kbps"
            is RtspSessionState.Failed -> "Failed: ${s.error.message}"
            else -> s::class.simpleName ?: ""
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
