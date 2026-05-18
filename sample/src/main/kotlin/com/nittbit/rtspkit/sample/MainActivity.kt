package com.nittbit.rtspkit.sample

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nittbit.rtspkit.RtspSession
import com.nittbit.rtspkit.RtspSessionConfiguration
import com.nittbit.rtspkit.core.Credentials
import com.nittbit.rtspkit.core.RtspSessionState
import com.nittbit.rtspkit.videorendering.RtspVideoSurface

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

@Composable
private fun SampleScreen() {
    var url by remember { mutableStateOf("rtsp://lab.nittbit.com:60005/Streaming/Channels/101?transportmode=unicast&profile=Profile_1") }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("Admin12345") }
    var session by remember { mutableStateOf<RtspSession?>(null) }

    val currentState = session?.state?.collectAsState()?.value ?: RtspSessionState.Idle
    val stats = session?.statistics?.collectAsState()?.value

    DisposableEffect(session) {
        // Capture the value at composition time. `session` read inside the
        // onDispose lambda would read the *current* snapshot at dispose
        // time, which fires when the next session is already set — and
        // would erroneously stop the *new* session.
        val captured = session
        onDispose { captured?.stop() }
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
    }
}
