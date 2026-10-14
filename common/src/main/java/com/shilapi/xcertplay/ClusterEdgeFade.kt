package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.widget.FrameLayout

/**
 * The C11 projection's left and top edges, faded out by taking alpha away from the video itself.
 *
 * This is the difference from [ClusterEdgeMaskView]: nothing is painted over the map. The frame keeps
 * its children (the square video) in an offscreen layer and multiplies their alpha by a gradient with
 * [PorterDuff.Mode.DST_IN], so the last pixels of the video become fully transparent at the panel edge
 * and the car's own gauges come through. A painted band can only ever approximate the colour around
 * it — which is why LeapTool ships sixteen baked styles — while an alpha fade needs no colour at all
 * and matches the day theme, the night theme and anything the firmware draws.
 *
 * The catch is the video view: a [android.view.SurfaceView] is composited *under* the window, so
 * nothing the window draws can give it alpha. This frame therefore only works around a
 * [android.view.TextureView], which is drawn in the window's own layer.
 */
internal class ClusterEdgeFadeFrame(
    context: Context,
    internal val leftPercent: Int,
    internal val topPercent: Int,
) : FrameLayout(context) {
    private val paint = Paint()
    private val opaque = Color.argb(255, 0, 0, 0)
    private val clear = Color.argb(0, 0, 0, 0)

    override fun dispatchDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        if ((leftPercent <= 0 && topPercent <= 0) || width <= 0f || height <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        // The layer keeps the fade inside this frame: siblings drawn later (the turn card, the music
        // and speed panels) keep their own alpha instead of being cut by the same mask.
        val checkpoint = canvas.saveLayer(0f, 0f, width, height, null)
        super.dispatchDraw(canvas)
        // DST_IN keeps the destination and scales its alpha by the source alpha, so the video fades
        // out along the ramp and the panel behind the window shows through.
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        if (leftPercent > 0) {
            val stop = width * leftPercent / 100f
            paint.shader = LinearGradient(0f, 0f, stop, 0f, opaque, clear, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, stop, height, paint)
        }
        if (topPercent > 0) {
            val stop = height * topPercent / 100f
            paint.shader = LinearGradient(0f, 0f, 0f, stop, opaque, clear, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width, stop, paint)
        }
        paint.shader = null
        paint.xfermode = null
        canvas.restoreToCount(checkpoint)
    }
}

/** The blending mode the driver picked, clamped to a known value. */
internal fun clusterEdgeFadeMode(context: Context): Int = when (AirPlayPersistence.loadClusterMaskMode(context)) {
    LeapmotorClusterLayout.MASK_MODE_COLOR -> LeapmotorClusterLayout.MASK_MODE_COLOR
    LeapmotorClusterLayout.MASK_MODE_OFF -> LeapmotorClusterLayout.MASK_MODE_OFF
    else -> LeapmotorClusterLayout.MASK_MODE_ALPHA
}

/**
 * The fade frame for the C11's square projection, or null when no fade was asked for. The alpha mode
 * is the only one that needs it; the painted band is attached by [attachClusterEdgeMask] instead.
 */
internal fun clusterEdgeFadeFrame(context: Context): ClusterEdgeFadeFrame? {
    if (clusterEdgeFadeMode(context) != LeapmotorClusterLayout.MASK_MODE_ALPHA) return null
    val left = AirPlayPersistence.loadClusterMaskLeftPercent(context)
    val top = AirPlayPersistence.loadClusterMaskTopPercent(context)
    if (left <= 0 && top <= 0) return null
    return ClusterEdgeFadeFrame(context, left, top)
}
