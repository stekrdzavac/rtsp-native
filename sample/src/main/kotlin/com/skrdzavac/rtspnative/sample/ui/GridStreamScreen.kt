// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
        Spacer(modifier = Modifier.height(48.dp))
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
