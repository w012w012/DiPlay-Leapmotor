package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.View
import android.view.Display
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.setup.DiLinkGeneration
import com.shilapi.xcertplay.setup.SetupGuide
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w600dp-h800dp")
class C11BydCleanupSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var fingerprint: String
    private var displayId = -1

    @Before fun setup() {
        fingerprint = android.os.Build.FINGERPRINT
        ShadowBuild.setFingerprint("qti/msmnile_gvmq/msmnile_gvmq:9/PQ1A/test:user/test-keys")
        for (name in listOf("diplay", "xcertplay_airplay", "diplay_byd_outputs")) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AirPlayPersistence.saveClusterMapEnabled(app, true)
        displayId = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        shadowOf(app.getSystemService(DisplayManager::class.java).getDisplay(displayId)).setFlags(Display.FLAG_PRESENTATION)
    }

    @After fun cleanup() {
        ShadowDisplayManager.removeDisplay(displayId)
        ShadowBuild.setFingerprint(fingerprint)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun settingsAndSearchOmitBydHardwareButKeepC11AndGenericControls() = withScreen { screen ->
        ReflectionHelpers.setField(screen, "bydVehicleAdvancedExpanded", true)
        val hidden = listOf(R.string.advanced_vehicle_data, R.string.hide_advanced_vehicle_data,
            R.string.settings_cluster_speedometer, R.string.dashboard_map_only_in_small_and_full_navi,
            R.string.cluster_song, R.string.carplay_calls_on_dashboard, R.string.carplay_call_controls_experimental,
            R.string.wheel_map_zoom, R.string.wheel_joystick, R.string.adb_cluster_activity_mode)
            .map(screen::getString)
        showCategory(screen, SettingsCategory.ADVANCED)
        var texts = texts(screen.window.decorView)
        assertTrue(screen.getString(R.string.settings_cluster_music) in texts)
        assertTrue(screen.getString(R.string.phone_audio_route) in texts)
        assertTrue(screen.getString(R.string.phone_audio_route_apps) in texts)
        assertTrue(screen.getString(R.string.bt_suspend_during_carplay) in texts)
        assertTrue(screen.getString(R.string.settings_navigation_wheel_volume) in texts)
        assertTrue(texts.any { it.startsWith(screen.getString(R.string.settings_c11_map_size)) })
        assertTrue(hidden.none(texts::contains))
        showCategory(screen, SettingsCategory.VEHICLE)
        texts = texts(screen.window.decorView)
        assertTrue(screen.getString(R.string.wheel_siri_key) in texts)
        assertTrue(screen.getString(R.string.settings_external_controller) in texts)
        assertTrue(hidden.none(texts::contains))
        val index = ReflectionHelpers.callInstanceMethod<List<Any>>(screen, "buildSettingsSearchIndex")
        val titles = index.map { ReflectionHelpers.getField<String>(it, "title") }
        assertTrue(hidden.none { title -> titles.any { it == title || it.startsWith("$title · ") } })
        assertTrue(screen.getString(R.string.settings_external_controller) in titles)
    }

    @Test fun c11TurnCardDoesNotOfferABydSmallWindowSelector() = withScreen { screen ->
        AirPlayPersistence.saveClusterContent(app, com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content.TURN_CARD)
        showCategory(screen, SettingsCategory.ADVANCED)
        val texts = texts(screen.window.decorView)
        assertFalse(texts.any { it.startsWith(screen.getString(R.string.cluster_small_window_marker)) })
        assertFalse(texts.contains(screen.getString(R.string.dashboard_map_only_in_small_and_full_navi)))
        assertTrue(texts.any { it.startsWith(screen.getString(R.string.turn_card_horizontal)) })
    }

    @Test @Config(sdk = [32])
    fun c11DoesNotOfferOrActivateTheBydAndroid12CallPopupWorkaround() {
        AirPlayPersistence.saveHideBydCallPopup(app, true)
        assertFalse(AirPlayPersistence.loadHideBydCallPopup(app))
        withScreen { screen ->
            showCategory(screen, SettingsCategory.ADVANCED)
            assertFalse(texts(screen.window.decorView).contains(screen.getString(R.string.settings_hide_byd_call_popup)))
        }
    }

    @Test fun oldDisplayAndWheelPreferencesStayStoredButCannotStartBydFunctions() {
        val prefs = app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("cluster_speedometer_enabled", true)
            .putBoolean("adb_cluster_activity_enabled", true)
            .putBoolean("platform21_cluster_enabled", true)
            .putInt("cluster_small_window_mode", 2).commit()
        WheelZoomSettings.setEnabled(app, true)
        WheelZoomSettings.setJoystick(app, true)
        assertFalse(AirPlayPersistence.loadClusterSpeedometerEnabled(app))
        assertFalse(AirPlayPersistence.loadAdbClusterEnabled(app))
        assertFalse(AirPlayPersistence.loadLegacyClusterEnabled(app))
        assertEquals(0, AirPlayPersistence.loadClusterSmallWindowMode(app))
        assertFalse(WheelZoomSettings.enabled(app))
        assertFalse(WheelZoomSettings.joystick(app))
        assertTrue(prefs.getBoolean("cluster_speedometer_enabled", false))
        assertEquals(2, prefs.getInt("cluster_small_window_mode", -1))
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var subscriptions = 0
        try {
            val view = ClusterSpeedometerView(controller.get()) { subscriptions++; {} }
            controller.get().setContentView(view)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, subscriptions)
            assertEquals(View.GONE, view.visibility)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun setupIgnoresAnyOldDiLinkSelectionAndKeepsAudioConfiguration() = withScreen { screen ->
        val expected = setOf(SetupGuide.Feature.AUTO_CONNECT, SetupGuide.Feature.LOCATION, SetupGuide.Feature.CLUSTER_MAP)
        for (generation in DiLinkGeneration.entries) {
            assertEquals(expected, SetupGuide.features(generation, c11 = true).map { it.feature }.toSet())
            DiLinkGeneration.save(screen, generation)
            val body = LinearLayout(screen)
            invokeBody(screen, "setupCarStep", body)
            invokeBody(screen, "setupFeaturesStep", body)
            assertFalse(texts(body).any { it.contains("DiLink", ignoreCase = true) })
        }
        val prefs = app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        assertFalse(SetupGuide.applyFirstSetupDefaults(screen, DiLinkGeneration.DILINK_3, phoneChosen = false))
        assertEquals(before, prefs.all)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w320dp-h800dp-xhdpi")
    fun compactC11SettingsFitWithLargeText() = capture("compact-zh", 320)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w1000dp-h800dp-xhdpi")
    fun fullC11SettingsFitWithLargeText() = capture("full-zh", 1000)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-ldrtl-w320dp-h800dp-xhdpi")
    fun arabicC11SettingsFitWithLargeText() = capture("compact-ar", 320)

    private fun capture(name: String, widthDp: Int) {
        RuntimeEnvironment.setFontScale(1.5f)
        withScreen { screen ->
            for ((part, methods) in mapOf("setup" to listOf("setupCarStep", "setupFeaturesStep"),
                "controls" to listOf("wheelKeysSettings"))) {
                val body = LinearLayout(screen).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = resources.configuration.layoutDirection
                }
                methods.forEach { invokeBody(screen, it, body) }
                val width = (widthDp * screen.resources.displayMetrics.density).toInt()
                body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                body.layout(0, 0, width, body.measuredHeight)
                for (text in descendants(body).filterIsInstance<TextView>()) {
                    val layout = text.layout ?: continue
                    for (line in 0 until layout.lineCount) assertEquals(text.text.toString(), 0, layout.getEllipsisCount(line))
                    assertTrue("Text clipped: ${text.text}",
                        layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                }
                val bitmap = Bitmap.createBitmap(width, body.height, Bitmap.Config.ARGB_8888)
                body.draw(Canvas(bitmap).apply { drawColor(Color.rgb(28, 32, 42)) })
                val directory = File("build/reports/c11-cleanup-ui").apply { mkdirs() }
                File(directory, "$part-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    private fun withScreen(block: (DiPlayActivity) -> Unit) {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings"))
        controller.get().applicationInfo.flags = controller.get().applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        controller.setup().visible()
        try { block(controller.get()) }
        finally { controller.pause().stop().destroy() }
    }

    private fun showCategory(screen: DiPlayActivity, category: SettingsCategory) {
        ReflectionHelpers.setField(screen, "settingsCategory", category)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
    }

    private fun invokeBody(screen: DiPlayActivity, method: String, body: LinearLayout) =
        ReflectionHelpers.callInstanceMethod<Unit>(screen, method,
            ReflectionHelpers.ClassParameter(LinearLayout::class.java, body))

    private fun texts(view: View): List<String> = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
