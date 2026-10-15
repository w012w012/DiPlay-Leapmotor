package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay

/**
 * Leapmotor C11's instrument cluster.
 *
 * DiLink 5.1 exposes measured shared-memory layers, so its geometry can be probed at runtime. The
 * C11 exposes none of that: the cluster projection panel is simply 1920x720 and the cluster renderer
 * expects a square dashboard picture. CarPlay's cluster stream is 720x720, so it is placed as a
 * centred-right 720x720 square: `left = 1920 - 720 = 1200`.
 *
 * Nothing here is configurable: there is no way to discover the geometry, and the shape has to match
 * the renderer's own layout or the map arrives stretched or clipped.
 */
internal object LeapmotorClusterLayout {
    /** The whole `qti/msmnile_gvmq` family (Snapdragon SA8155P IVI) seen on the C11. */
    private const val FINGERPRINT_PART = "qti/msmnile_gvmq"

    private const val PANEL_WIDTH = 1920
    private const val PANEL_HEIGHT = 720
    /** CarPlay's dashboard/cluster stream uses a square picture on this firmware. */
    private const val STREAM_SIZE = 720

    /** The firmware property that names the meter display, as the 0.2.15-245 C11 build reads it. */
    private const val METER_DISPLAY_ID_PROPERTY = "persist.sys.lp.meterDisplayId"

    /** Marker defaults measured from the 0.2.15-245 C11 build; the settings sliders override them. */
    const val DEFAULT_MARKER_X_PERCENT = 50
    const val DEFAULT_MARKER_Y_PERCENT = 65
    /** The square stream is requested at its native size unless the driver changes it. */
    const val DEFAULT_SCALE_PERCENT = 100

    /**
     * The panel edge the map would otherwise cut into abruptly. LeapTool's instrument mask covers the
     * same edges with a baked PNG; the gradient here is drawn instead, in the panel's own night colour.
     */
    const val DEFAULT_MASK_LEFT_PERCENT = 27
    const val DEFAULT_MASK_TOP_PERCENT = 10
    const val MASK_STEP_PERCENT = 1

    /**
     * How the projection meets the gauges around it.
     *
     * [MASK_MODE_ALPHA] fades the video itself out along its left and top edges, so the car's own
     * gauges show through: nothing is painted over the map, which is why it blends with any theme.
     * It needs the cluster window on a [android.view.TextureView]: a SurfaceView is composited under
     * the window, so nothing the window draws can give it alpha.
     *
     * [MASK_MODE_COLOR] paints the measured colour band instead — what LeapTool's baked PNGs do, and
     * the only option that works on the SurfaceView path.
     */
    const val MASK_MODE_ALPHA = 0
    const val MASK_MODE_COLOR = 1
    const val MASK_MODE_OFF = 2
    const val DEFAULT_MASK_MODE = MASK_MODE_ALPHA

    fun isC11(fingerprint: String = Build.FINGERPRINT): Boolean =
        fingerprint.contains(FINGERPRINT_PART, ignoreCase = true)

