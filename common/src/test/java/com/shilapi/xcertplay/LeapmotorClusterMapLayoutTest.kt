package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.TextureView
import android.view.View
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.backup.SettingsBackupCodec
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class LeapmotorClusterMapLayoutTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    private val fade = AirPlayInsets(left = 27, top = 10)

    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun oldPresetsKeepTheirFitAreaAtNativeResolution() {
        for ((legacy, size) in listOf(100 to 100, 83 to 150, 67 to 175, 125 to 50)) {
            prefs.edit().putInt("cluster_map_scale_percent", legacy).commit()
            val settings = LeapmotorClusterMapLayout.load(context)
            assertEquals(size, settings.size)
            val expected = LeapmotorClusterLayout.streamConfig(CarPlayClusterDisplay.Content.MAP,
                contentInsetPercent = LeapmotorClusterLayout.contentInsetPercent(legacy)).safeArea
            assertEquals(expected, LeapmotorClusterMapLayout.plan(settings, fade).safeArea)
            val stream = LeapmotorClusterMapLayout.streamConfig(context, CarPlayClusterDisplay.Content.MAP)
            assertEquals(720, stream.widthPixels)
            assertEquals(720, stream.heightPixels)
        }
    }

    @Test fun positionMovesTheWholeFitBoxWithoutShrinkingIt() {
        val first = LeapmotorClusterMapLayout.plan(LeapmotorClusterMapLayout.Settings(100, 37, 45), fade)
        val last = LeapmotorClusterMapLayout.plan(LeapmotorClusterMapLayout.Settings(100, 63, 55), fade)
        assertTrue(last.safeArea.left > first.safeArea.left)
        assertTrue(last.safeArea.top > first.safeArea.top)
        assertEquals(first.safeArea.left + first.safeArea.right, last.safeArea.left + last.safeArea.right)
        assertEquals(first.safeArea.top + first.safeArea.bottom, last.safeArea.top + last.safeArea.bottom)
    }

    @Test fun largeMapsAllowFullHorizontalAndVerticalCentreTravel() {
        for (size in listOf(100, 150, 200)) {
            val first = LeapmotorClusterMapLayout.plan(LeapmotorClusterMapLayout.Settings(size, 5, 5), fade)
            val last = LeapmotorClusterMapLayout.plan(LeapmotorClusterMapLayout.Settings(size, 95, 95), fade)
            assertEquals("Horizontal travel must extend beyond the old fit-box limit", 5..95, first.xRange)
            assertEquals("Vertical travel must extend beyond the old fit-box limit", 5..95, first.yRange)
            assertEquals(5, first.x)
            assertEquals(5, first.y)
            assertEquals(95, last.x)
            assertEquals(95, last.y)
            fun centre(insets: AirPlayInsets) =
                (insets.left + 720 - insets.right) / 2.0 to (insets.top + 720 - insets.bottom) / 2.0
            assertEquals(36.0, centre(first.safeArea).first, 1.0)
            assertEquals(36.0, centre(first.safeArea).second, 1.0)
            assertEquals(684.0, centre(last.safeArea).first, 1.0)
            assertEquals(684.0, centre(last.safeArea).second, 1.0)
        }
    }

    @Test fun edgePositionsRefitTheOverviewWithoutMovingTheVideoWindow() {
        val middle = LeapmotorClusterMapLayout.plan(LeapmotorClusterMapLayout.Settings(150, 50, 50), fade)
        val edge = LeapmotorClusterMapLayout.plan(LeapmotorClusterMapLayout.Settings(150, 5, 95), fade)
        assertTrue(edge.safeArea.left + edge.safeArea.right > middle.safeArea.left + middle.safeArea.right)
        assertTrue(edge.safeArea.top + edge.safeArea.bottom > middle.safeArea.top + middle.safeArea.bottom)
        LeapmotorClusterMapLayout.saveSize(context, 150)
        LeapmotorClusterMapLayout.saveX(context, 5)
        LeapmotorClusterMapLayout.saveY(context, 95)
        val saved = LeapmotorClusterMapLayout.load(context)
        assertEquals(5, saved.x)
        assertEquals(95, saved.y)
        val stream = LeapmotorClusterMapLayout.streamConfig(context, CarPlayClusterDisplay.Content.MAP)
        assertEquals(720, stream.widthPixels)
        assertEquals(720, stream.heightPixels)
        assertEquals(edge.safeArea, stream.safeArea)
    }

    @Test fun fix22TestedPositionKeepsItsMeaningAndDirectionalOffsetsUseResetPosition() {
        prefs.edit().putInt(LeapmotorClusterMapLayout.SIZE_KEY, 150)
            .putInt(LeapmotorClusterMapLayout.X_KEY, 47)
            .putInt(LeapmotorClusterMapLayout.Y_KEY, 52).commit()
        val plan = LeapmotorClusterMapLayout.plan(LeapmotorClusterMapLayout.load(context), fade)
        // Captured from the user's fix22 log at 12:23:54; upgrade keeps these coordinates.
        assertEquals(AirPlayInsets(top = 32, bottom = 3, left = 28, right = 72), plan.safeArea)
        val reset = LeapmotorClusterMapLayout.plan(LeapmotorClusterMapLayout.Settings(), fade)
        assertEquals(-115, LeapmotorClusterMapLayout.offsetPixels(plan.x, reset.x))
        assertEquals(-22, LeapmotorClusterMapLayout.offsetPixels(plan.y, reset.y))
        assertEquals(0, LeapmotorClusterMapLayout.offsetPixels(reset.x, reset.x))
    }

    @Test fun everySizeAndExtremePositionProducesANonemptyInBoundsRectangle() {
        for (size in 25..200) for (edge in listOf(0, 1, 10, 27, 75, 100)) {
            val insets = AirPlayInsets(left = edge, top = edge)
            for (centre in listOf(-100, 5, 50, 95, 200)) {
                val plan = LeapmotorClusterMapLayout.plan(LeapmotorClusterMapLayout.Settings(size, centre, centre), insets)
                val area = plan.safeArea
                assertTrue(area.left >= 0 && area.right >= 0 && area.top >= 0 && area.bottom >= 0)
                assertTrue(area.left + area.right < 720 && area.top + area.bottom < 720)
                assertTrue(plan.x in plan.xRange && plan.y in plan.yRange)
            }
        }
    }

    @Test fun persistenceResetAndBackupRoundTripPreserveIndependentLayout() {
        prefs.edit().putInt("cluster_map_scale_percent", 67).commit()
        LeapmotorClusterMapLayout.saveSize(context, 111)
        LeapmotorClusterMapLayout.saveX(context, 48)
        LeapmotorClusterMapLayout.saveY(context, 50)
        val expected = LeapmotorClusterMapLayout.load(context)
        val backup = SettingsBackupCodec.export(context, "test")
        prefs.edit().clear().commit()
        assertFalse(SettingsBackupCodec.import(context, backup).invalid)
        assertEquals(expected, LeapmotorClusterMapLayout.load(context))
        LeapmotorClusterMapLayout.reset(context)
        assertEquals(LeapmotorClusterMapLayout.Settings(), LeapmotorClusterMapLayout.load(context))
        assertEquals(67, prefs.getInt("cluster_map_scale_percent", 0))
    }

    @Test fun shrinkingKeepsTheCentreAndAllowsMovingBothWays() {
        val before = LeapmotorClusterMapLayout.plan(LeapmotorClusterMapLayout.load(context), fade)
        LeapmotorClusterMapLayout.saveSize(context, 25)
        val small = LeapmotorClusterMapLayout.plan(LeapmotorClusterMapLayout.load(context), fade)
        assertEquals(before.x, small.x)
        assertEquals(before.y, small.y)
        assertTrue(small.xRange.first < small.x && small.xRange.last > small.x)
        assertTrue(small.yRange.first < small.y && small.yRange.last > small.y)
        assertTrue(small.safeArea.left + small.safeArea.right > before.safeArea.left + before.safeArea.right)
        assertTrue(small.safeArea.top + small.safeArea.bottom > before.safeArea.top + before.safeArea.bottom)
    }

    @Test fun sizeAndPositionStillWorkWithTheVisualFadeOffAndZeroSavedWidths() {
        AirPlayPersistence.saveClusterMaskMode(context, LeapmotorClusterLayout.MASK_MODE_OFF)
        AirPlayPersistence.saveClusterMaskLeftPercent(context, 0)
        AirPlayPersistence.saveClusterMaskTopPercent(context, 0)
        val before = LeapmotorClusterMapLayout.streamConfig(context, CarPlayClusterDisplay.Content.MAP)
        LeapmotorClusterMapLayout.saveSize(context, 25)
        LeapmotorClusterMapLayout.saveX(context, 70)
        LeapmotorClusterMapLayout.saveY(context, 65)
        val after = LeapmotorClusterMapLayout.streamConfig(context, CarPlayClusterDisplay.Content.MAP)
        assertNotEquals(before.safeArea, after.safeArea)
        val plan = LeapmotorClusterMapLayout.plan(LeapmotorClusterMapLayout.load(context),
            LeapmotorClusterMapLayout.fadeInsets(context))
        assertEquals(70, plan.x)
        assertEquals(65, plan.y)
        assertEquals(720, after.widthPixels)
    }

    @Test fun oldPixelProbeAndNewSettingsNeverTransformTheActualPresentation() {
        val original = android.os.Build.FINGERPRINT
        ShadowBuild.setFingerprint("qti/msmnile_gvmq/msmnile_gvmq:9/PQ1A/test:user/test-keys")
        context.getSharedPreferences("leapmotor_cluster_map_offset", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).commit()
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION)
        val presentation = ClusterMapPresentation(context, display) {}
        try {
            presentation.show()
            val video = ReflectionHelpers.getField<TextureView>(presentation, "videoView")
            val frame = video.parent as ClusterEdgeFadeFrame
            val spec = View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
            frame.measure(spec, spec)
            frame.layout(0, 0, 720, 720)
            LeapmotorClusterMapLayout.saveSize(context, 150)
            LeapmotorClusterMapLayout.saveX(context, 5)
            LeapmotorClusterMapLayout.saveY(context, 95)
            assertTrue(video.getTransform(Matrix()).isIdentity)
            assertEquals(0f, frame.translationX, 0f)
            assertEquals(0f, frame.translationY, 0f)
            assertEquals(720, frame.width)
            assertEquals(27, frame.leftPercent)
            assertEquals(10, frame.topPercent)
            presentation.dismiss()
            presentation.show()
            assertTrue(video.getTransform(Matrix()).isIdentity)
        } finally {
            presentation.dismiss()
            ShadowDisplayManager.removeDisplay(id)
            ShadowBuild.setFingerprint(original)
            context.getSharedPreferences("leapmotor_cluster_map_offset", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
