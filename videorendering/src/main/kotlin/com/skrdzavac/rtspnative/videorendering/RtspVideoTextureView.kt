// SPDX-License-Identifier: Apache-2.0

package com.skrdzavac.rtspnative.videorendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import com.skrdzavac.rtspnative.RtspSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A TextureView-backed video surface for an [RtspSession]. Unlike the
 * SurfaceView-backed [RtspVideoView], a TextureView composites through the
 * regular view/GPU pipeline, so it can be transformed by a Compose
 * `graphicsLayer` (scale/translate) — this is what enables pinch-to-zoom in
 * fullscreen. Prefer [RtspVideoView] for grids (lighter, zero-copy); use this
 * where the video must be zoomed/animated. Wrap in [RtspVideoTextureSurface]
 * for Jetpack Compose.
 */
class RtspVideoTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    private val textureView = TextureView(context).also { addView(it) }
    private var session: RtspSession? = null
    private var ownedScope: CoroutineScope? = null
    private var videoSize: Pair<Int, Int>? = null
    /** The surface this view last attached, so detach can be identity-checked. */
    private var attachedSurface: Surface? = null

    init {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val surface = Surface(st)
                attachedSurface = surface
                session?.attachSurface(surface)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                attachedSurface?.let { session?.detachSurface(it) }
                attachedSurface?.release()
                attachedSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    /**
     * Bind this view to [session]. Safe to call repeatedly with the same
     * session reference — subsequent calls are no-ops. Calling with a
     * different session detaches the previous one and rebinds.
     */
    fun attach(session: RtspSession) {
        if (this.session === session) return
        detach()
        this.session = session
        attachedSurface?.let { session.attachSurface(it) }
        session.setSnapshotter(::snapshot)
        val scope = MainScope()
        ownedScope = scope
        scope.launch {
            session.videoSize.collect { size ->
                videoSize = size
                requestLayout()
            }
        }
    }

    fun detach() {
        session?.setSnapshotter(null)
        // Pass the surface we own so the session ignores this detach if it has
        // already been rebound to a newer view (grid<->fullscreen handoff).
        attachedSurface?.let { session?.detachSurface(it) }
        session = null
        ownedScope?.cancel()
        ownedScope = null
    }

    /** Capture the latest decoded frame. TextureView readback is synchronous. */
    private fun snapshot(): Bitmap? = textureView.bitmap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (videoWidth, videoHeight) = videoSize ?: run {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec)
        val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (parentWidth <= 0 || parentHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val parentAspect = parentWidth.toFloat() / parentHeight
        val videoAspect = videoWidth.toFloat() / videoHeight
        val (w, h) = if (parentAspect > videoAspect) {
            (parentHeight * videoAspect).toInt() to parentHeight
        } else {
            parentWidth to (parentWidth / videoAspect).toInt()
        }
        setMeasuredDimension(w, h)
        textureView.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }
}
