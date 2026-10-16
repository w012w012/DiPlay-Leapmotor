package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.SeekBar
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import androidx.core.view.ViewCompat
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class LeapmotorClusterMapLayoutSettingsTest {
    @Test fun positionSlidersShowDirectionInsteadOfBarePercentAndAllowBothExtremes() {
        withC11Settings { activity ->
            val x = layoutControl(activity, R.string.settings_c11_map_horizontal)
            val y = layoutControl(activity, R.string.settings_c11_map_vertical)
            assertEquals(90, x.max)
            assertEquals(90, y.max)
            assertTrue(x.isEnabled && y.isEnabled)
            fun move(control: SeekBar, progress: Int, value: String) {
                val listener = ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(control, "mOnSeekBarChangeListener")
                listener.onStartTrackingTouch(control)
                listener.onProgressChanged(control, progress, true)
                assertTrue(descendants(control.parent as View).filterIsInstance<TextView>().any {
                    it.text.toString() == value
                })
                assertEquals(value, ViewCompat.getStateDescription(control)?.toString())
            }
            move(x, 0, activity.getString(R.string.settings_c11_map_offset_left, 418))
            move(x, 90, activity.getString(R.string.settings_c11_map_offset_right, 230))
            move(y, 0, activity.getString(R.string.settings_c11_map_offset_up, 360))
            move(y, 90, activity.getString(R.string.settings_c11_map_offset_down, 288))
            val settings = LeapmotorClusterMapLayout.load(activity)
            assertEquals(95, settings.x)
            assertEquals(95, settings.y)
            val content = settingsContent(activity)
            for (id in listOf(R.string.marker_left, R.string.marker_right, R.string.marker_up, R.string.marker_down)) {
                assertTrue(descendants(content).filterIsInstance<TextView>().any { it.text == activity.getString(id) })
            }
        }
    }

    @Test fun adjustingAndResettingMarksReconnectWithoutStoppingTheActiveSession() {
        withC11Settings { activity ->
            val session = mock(CarPlayController::class.java)
            var stops = 0
            CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 1920, 1080, Any(),
                CarPlaySessionDisplay(1920, 1080, Surface.ROTATION_0, false, false, 1920, 1080)) { stops++ }
            CarPlayBackgroundSession.active = true
            try {
                val control = layoutControl(activity, R.string.settings_c11_map_size)
                val listener = ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(control, "mOnSeekBarChangeListener")
                listener.onProgressChanged(control, 75, true) // Standard 100
                assertTrue(PendingReconnect.isPending(session))
                assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
                assertEquals(0, stops)
                PendingReconnect.clear()
                LeapmotorClusterMapLayout.saveX(activity, 40)
                val reset = descendants(settingsContent(activity)).filterIsInstance<TextView>().single {
                    it.text.toString() == activity.getString(R.string.settings_c11_map_layout_reset)
                }
                reset.performClick()
                assertEquals(LeapmotorClusterMapLayout.Settings(), LeapmotorClusterMapLayout.load(activity))
                assertTrue(PendingReconnect.isPending(session))
                assertEquals(0, stops)
            } finally { CarPlayBackgroundSession.clear(); PendingReconnect.clear() }
        }
    }

    @Test fun manualControlsReplacePresetAndProbeAndSaveWithoutChangingLegacySize() {
        withC11Settings { activity ->
            val content = settingsContent(activity)
            assertFalse(descendants(content).filterIsInstance<TextView>().any {
                it.text.toString().startsWith(activity.getString(R.string.cluster_map_size) + " · ")
            })
            assertFalse(descendants(content).filterIsInstance<Switch>().any {
                it.contentDescription?.toString()?.contains("offset", ignoreCase = true) == true
            })
            val control = layoutControl(activity, R.string.settings_c11_map_size)
            assertTrue(control.isEnabled)
            val listener = ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(control, "mOnSeekBarChangeListener")
            listener.onProgressChanged(control, 125, true) // 25 + 125 = 150
            assertEquals(150, LeapmotorClusterMapLayout.load(activity).size)
            assertFalse(activity.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
                .contains("cluster_map_scale_percent"))
            assertNotNull(layoutControl(activity, R.string.settings_c11_map_horizontal))
            assertNotNull(layoutControl(activity, R.string.settings_c11_map_vertical))
        }
    }

    @Test fun aRetiredSavedChoiceOffersOnlyTransparencyAndOff() {
        withC11Settings { activity ->
            activity.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit()
                .putInt("leapmotor_cluster_mask_mode", 1).commit()
            val mode = modeControl(activity)
            val title = activity.getString(R.string.leapmotor_cluster_mask_mode)
            val alpha = activity.getString(R.string.leapmotor_cluster_mask_mode_alpha)
            assertEquals("$title · $alpha", mode.text.toString())
            mode.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            try {
                val list = dialog.listView
                assertEquals(2, list.adapter.count)
                assertEquals(alpha, list.adapter.getItem(0))
                assertEquals(activity.getString(R.string.leapmotor_cluster_mask_mode_off), list.adapter.getItem(1))
                assertEquals(0, list.checkedItemPosition)
            } finally { dialog.dismiss() }
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w320dp-h800dp-xhdpi")
    fun compactLargeFontControlFits() = screenshot("compact-zh", 320)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w1000dp-h800dp-xhdpi")
    fun fullLargeFontControlFits() = screenshot("full-zh", 1000)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-ldrtl-w320dp-h800dp-xhdpi")
    fun arabicLargeFontControlFits() = screenshot("compact-ar", 320)

    private fun screenshot(name: String, widthDp: Int) {
        RuntimeEnvironment.setFontScale(1.5f)
        withC11Settings { activity ->
            fun capture(suffix: String) {
                val controls = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "c11MapLayoutControls",
                    ReflectionHelpers.ClassParameter(LinearLayout::class.java, controls))
                screenshotRow(activity, controls, name + suffix, widthDp)
            }
            LeapmotorClusterMapLayout.saveX(activity, 5)
            LeapmotorClusterMapLayout.saveY(activity, 95)
            capture("")
            LeapmotorClusterMapLayout.saveX(activity, 95)
            LeapmotorClusterMapLayout.saveY(activity, 5)
            capture("-right-up")
            AirPlayPersistence.saveC11VehicleLogsEnabled(activity, false)
            val vehicleLogs = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "c11VehicleLogControls",
                ReflectionHelpers.ClassParameter(LinearLayout::class.java, vehicleLogs))
            screenshotRow(activity, vehicleLogs, "vehicle-logs-$name", widthDp)
            screenshotRow(activity, modeControl(activity), "mode-$name", widthDp)
        }
    }

    private fun screenshotRow(activity: DiPlayActivity, row: View, name: String, widthDp: Int) {
        row.layoutDirection = activity.resources.configuration.layoutDirection
        val width = (widthDp * activity.resources.displayMetrics.density).toInt()
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, width, row.measuredHeight)
        for (text in descendants(row).filterIsInstance<TextView>()) {
            val layout = text.layout ?: continue
            for (line in 0 until layout.lineCount) assertEquals(text.text.toString(), 0, layout.getEllipsisCount(line))
            assertTrue(text.right <= width && text.left >= 0)
            assertTrue("Text must fit vertically: ${text.text}",
                layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
        }
        assertTrue(row.height > 0)
        val bitmap = Bitmap.createBitmap(width, row.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.rgb(28, 32, 42)) }
        row.draw(canvas)
        val directory = File("build/reports/c11-map-layout-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun settingsContent(activity: DiPlayActivity): LinearLayout {
        val content = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
            ReflectionHelpers.ClassParameter(LinearLayout::class.java, content))
        return content
    }

    private fun layoutControl(activity: DiPlayActivity, title: Int): SeekBar =
        descendants(settingsContent(activity)).filterIsInstance<SeekBar>().single {
            it.contentDescription == activity.getString(title)
        }

    private fun modeControl(activity: DiPlayActivity): TextView =
        descendants(settingsContent(activity)).filterIsInstance<TextView>().single {
            it.text.toString().startsWith(activity.getString(R.string.leapmotor_cluster_mask_mode) + " · ")
        }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun withC11Settings(block: (DiPlayActivity) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val original = android.os.Build.FINGERPRINT
        ShadowBuild.setFingerprint("qti/msmnile_gvmq/msmnile_gvmq:9/PQ1A/test:user/test-keys")
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("leapmotor_cluster_map_offset", Context.MODE_PRIVATE).edit().clear().commit()
        AirPlayPersistence.saveClusterMapEnabled(app, true)
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        shadowOf(app.getSystemService(DisplayManager::class.java).getDisplay(id)).setFlags(Display.FLAG_PRESENTATION)
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.CLUSTER_MAP))
        ReflectionHelpers.setField(activity, "c11AdbState", BydAdbAccess.State.READY)
        try { block(activity) }
        finally {
            activity.finish()
            ShadowDisplayManager.removeDisplay(id)
            ShadowBuild.setFingerprint(original)
            RuntimeEnvironment.setFontScale(1f)
            app.getSharedPreferences("leapmotor_cluster_map_offset", Context.MODE_PRIVATE).edit().clear().commit()
            app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
