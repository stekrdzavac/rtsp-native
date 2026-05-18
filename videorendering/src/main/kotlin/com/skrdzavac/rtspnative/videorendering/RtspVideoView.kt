package com.skrdzavac.rtspnative.videorendering

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.skrdzavac.rtspnative.RtspSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The public video surface for an [RtspSession]. SurfaceView-backed for
 * the zero-copy MediaCodec render path; wrap in [RtspVideoSurface] for
 * Jetpack Compose.
 */
class RtspVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    private val surfaceView = SurfaceView(context).also { addView(it) }
    private var session: RtspSession? = null
    private var ownedScope: CoroutineScope? = null
    private var videoSize: Pair<Int, Int>? = null

    init {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                session?.attachSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                session?.attachSurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                session?.detachSurface()
            }
        })
    }

    fun attach(session: RtspSession) {
        detach()
        this.session = session
        if (surfaceView.holder.surface?.isValid == true) {
            session.attachSurface(surfaceView.holder.surface)
        }
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
        session?.detachSurface()
        session = null
        ownedScope?.cancel()
        ownedScope = null
    }

    /**
     * Capture the latest decoded video frame into a Bitmap via
     * [PixelCopy]. Output is at the native video resolution if known,
     * else at the view's measured size. Returns null if the surface
     * isn't ready or the platform copy fails.
     */
    suspend fun snapshot(): Bitmap? {
        val surface = surfaceView.holder.surface
        if (surface == null || !surface.isValid) return null
        val (w, h) = videoSize ?: (surfaceView.width to surfaceView.height)
        if (w <= 0 || h <= 0) return null

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            val ht = HandlerThread("rtsp-snapshot").also { it.start() }
            val handler = Handler(ht.looper)
            PixelCopy.request(surfaceView, bitmap, { result ->
                ht.quitSafely()
                if (result == PixelCopy.SUCCESS) {
                    cont.resume(bitmap)
                } else {
                    bitmap.recycle()
                    cont.resume(null)
                }
            }, handler)
            cont.invokeOnCancellation { ht.quitSafely() }
        }
    }

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
        surfaceView.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }
}
