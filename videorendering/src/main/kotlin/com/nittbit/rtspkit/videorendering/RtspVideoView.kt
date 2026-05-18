package com.nittbit.rtspkit.videorendering

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.nittbit.rtspkit.RtspSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
        session?.detachSurface()
        session = null
        ownedScope?.cancel()
        ownedScope = null
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
