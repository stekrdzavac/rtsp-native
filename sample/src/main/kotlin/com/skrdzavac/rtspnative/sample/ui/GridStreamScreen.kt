// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.sample.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val visible = entries

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

    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    // Unmute only the focused stream; everything else stays muted so we
    // don't get a wall of overlapping audio in the grid.
    LaunchedEffect(selectedIndex, sessions) {
        sessions.forEachIndexed { i, s -> s.audioRenderer.isMuted = (i != selectedIndex) }
    }

    BackHandler(enabled = selectedIndex != null) {
        selectedIndex = null
    }

    // movableContentOf preserves composition state (and the underlying
    // AndroidView + SurfaceView + MediaCodec) as the same content is
    // re-hosted under a different layout. That's how we get a black-
    // flash-free transition from a grid cell to fullscreen and back —
    // the decoder never sees the surface change.
    val tileContents: List<@Composable (Modifier) -> Unit> = remember(sessions) {
        sessions.mapIndexed { i, session ->
            movableContentOf { tileModifier: Modifier ->
                GridTile(
                    session = session,
                    onClick = { selectedIndex = if (selectedIndex == i) null else i },
                    modifier = tileModifier,
                )
            }
        }
    }

    val focused = selectedIndex
    if (focused == null) {
        Column(
            modifier = modifier.fillMaxSize().padding(4.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            for (row in 0 until tileContents.size) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
//                    for (col in 0 until 2) {
//                        val index = row * 2 + col
                        tileContents[row](
                            Modifier
                                .weight(1f)
                                .aspectRatio(16f / 9f),
                        )
//                    }
                }
            }
        }
    } else {
        Box(
            modifier = modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            tileContents[focused](Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun GridTile(
    session: RtspSession,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by session.state.collectAsState()
    val stats by session.statistics.collectAsState()
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .background(Color.Black)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
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
