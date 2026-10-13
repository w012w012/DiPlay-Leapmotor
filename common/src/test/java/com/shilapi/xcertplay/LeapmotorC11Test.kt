package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDisplayManager

/**
 * The C11 paths added on top of the Leapmotor adaptation: the wheel bridge, the square stream and
 * the meter display choice. The numbers come from the 0.2.15-245 C11 build and the head unit's own
 * diagnostic report, so they can be checked without the car.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class LeapmotorC11Test {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(DisplayManager::class.java)
    private var originalFingerprint: String? = null

    @Before fun setUp() {
        originalFingerprint = android.os.Build.FINGERPRINT
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        originalFingerprint?.let(ShadowBuild::setFingerprint)
    }

    private fun asC11() = ShadowBuild.setFingerprint(
        "qti/msmnile_gvmq/msmnile_gvmq:9/PQ1A.190105.004/05190841:user/test-keys",
    )

    private fun display(name: String, spec: String = "w1920dp-h720dp-mdpi"): Int {
        val id = ShadowDisplayManager.addDisplay(spec, 5)
        shadowOf(manager.getDisplay(id)).apply {
            setName(name)
            setFlags(Display.FLAG_PRESENTATION)
        }
        return id
    }

    @Test fun steeringWheelBridgeNumbersTheKeysInTheActionExtra() {
        val channel = "com.leapmotor.steerwheelcontrol.media"
        assertEquals("preOne", LeapmotorMediaProtocol.steerWheelAction(Intent(channel).putExtra("action", 1)))
        assertEquals("nextOne", LeapmotorMediaProtocol.steerWheelAction(Intent(channel).putExtra("action", 4)))
        assertEquals("playpause", LeapmotorMediaProtocol.steerWheelAction(Intent(channel).putExtra("action", 7)))
        assertNull(LeapmotorMediaProtocol.steerWheelAction(Intent(channel).putExtra("action", 5)))
        assertNull(LeapmotorMediaProtocol.steerWheelAction(Intent(channel)))
    }

    @Test fun theBridgeAndTheIntExtraChannelsAreRegistered() {
        assertTrue(LeapmotorMediaProtocol.ALL_ACTIONS.contains("com.leapmotor.steerwheelcontrol"))
        assertTrue(LeapmotorMediaProtocol.ALL_ACTIONS.contains("com.leapmotor.steerwheelcontrol.media"))
        assertTrue(LeapmotorMediaProtocol.ALL_ACTIONS.contains("com.leapmotor.steerwheelcontrol.phone"))
        assertTrue(LeapmotorMediaProtocol.ALL_ACTIONS.contains("com.leapmotor.ICU2MMICtrl"))
        assertTrue(LeapmotorMediaProtocol.ALL_ACTIONS.contains("com.leapmotor.customkey.music.pauseplay"))
        assertEquals(LeapmotorMediaProtocol.ACTIONS.size + 2, LeapmotorMediaProtocol.ALL_ACTIONS.size)
    }

    @Test fun theSquareStreamMatchesTheMeasuredCanvas() {
        val config = LeapmotorClusterLayout.streamConfig(CarPlayClusterDisplay.Content.MAP)
        assertEquals(720, config.widthPixels)
        assertEquals(720, config.heightPixels)
        assertEquals(30, config.fps)
        assertEquals(CarPlayClusterDisplay.MAP_URL, config.initialUrl)
        assertEquals(AirPlayInsets(top = 255, bottom = 39, left = 255, right = 255), config.safeArea)
    }

    @Test fun theMarkerSlidersMoveTheSafeArea() {
        val centred = LeapmotorClusterLayout.streamConfig(
            CarPlayClusterDisplay.Content.MAP, markerYPercent = 50,
        )
        assertEquals(AirPlayInsets(top = 147, bottom = 147, left = 255, right = 255), centred.safeArea)
    }

    @Test fun theC11MeterDisplayIsFoundByPanelGeometry() {
        asC11()
        val id = display("HDMI 屏幕")
        try {
            assertEquals(id, ClusterMapPresentation.findDisplay(app)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun theDriverChoiceOverridesTheAutomaticMeterChoice() {
        asC11()
        val first = display("HDMI 屏幕")
        val second = display("HDMI 屏幕")
        AirPlayPersistence.saveClusterDisplayId(app, first)
        try {
            assertEquals(first, ClusterMapPresentation.findDisplay(app)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(second)
            ShadowDisplayManager.removeDisplay(first)
        }
    }

    @Test fun anotherFirmwareKeepsTheBydSelection() {
        val id = display("HDMI 屏幕")
        try {
            assertNull(ClusterMapPresentation.findDisplay(app))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }
}
