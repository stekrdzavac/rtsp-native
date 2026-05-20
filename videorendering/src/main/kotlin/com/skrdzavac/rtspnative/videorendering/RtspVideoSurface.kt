// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.videorendering

import androidx.compose.runtime.Composable
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
 *
 * Reacts to [session] changes: if the caller swaps the session
 * reference (e.g., switching camera profile), the underlying
 * [SurfaceView] is reused and rebound to the new session — no view
 * recreation, no surface flash.
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
        // factory only captures `session` at first inflation; this update
        // block runs on every recomposition and rebinds when the session
        // reference changes. attach() is cheap-idempotent for the
        // same-session case.
        update = { view -> view.attach(session) },
        onRelease = { it.detach() },
    )
}
