package com.shilapi.xcertplay

import android.graphics.Point
import android.os.Build

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

    fun isC11(fingerprint: String = Build.FINGERPRINT): Boolean =
        fingerprint.contains(FINGERPRINT_PART, ignoreCase = true)

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
}
