package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.widget.FrameLayout

/**
 * One edge's alpha ramp, measured from the panel edge inward.
 *
 * The map has to be *gone* at the edge — that edge is the seam being blended — and fully back at
 * [toX]/[toY]. Running it the other way round (opaque at the edge, clear inside) leaves the hard seam
 * untouched and deletes a band out of the middle of the map instead, which is what the first build of
 * this fade did.
 */
internal data class EdgeFadeRamp(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val edgeAlpha: Int = 0,
    val innerAlpha: Int = 255,
)

/** The left band: transparent at x=0, fully visible again at 27 % (by default) of the width. */
internal fun leftEdgeFadeRamp(width: Float, percent: Int): EdgeFadeRamp? {
    if (percent <= 0 || width <= 0f) return null
    val stop = width * percent.coerceIn(0, 100) / 100f
    if (stop <= 0f) return null
    return EdgeFadeRamp(fromX = 0f, fromY = 0f, toX = stop, toY = 0f)
}

/** The top band: transparent at y=0, fully visible again at 10 % (by default) of the height. */
internal fun topEdgeFadeRamp(height: Float, percent: Int): EdgeFadeRamp? {
    if (percent <= 0 || height <= 0f) return null
    val stop = height * percent.coerceIn(0, 100) / 100f
    if (stop <= 0f) return null
    return EdgeFadeRamp(fromX = 0f, fromY = 0f, toX = 0f, toY = stop)
}

/**
 * The radius of the rounded corner where the two bands meet, in pixels.
 *
 * Two straight ramps each finish on a straight line, so the map's clear region ends in a right angle at
 * the point where they meet and the joint reads as an abrupt corner. The frame takes that tip away with
 * a radial fade — the R angle of a corner. Half the narrower band keeps the rounding in proportion when
 * the driver narrows the fades, and nothing is rounded unless both bands exist to form a corner.
 */
internal fun cornerFilletRadius(leftPixels: Float, topPixels: Float): Float {
    if (leftPixels <= 0f || topPixels <= 0f) return 0f
    return (minOf(leftPixels, topPixels) * 0.5f).coerceIn(MIN_FILLET_RADIUS, MAX_FILLET_RADIUS)
}

private const val MIN_FILLET_RADIUS = 8f

/** A wide radius eats map for no visible gain once it is past a tenth of the panel. */
private const val MAX_FILLET_RADIUS = 72f

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

    /** The R angle actually in use, 0 when there is no corner to round. */
    internal fun filletRadius(): Float {
        val left = leftEdgeFadeRamp(width.toFloat(), leftPercent) ?: return 0f
        val top = topEdgeFadeRamp(height.toFloat(), topPercent) ?: return 0f
        return cornerFilletRadius(left.toX, top.toY)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        val left = leftEdgeFadeRamp(width, leftPercent)
        val top = topEdgeFadeRamp(height, topPercent)
        if (left == null && top == null) {
            super.dispatchDraw(canvas)
            return
        }
        // The layer keeps the fade inside this frame: siblings drawn later (the turn card, the music
        // and speed panels) keep their own alpha instead of being cut by the same mask.
        val checkpoint = canvas.saveLayer(0f, 0f, width, height, null)
        super.dispatchDraw(canvas)
        // DST_IN keeps the destination and scales its alpha by the source alpha: the video fades
        // out along the ramp and the panel behind the window shows through.
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        left?.let {
            paint.shader = rampShader(it)
            canvas.drawRect(0f, 0f, it.toX, height, paint)
        }
        top?.let {
            paint.shader = rampShader(it)
            canvas.drawRect(0f, 0f, width, it.toY, paint)
        }
        // The two ramps finish on straight lines, so their corner is a right angle. A radial fade whose
        // alpha is opaque at that point and runs out at `fillet` takes the tip away: the map's corner
        // becomes the R angle of a corner instead of a square joint. It decays to nothing at its own
        // edge, so it never adds a visible boundary, and it stays far inside both bands, so the safe
        // area declared to the phone is untouched.
        if (left != null && top != null) {
            val fillet = cornerFilletRadius(left.toX, top.toY)
            if (fillet > 0f) {
                paint.shader = RadialGradient(
                    left.toX,
                    top.toY,
                    fillet,
                    Color.argb(255, 0, 0, 0),
                    Color.argb(0, 0, 0, 0),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(left.toX, top.toY, fillet, paint)
            }
        }
        paint.shader = null
        paint.xfermode = null
        canvas.restoreToCount(checkpoint)
    }

    private fun rampShader(ramp: EdgeFadeRamp): LinearGradient = LinearGradient(
        ramp.fromX,
        ramp.fromY,
        ramp.toX,
        ramp.toY,
        Color.argb(ramp.edgeAlpha, 0, 0, 0),
        Color.argb(ramp.innerAlpha, 0, 0, 0),
        Shader.TileMode.CLAMP,
    )
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
