package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.util.Log
import kotlin.math.roundToInt
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.TextureView
import android.content.res.ColorStateList
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * Shows CarPlay's instrument-cluster stream on a BYD cluster projection display.
 *
 * BYD exposes the cluster's projection area as public presentation displays owned by
 * com.byd.containerservice (DiLink 5) or com.xdja.containerservice (DiLink 4). The
 * cluster only shows this display while its projection mode is on, which DiPlay cannot switch.
 */
internal class ClusterMapPresentation(
    context: Context,
    display: Display,
    private val theme: DiLink51ClusterLayout.Theme = DiLink51ClusterLayout.theme(context),
    private val onSurface: (Surface?) -> Unit,
) : Presentation(context, display) {
    // The placeholder until the map arrives: a small spinner.
    private var waitingLabel: View? = null
    private var turnCardView: ClusterTurnCardView? = null
    private var videoView: View? = null
    private var edgeMaskView: ClusterEdgeMaskView? = null
    private var streamActive = false
    var outputSurface: Surface? = null
        private set
    var mapVisible = true
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val size = sizeOf(display)
        val adaptive = DiLink51ClusterLayout.supported()
        val plan = if (adaptive) DiLink51ClusterLayout.plan(size.x, size.y, theme) else null
        // Leapmotor C11 exposes no measurable DiLink layers, so its square window is derived from
        // the physical panel size instead. It is only ever used when DiLink planning failed.
        val leap = if (plan == null) LeapmotorClusterLayout.plan(size.x, size.y) else null
        // The adaptive DiLink 5.1 layout follows the cluster's own contrast; elsewhere the cluster follows DiPlay's theme.
        val dark = if (plan != null) DiLink51ClusterLayout.dark(context, theme) else context.resolveAppNightNow()
        val backdrop = if (dark) Color.rgb(15, 22, 30) else Color.rgb(207, 218, 229)
        val placeholder = if (dark) Color.BLACK else Color.rgb(233, 238, 246)
        val root = FrameLayout(context).apply {
            setBackgroundColor(
                when {
                    leap != null -> Color.TRANSPARENT
                    plan == null -> placeholder
                    plan.fullMap -> backdrop
                    else -> Color.TRANSPARENT
                }
            )
        }
        if (plan != null || leap != null) {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        // The C11 cluster is a real screen that also renders the car's own gauges: claim only the
        // measured square instead of the whole panel, and let touches outside it reach the car. If
        // the firmware refuses the resized window the video keeps the panel coordinates, so the map
        // still lands where the renderer measured it.
        val leapWindow = leap != null && run {
            window?.setGravity(Gravity.END or Gravity.TOP)
            window?.setLayout(leap.width, leap.height)
            window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            val attributes = window?.attributes
            attributes != null && attributes.width == leap.width && attributes.height == leap.height
        }
        val videoParams = when {
            leapWindow -> FrameLayout.LayoutParams(-1, -1)
            leap != null -> FrameLayout.LayoutParams(leap.width, leap.height, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = leap.left; topMargin = leap.top
            }
            plan == null -> FrameLayout.LayoutParams(-1, -1)
            else -> FrameLayout.LayoutParams(plan.width, plan.height, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = plan.left; topMargin = plan.top
            }
        }
        if (leap != null) {
            ClusterProjectionState.note(
                "leapmotor viewport square=${leap.width}x${leap.height}@${leap.left},${leap.top}" +
                    " panel=${size.x}x${size.y} window=$leapWindow dark=$dark",
            )
        }
        val maskMode = if (leap != null) clusterEdgeFadeMode(context) else LeapmotorClusterLayout.MASK_MODE_OFF
        val alphaFade = leap != null && maskMode == LeapmotorClusterLayout.MASK_MODE_ALPHA
        val alphaFadeFrame = if (alphaFade) clusterEdgeFadeFrame(context) else null
        if (plan != null) {
            val textureView = TextureView(context)
            fun transform() {
                textureView.setTransform(Matrix().apply {
                    setScale(DiLink51ClusterLayout.STREAM_WIDTH.toFloat() / plan.width,
                        DiLink51ClusterLayout.STREAM_HEIGHT.toFloat() / plan.height)
                    postTranslate(-plan.sourceLeft.toFloat(), -plan.sourceTop.toFloat())
                })
            }
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    texture.setDefaultBufferSize(DiLink51ClusterLayout.STREAM_WIDTH, DiLink51ClusterLayout.STREAM_HEIGHT)
                    transform()
                    outputSurface = Surface(texture).also(onSurface)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = transform()
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    onSurface(null)
                    outputSurface?.release()
                    outputSurface = null
                    return true
                }
            }
            root.addView(textureView, videoParams)
            videoView = textureView
            if (plan.fullMap) root.addView(InstrumentContrastView(context, plan, backdrop), FrameLayout.LayoutParams(-1, -1))
            Log.i(TAG, "layout=$theme viewport=$plan source=${plan.sourceLeft},${plan.sourceTop} dark=$dark")
        } else if (alphaFade) {
            // The alpha fade needs the video in the window's own layer: a SurfaceView is composited
            // under the window, so nothing drawn here can give it alpha, and its black would show
            // through wherever the fade made the texture transparent.
            val video = ClusterVideoTexture(
                context,
                bufferWidth = leap!!.width,
                bufferHeight = leap.height,
            ) { surface ->
                outputSurface = surface
                onSurface(surface)
            }
            if (alphaFadeFrame == null) {
                root.addView(video, videoParams)
            } else {
                alphaFadeFrame.addView(video, FrameLayout.LayoutParams(-1, -1))
                root.addView(alphaFadeFrame, videoParams)
            }
            videoView = video
        } else {
            // Preserve the original PR renderer for DiLink 5 and unverified firmware.
            val surfaceView = SurfaceView(context)
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.i(TAG, "cluster surface created")
                    outputSurface = holder.surface
                    onSurface(holder.surface)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    Log.i(TAG, "cluster surface ${width}x$height")
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.i(TAG, "cluster surface destroyed")
                    onSurface(null)
                    // SurfaceHolder owns this surface; do not release it ourselves.
                    outputSurface = null
                }
            })
            root.addView(surfaceView, videoParams)
            videoView = surfaceView
        }
        // The C11's cluster renderer keeps its own gauges around the square projection, so the map
        // would otherwise end in a hard line against them. The video view is already built above;
        // this only says what happened, because an exported report is what a car test is judged on.
        if (leap != null) {
            when (maskMode) {
                LeapmotorClusterLayout.MASK_MODE_ALPHA -> ClusterProjectionState.mask(
                    if (alphaFadeFrame == null) {
                        "leapmotor mask off: both widths are 0, nothing drawn mode=alpha"
                    } else {
                        "leapmotor mask attached route=presentation mode=alpha" +
                            " display=${display.displayId}" +
                            " square=${leap.width}x${leap.height}@${leap.left},${leap.top}" +
                            " window=$leapWindow left=${alphaFadeFrame.leftPercent}%" +
                            " top=${alphaFadeFrame.topPercent}%" +
                            " corner=${cornerFilletRadius(
                                leap.width * alphaFadeFrame.leftPercent / 100f,
                                leap.height * alphaFadeFrame.topPercent / 100f,
                            ).toInt()}px above=TextureView"
                    },
                )
                LeapmotorClusterLayout.MASK_MODE_COLOR -> {
                    // The measured colour band, painted above the video; see attachClusterEdgeMask
                    // for why that order is not optional.
                    val mask = attachClusterEdgeMask(root, videoView, videoParams, context, dark)
                    if (mask == null) {
                        ClusterProjectionState.mask(
                            "leapmotor mask off: both widths are 0, nothing drawn mode=color",
                        )
                    } else {
                        edgeMaskView = mask
                        ClusterProjectionState.mask(
                            "leapmotor mask attached route=presentation mode=color" +
                                " display=${display.displayId}" +
                                " square=${leap.width}x${leap.height}@${leap.left},${leap.top} window=$leapWindow" +
                                " left=${mask.leftPercent}% top=${mask.topPercent}% night=$dark" +
                                " above=${videoView?.javaClass?.simpleName}",
                        )
                    }
                }
                else -> ClusterProjectionState.mask("leapmotor mask off: mode off")
            }
        }
        val waitingColor = if (dark) Color.WHITE else Color.DKGRAY
        val density = context.resources.displayMetrics.density
        waitingLabel = FrameLayout(context).apply {
            if (plan == null && leap == null) setBackgroundColor(placeholder)
            addView(ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(waitingColor)
                contentDescription = context.getString(R.string.cluster_waiting_for_map)
            }, FrameLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt(), Gravity.CENTER))
        }
        root.addView(waitingLabel, FrameLayout.LayoutParams(videoParams))
        turnCardView = ClusterTurnCardView(context).apply {
            visibility = View.GONE
            // The C11 card is sized against the square's own fade-free strip. The default window is the
            // BYD cluster's 29 %-wide box, which made the same size percent a much smaller card here.
            if (leap != null) {
                windowInsets = AirPlayInsets(
                    top = AirPlayPersistence.loadClusterMaskTopPercent(context).coerceAtLeast(1),
                    bottom = 0,
                    left = AirPlayPersistence.loadClusterMaskLeftPercent(context).coerceAtLeast(1),
                    right = 0,
                )
            }
        }
        root.addView(turnCardView, FrameLayout.LayoutParams(-1, -1))
        root.addView(ClusterMusicView(context), FrameLayout.LayoutParams(videoParams))
        root.addView(ClusterSpeedometerView(context), FrameLayout.LayoutParams(-1, -1))
        // Start with the placeholder visible; SurfaceView would otherwise cover the light
        // backdrop with an empty black surface before the first stream notification.
        // SurfaceView ignores fractional alpha before API 34. Keep its decoder surface
        // visible and fade an opaque ordinary View over it instead.
        if (videoView !is SurfaceView) videoView?.alpha = if (streamActive) 1f else 0f
        waitingLabel?.apply {
            alpha = if (streamActive) 0f else 1f
            visibility = if (streamActive) View.GONE else View.VISIBLE
        }
        setContentView(root)
    }

    /**
     * Cross-fades the waiting placeholder over the live map. TextureView can also fade the
     * video; legacy SurfaceView stays visible under the placeholder to preserve its surface.
     */
    fun setStreamActive(active: Boolean) {
        if (streamActive == active) return
        // Update before cancelling: a cancelled fade must not hide the newly restored label.
        streamActive = active
        val video = videoView
        val label = waitingLabel
        video?.animate()?.cancel()
        label?.animate()?.cancel()
        if (video == null || label == null) return
        label.visibility = View.VISIBLE
        // Animate from the current opacity so a stream reversal does not jump or restart.
        if (video !is SurfaceView) video.animate().alpha(if (active) 1f else 0f).setDuration(300).start()
        label.animate().alpha(if (active) 0f else 1f).setDuration(300).withEndAction {
            if (streamActive == active) label.visibility = if (active) View.GONE else View.VISIBLE
        }.start()
    }

    /** Window alpha hides the pixels without destroying the TextureView/decoder surface. */
    fun setMapVisible(visible: Boolean) {
        mapVisible = visible
        window?.let { window ->
            val alpha = if (visible) 1f else 0f
            if (window.attributes.alpha != alpha) window.attributes = window.attributes.apply { this.alpha = alpha }
        }
    }

    fun setTurnCardOverlay(xPercent: Int, yPercent: Int, sizePercent: Int) {
        turnCardView?.setLayout(xPercent, yPercent, sizePercent)
    }

    fun setTurnCardNightMode(night: Boolean) {
        turnCardView?.setNightMode(night)
    }

    /** The edge fades follow the same day/night choice as the turn card, so they never stand out. */
    fun setMaskNightMode(night: Boolean) {
        edgeMaskView?.setNight(night)
    }

    /** ClusterEdgeMaskView's saved widths, for the diagnostics line. */
    val maskWidths: Pair<Int, Int>? get() = edgeMaskView?.let { it.leftPercent to it.topPercent }

    fun setTurnCardOpacity(percent: Int) {
        turnCardView?.setOpacity(percent)
    }

    fun setTurnCardGuidance(guidance: ClusterTurnGuidance?) {
        turnCardView?.setGuidance(guidance)
    }

    companion object {
        const val TAG = "DiPlay-Cluster"

        /** Keep the 5/5.1 selection order, then try the measured DiLink 4 projection display. */
        fun findDisplay(context: Context, theme: DiLink51ClusterLayout.Theme = DiLink51ClusterLayout.theme(context)): Display? {
            // The C11 exposes a plain 1920x720 HDMI screen instead of a named BYD layer, and which
            // of its displays carries the meter differs between firmware builds.
            if (LeapmotorClusterLayout.isC11()) {
                return LeapmotorClusterLayout.findDisplay(context, AirPlayPersistence.loadClusterDisplayId(context))
            }
            val displays = context.getSystemService(DisplayManager::class.java)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION) ?: return null
            val name = DiLink51ClusterLayout.displayName(
                displays.map { it.name }, android.os.Build.FINGERPRINT, theme,
            )
            if (name == null) {
                // Never replace a missing 5.1 side layer with a full-screen display.
                if (DiLink51ClusterLayout.supported()) return null
                return displays.firstOrNull { display ->
                    val size = sizeOf(display)
                    DiLink4ClusterDisplay.accepts(display.name, size.x, size.y)
                }
            }
            return displays.firstOrNull { it.name == name }?.takeIf {
                if (!DiLink51ClusterLayout.supported()) true else {
                    val size = sizeOf(it)
                    DiLink51ClusterLayout.plan(size.x, size.y, theme) != null
                }
            }
        }

        fun describeDisplays(context: Context): String =
            context.getSystemService(DisplayManager::class.java)?.displays
                ?.joinToString {
                    val size = sizeOf(it)
                    "${it.displayId}:${it.name} ${size.x}x${size.y} flags=${it.flags} valid=${it.isValid}"
                }.orEmpty()

        /** The displays the driver may pick from when the automatic meter choice is wrong. */
        fun presentationDisplays(context: Context): List<Display> =
            context.getSystemService(DisplayManager::class.java)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.toList().orEmpty()

        fun diagnosticReport(context: Context): String = buildString {
            appendLine("clusterEnabled=${AirPlayPersistence.loadClusterMapEnabled(context)}")
            appendLine("navigationReceiverAvailable=${com.shilapi.xcertplay.hud.BydOutputSettings.navigationAvailable(context)}")
            appendLine("allDisplays=${describeDisplays(context)}")
            val presentations = context.getSystemService(DisplayManager::class.java)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).orEmpty()
            appendLine("presentationDisplayIds=${presentations.joinToString { it.displayId.toString() }}")
            if (LeapmotorClusterLayout.isC11()) {
                appendLine("leapmotorMeterDisplayId=${LeapmotorClusterLayout.meterDisplayId() ?: "none"}")
                appendLine("leapmotorClusterDisplayChoice=${AirPlayPersistence.loadClusterDisplayId(context)}")
            }
            val selected = findDisplay(context)
            append("selectedCluster=${selected?.let { "${it.displayId}:${it.name}" } ?: "none"}")
        }

        fun sizeOf(display: Display): Point = Point().also {
            @Suppress("DEPRECATION")
            display.getRealSize(it)
        }
    }
}

