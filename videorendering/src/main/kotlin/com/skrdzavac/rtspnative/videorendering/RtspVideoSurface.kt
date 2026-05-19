// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.videorendering

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.skrdzavac.rtspnative.RtspSession

/**
 * Compose wrapper around [RtspVideoView]. Use this from a Composable
 * scope:
 *
 * ```
 * RtspVideoSurface(session = session, modifier = Modifier.fillMaxSize())
 * ```
 */
@Composable
fun RtspVideoSurface(
    session: RtspSession,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            RtspVideoView(context).apply { attach(session) }
        },
        onRelease = { it.detach() },
    )
    DisposableEffect(session) {
        onDispose { /* RtspVideoView.detach() handled via onRelease */ }
    }
}