    /**
     * The display the car itself calls its meter, when the firmware publishes the property. Null
     * means it does not, and the caller must fall back to the panel geometry.
     */
    fun meterDisplayId(): Int? = runCatching {
        val systemProperties = Class.forName("android.os.SystemProperties")
        val get = systemProperties.getMethod("get", String::class.java)
        (get.invoke(null, METER_DISPLAY_ID_PROPERTY) as? String)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    /** The cluster panel as the car reports it: a 1920x720 HDMI presentation display. */
    fun isClusterPanel(display: Display): Boolean {
        val size = Point().also { @Suppress("DEPRECATION") display.getRealSize(it) }
        return size.x == PANEL_WIDTH && size.y == PANEL_HEIGHT
    }

    /**
     * Picks the meter display in the order the evidence supports: the driver's explicit choice, the
     * firmware property, then the last 1920x720 presentation display. Every step is logged by the
     * caller, because which display carries the meter differs between firmware builds.
     */
    fun findDisplay(context: Context, savedDisplayId: Int = -1): Display? {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return null
        val all = manager.displays?.toList().orEmpty()
        if (savedDisplayId >= 0) {
            all.firstOrNull { it.displayId == savedDisplayId && it.isValid }?.let { return it }
        }
        meterDisplayId()?.let { id ->
            all.firstOrNull { it.displayId == id && it.isValid }?.let { return it }
        }
        return manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.filter { isClusterPanel(it) }
            ?.lastOrNull()
    }

    /**
     * The cluster's seating. Only the known 1920x720 panel gets a window; anything else falls back
     * to DiPlay's default full-panel behaviour rather than risking a wrong guess.
     */
    fun plan(panel: Point, fingerprint: String = Build.FINGERPRINT): ClusterMapViewport? =
        plan(panel.x, panel.y, fingerprint)

    fun plan(width: Int, height: Int, fingerprint: String = Build.FINGERPRINT): ClusterMapViewport? {
        if (!isC11(fingerprint)) return null
        if (width != PANEL_WIDTH || height != PANEL_HEIGHT) return null
        return ClusterMapViewport(
            left = width - STREAM_SIZE,
            top = 0,
            width = STREAM_SIZE,
            height = STREAM_SIZE,
            sourceLeft = 0,
            sourceTop = 0,
            streamWidth = STREAM_SIZE,
            streamHeight = STREAM_SIZE,
            fullMap = false,
            pinWindowToViewport = true,
        )
    }

    /**
     * How much of each fade band the map keeps clear, in percent of the band, for the driver's size
     * preset.
     *
     * The stream size itself does not change how large the map looks: the square is rendered into a
     * fixed 720 px window, so the route's size on the panel follows the safe area's *fraction* only.
     * These values are what makes the size control real — 100 % keeps the route inside the fade-free
     * part of the square (a 526 px column today), and smaller values let it use part of the faded band,
     * which is the only way to a bigger map. The car's own report confirms the fraction is what counts:
     * a 600 px stream at 83 % and a 480 px stream at 67 % both gave the route the same panel width.
     */
    fun contentInsetPercent(scalePresetPercent: Int): Int = when (scalePresetPercent) {
        67 -> 25
        83 -> 50
        125 -> 150
        else -> 100
    }

    /**
     * The square dashboard stream. The safe area keeps Apple Maps' car marker inside the part of the
     * square the cluster leaves uncovered: the driver's marker sliders move it when that measurement
     * does not fit this firmware.
     *
     * [fadeInsets] is only the band this square's own edges use — the faded strip, and nothing on the
     * two sides that abut the panel edge. The BYD cluster's measured area (35 % per side) must not leak
     * in here: on a 720 px square it left the route a 210 px column and a 426 px row.
     *
     * The route is centred inside whatever the inset leaves, not on the marker: with the left edge
     * faded, centring on a marker at 50 % pushed the route into the fade and read as an off-centre map.
     */
    fun streamConfig(
        content: CarPlayClusterDisplay.Content,
        scalePercent: Int = DEFAULT_SCALE_PERCENT,
        markerXPercent: Int = DEFAULT_MARKER_X_PERCENT,
        markerYPercent: Int = DEFAULT_MARKER_Y_PERCENT,
        contentInsetPercent: Int = 100,
        fadeInsets: AirPlayInsets = AirPlayInsets(
            top = DEFAULT_MASK_TOP_PERCENT,
            bottom = 0,
            left = DEFAULT_MASK_LEFT_PERCENT,
            right = 0,
        ),
    ): AirPlayDisplayConfig {
        val inset = contentInsetPercent.coerceIn(0, 200)
        fun kept(percent: Int) = (percent * inset + 50) / 100
        val covered = AirPlayInsets(
            top = kept(fadeInsets.top),
            bottom = kept(fadeInsets.bottom),
            left = kept(fadeInsets.left),
            right = kept(fadeInsets.right),
        )
        return CarPlayClusterDisplay.config(
            widthPixels = STREAM_SIZE,
            heightPixels = STREAM_SIZE,
            scalePercent = scalePercent,
            content = content,
            markerXPercent = markerXPercent,
            markerYPercent = markerYPercent,
            baseSafeArea = covered,
            // The centre of the strip the map may use, so the route fills it symmetrically.
            contentCenterXPercent = (covered.left + 100) / 2,
            contentCenterYPercent = (covered.top + 100) / 2,
        )
    }
}
