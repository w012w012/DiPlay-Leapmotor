package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import kotlin.math.roundToInt

/** C11 route-fit geometry advertised to the phone; never transforms the decoded video or its fade. */
internal object LeapmotorClusterMapLayout {
    private const val PREFS = "xcertplay_airplay"
    internal const val SIZE_KEY = "cluster_c11_map_size_percent"
    internal const val X_KEY = "cluster_c11_map_center_x_percent"
    internal const val Y_KEY = "cluster_c11_map_center_y_percent"
    private const val STREAM_SIZE = 720
    val sizes = (25..200).toList()
    val positions = 5..95

    data class Settings(val size: Int = 100, val x: Int? = null, val y: Int? = null)
    data class Plan(
        val size: Int,
        val x: Int,
        val y: Int,
        val xRange: IntRange,
        val yRange: IntRange,
        val defaultX: Int,
        val defaultY: Int,
        val safeArea: AirPlayInsets,
    )

    fun load(context: Context): Settings {
        val values = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        // Preserve all four old C11 fit boxes exactly, at the native 720-square resolution.
        val legacy = when (values["cluster_map_scale_percent"] as? Int) {
            83 -> 150
            67 -> 175
            125 -> 50
            else -> 100
        }
        return Settings(
            ((values[SIZE_KEY] as? Int) ?: legacy).coerceIn(sizes.first(), sizes.last()),
            (values[X_KEY] as? Int)?.coerceIn(5, 95),
            (values[Y_KEY] as? Int)?.coerceIn(5, 95),
        )
    }

    fun saveSize(context: Context, value: Int) {
        val current = plan(load(context), fadeInsets(context))
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit().putInt(SIZE_KEY, value.coerceIn(25, 200))
        // When the driver first changes size, keep the current centre instead of following the
        // expanding right/bottom boundary.
        if (!prefs.contains(X_KEY)) editor.putInt(X_KEY, current.x)
        if (!prefs.contains(Y_KEY)) editor.putInt(Y_KEY, current.y)
        editor.apply()
    }
    fun saveX(context: Context, value: Int) = save(context, X_KEY, value.coerceIn(5, 95))
    fun saveY(context: Context, value: Int) = save(context, Y_KEY, value.coerceIn(5, 95))
    private fun save(context: Context, key: String, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(key, value).apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(SIZE_KEY, 100).remove(X_KEY).remove(Y_KEY).apply()
    }

    fun fadeInsets(context: Context) = AirPlayInsets(
        // Disabling the visual mask must not disable map-size control or reset its calibration.
        left = AirPlayPersistence.loadClusterMaskLeftPercent(context).takeIf { it > 0 }
            ?: LeapmotorClusterLayout.DEFAULT_MASK_LEFT_PERCENT,
        top = AirPlayPersistence.loadClusterMaskTopPercent(context).takeIf { it > 0 }
            ?: LeapmotorClusterLayout.DEFAULT_MASK_TOP_PERCENT,
    )

    fun plan(settings: Settings, fade: AirPlayInsets): Plan {
        val size = settings.size.coerceIn(25, 200)
        // 100 is the old Standard; 150/175/50 reproduce Larger/Largest/Smallest.
        // These are route-fit sizes, not a camera zoom factor or video resolution.
        fun kept(percent: Int): Int {
            val inset = (percent.coerceIn(0, 100) * (200 - size.coerceAtLeast(50)) + 50) / 100
            // Below the old Smallest preset, shrink both dimensions of that fit box, providing
            // useful travel in both directions while the full video and mask remain stationary.
            return (if (size < 50) 100 - ((100 - inset) * size / 50.0).roundToInt() else inset)
                .coerceIn(0, 98)
        }
        val left = kept(fade.left)
        val top = kept(fade.top)
        val xRange = positions
        val yRange = positions
        val defaultX = ((100 + left) / 2).coerceIn(xRange)
        val defaultY = ((100 + top) / 2).coerceIn(yRange)
        // Let the phone refit the overview near a frame edge, rather than restricting centre travel
        // to the small margins left by a large fit box. The video and its fade never move.
        val x = (settings.x ?: defaultX).coerceIn(xRange)
        val y = (settings.y ?: defaultY).coerceIn(yRange)
        val safeArea = requireNotNull(CarPlayClusterDisplay.config(
            widthPixels = STREAM_SIZE, heightPixels = STREAM_SIZE, scalePercent = 100,
            baseSafeArea = AirPlayInsets(left = left, top = top),
            contentCenterXPercent = x, contentCenterYPercent = y,
        ).safeArea)
        return Plan(size, x, y, xRange, yRange, defaultX, defaultY, safeArea)
    }

    fun streamConfig(context: Context, content: CarPlayClusterDisplay.Content) =
        streamConfig(content, plan(load(context), fadeInsets(context)))

    fun streamConfig(content: CarPlayClusterDisplay.Content, layout: Plan) =
        LeapmotorClusterLayout.streamConfig(content).copy(safeArea = layout.safeArea)

    /** Signed displacement of the layout centre, in pixels of the native cluster stream. */
    fun offsetPixels(centre: Int, reference: Int): Int =
        ((centre - reference) * STREAM_SIZE / 100.0).roundToInt()
}
