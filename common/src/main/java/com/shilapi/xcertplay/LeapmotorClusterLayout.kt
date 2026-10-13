package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
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
     * The square dashboard stream. The safe area keeps Apple Maps' car marker inside the part of
     * the square the cluster leaves uncovered; the default comes from the 0.2.15-245 build, and the
     * marker sliders move it when that measurement does not fit this firmware.
     */
    fun streamConfig(
        content: CarPlayClusterDisplay.Content,
        scalePercent: Int = DEFAULT_SCALE_PERCENT,
        markerXPercent: Int = DEFAULT_MARKER_X_PERCENT,
        markerYPercent: Int = DEFAULT_MARKER_Y_PERCENT,
    ): AirPlayDisplayConfig = CarPlayClusterDisplay.config(
        widthPixels = STREAM_SIZE,
        heightPixels = STREAM_SIZE,
        scalePercent = scalePercent,
        content = content,
        markerXPercent = markerXPercent,
        markerYPercent = markerYPercent,
    )
}