/**
 * The C11 projection's left and top edge fades, over the video. LeapTool covers the same seam with
 * static PNG masks whose alpha ramp is baked in; the measured night mask is a left band about 27% of
 * the square wide, in the panel's own night blue, and its day pair is the same shape in the panel's
 * light grey. Drawing both ramps and cross-fading between them keeps the seam matched to the gauges
 * whether the car is in its light or its dark cluster theme.
 *
 * Shared by every projection window the C11 can present through: the cluster Presentation and the
 * ADB activity route, so the seam is blended whichever window the firmware picks.
 */
internal class ClusterEdgeMaskView(
    context: Context,
    internal val leftPercent: Int,
    internal val topPercent: Int,
) : View(context) {
    private var night = true
    private var nightWeight = 1f
    private val paint = Paint()

    fun setNight(value: Boolean) {
        if (night == value) return
        night = value
        // A short fade beats a snap: the car's own gauges also transition over a beat.
        val from = nightWeight
        val to = if (value) 1f else 0f
        animate()?.cancel()
        animate().setDuration(300).setUpdateListener { animation ->
            nightWeight = from + (to - from) * animation.animatedFraction
            invalidate()
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat(); val height = height.toFloat()
        // Measured from the car's own mask tool: night sits on the cluster's dark blue, day on its
        // light grey. Each band is fully opaque at the panel edge and clear at its inner end, so the
        // map surfaces through the ramp.
        val nightColor = Color.rgb(31, 44, 75)
        val dayColor = Color.rgb(234, 237, 246)
        val weight = nightWeight.coerceIn(0f, 1f)
        fun blended(a: Float): Int =
            Color.argb((a * 255).roundToInt(),
                (Color.red(dayColor) + (Color.red(nightColor) - Color.red(dayColor)) * weight).roundToInt(),
                (Color.green(dayColor) + (Color.green(nightColor) - Color.green(dayColor)) * weight).roundToInt(),
                (Color.blue(dayColor) + (Color.blue(nightColor) - Color.blue(dayColor)) * weight).roundToInt(),
            )
        if (leftPercent > 0) {
            val stop = width * leftPercent / 100f
            paint.shader = LinearGradient(0f, 0f, stop, 0f, blended(1f), blended(0f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, stop, height, paint)
        }
        if (topPercent > 0) {
            val stop = height * topPercent / 100f
            paint.shader = LinearGradient(0f, 0f, 0f, stop, blended(1f), blended(0f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width, stop, paint)
        }
        // The band's inner corner is a right angle where the two ramps finish, and that tip is what
        // reads as abrupt. The same R angle as the transparent fade, in colour: a patch that is opaque
        // at the corner and runs out at the fillet radius. Clipped to the map's side, so it fills the
        // corner sliver instead of thickening either band.
        val leftStop = width * leftPercent / 100f
        val topStop = height * topPercent / 100f
        val fillet = cornerFilletRadius(leftStop, topStop)
        if (fillet > 0f) {
            val checkpoint = canvas.save()
            canvas.clipRect(leftStop, topStop, width, height)
            paint.shader = RadialGradient(leftStop, topStop, fillet, blended(1f), blended(0f), Shader.TileMode.CLAMP)
            canvas.drawCircle(leftStop, topStop, fillet, paint)
            canvas.restoreToCount(checkpoint)
        }
        paint.shader = null
    }
}

/** Builds the edge mask when the C11's saved widths ask for one; null keeps the window untouched. */
internal fun clusterEdgeMask(context: Context): ClusterEdgeMaskView? {
    val left = AirPlayPersistence.loadClusterMaskLeftPercent(context)
    val top = AirPlayPersistence.loadClusterMaskTopPercent(context)
    if (left <= 0 && top <= 0) return null
    return ClusterEdgeMaskView(context, left, top)
}

/**
 * Puts the C11 edge fade directly above the projection's video view, laid out like the video.
 *
 * The position is not cosmetic. The video is a `SurfaceView`, and `SurfaceView.draw()` clears the
 * canvas of the region it draws, so a fade added before it is erased on the first frame; an opaque
 * `TextureView` would hide it just as completely. Inserting it right after the video keeps that fact
 * in one place instead of depending on the caller's add order. Taking the video's layout keeps the
 * fade on the square even when the firmware refuses the resized window and the map is placed with
 * margins inside a full-panel window.
 */
internal fun attachClusterEdgeMask(
    root: FrameLayout,
    video: View?,
    videoParams: FrameLayout.LayoutParams,
    context: Context,
    night: Boolean,
): ClusterEdgeMaskView? {
    val mask = clusterEdgeMask(context) ?: return null
    mask.setNight(night)
    root.addView(
        mask,
        if (video == null) root.childCount else root.indexOfChild(video) + 1,
        FrameLayout.LayoutParams(videoParams),
    )
    return mask
}

/** Shading belongs to DiPlay's map only; no stock cluster window is changed or covered outside the side card. */
private class InstrumentContrastView(    context: Context,
    private val plan: DiLink51ClusterLayout.Plan,
    private val background: Int,
) : View(context) {
    private val paint = Paint()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = plan.left.toFloat(); val top = plan.top.toFloat()
        val right = left + plan.width; val bottom = top + plan.height
        fun fade(x0: Float, y0: Float, x1: Float, y1: Float, start: Int, end: Int,
                 l: Float, t: Float, r: Float, b: Float) {
            paint.shader = LinearGradient(x0, y0, x1, y1, start, end, Shader.TileMode.CLAMP)
            canvas.drawRect(l, t, r, b, paint)
        }
        val clear = background and 0x00ffffff
        val soft = (background and 0x00ffffff) or (210 shl 24)
        if (plan.fullMap) {
            fade(left, top, left, top + 64, background, clear, left, top, right, top + 64)
            fade(left, bottom - 48, left, bottom, clear, background, left, bottom - 48, right, bottom)
            // The trip/menu readouts occupy the outer panels; keep the route and marker in the clear center.
            fade(left, top, left + 460, top, soft, clear, left, top, left + 460, bottom)
            fade(right - 460, top, right, top, clear, soft, right - 460, top, right, bottom)
        }
        paint.shader = null
    }
}
