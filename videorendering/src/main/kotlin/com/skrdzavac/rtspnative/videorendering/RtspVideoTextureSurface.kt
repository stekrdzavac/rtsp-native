// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.videorendering

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.skrdzavac.rtspnative.RtspSession

/**
 * Compose wrapper around [RtspVideoTextureView] — the TextureView-backed
 * variant of [RtspVideoSurface]. Use this where the video must be transformed
 * by a `graphicsLayer` (e.g. pinch-to-zoom in fullscreen); use [RtspVideoSurface]
 * elsewhere for the lighter SurfaceView render path.
 *
 * ```
 * RtspVideoTextureSurface(session = session, modifier = Modifier.fillMaxSize())
 * ```
 */
@Composable
fun RtspVideoTextureSurface(
    session: RtspSession,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            RtspVideoTextureView(context).apply { attach(session) }
        },
        update = { view -> view.attach(session) },
        onRelease = { it.detach() },
    )
}
