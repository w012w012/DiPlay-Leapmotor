package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // Only the faded band is declared off-limits: 27 % left, 10 % top, nothing on the two edges
        // that meet the panel.
        assertEquals(AirPlayInsets(top = 216, bottom = 0, left = 97, right = 97), config.safeArea)
    }

    /**
     * The BYD cluster's measured safe area (35 % per side) must not reach the C11 square: on a 720 px
     * square it left the route a 210x426 column, which reads as a tiny map on the instrument cluster.
     */
    @Test fun theSquareGivesTheMapFarMoreRoomThanTheBydClusterSafeArea() {
        val c11 = LeapmotorClusterLayout.streamConfig(CarPlayClusterDisplay.Content.MAP).safeArea!!
        val byd = CarPlayClusterDisplay.config(
            widthPixels = 720,
            heightPixels = 720,
            content = CarPlayClusterDisplay.Content.MAP,
        ).safeArea!!
        fun area(insets: AirPlayInsets) =
            (720 - insets.left - insets.right) * (720 - insets.top - insets.bottom)
        // What the C11 square declared before this fix, straight from the car's own report.
        val previous = (720 - 255 - 255) * (720 - 111 - 183)
        assertEquals(210 * 426, previous)
        assertEquals(526 * 504, area(c11))
        assertTrue(
            "the C11 square must give the map far more room: ${area(c11)} vs $previous before" +
                " and ${area(byd)} on the BYD safe area",
            area(c11) > previous * 2,
        )
    }

    @Test fun theMarkerSlidersMoveTheSafeArea() {
        val centred = LeapmotorClusterLayout.streamConfig(
            CarPlayClusterDisplay.Content.MAP, markerYPercent = 50,
        )
        assertEquals(AirPlayInsets(top = 36, bottom = 36, left = 97, right = 97), centred.safeArea)
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

    @Test fun theGearIsReadFromTheSomeIpLogLine() {
        assertEquals(3, C11LogcatProtocol.gearValue("10-12 21:01:58.501  1234  5678 I C11CarSomeIp: onMessage  eventId: 1110 value: 3"))
        assertEquals("R", C11LogcatProtocol.gearName(1))
        assertEquals("N", C11LogcatProtocol.gearName(2))
        assertEquals("D", C11LogcatProtocol.gearName(3))
        assertEquals("P", C11LogcatProtocol.gearName(4))
        assertNull(C11LogcatProtocol.gearName(0))
    }

    /** The real 2023 C11 prints the gear over three bridge lines; the value line carries the number. */
    @Test fun theBridgeThreeLineGearIsRead() {
        assertEquals(3, C11LogcatProtocol.gearValue(
            "MediaTlog - CtrlService: Recive the gear is : 3"))
        assertNull(C11LogcatProtocol.gearValue("MediaTlog - CtrlService: Start recive someIp message eventId is : 1110"))
        assertNull(C11LogcatProtocol.gearValue("MediaTlog - CtrlService: End recive someIp message eventId is : 1110"))
    }

    /** The cluster's XML nodes republish the position with an attribute on the tag. */
    @Test fun theAttributedXmlGearIsRead() {
        assertEquals(1, C11LogcatProtocol.gearValue("C11CarXml: node_name : gear  setTextContent: 1"))
        assertEquals(2, C11LogcatProtocol.gearValue("C11AirConditioner: node_name : gear  setTextContent: 2"))
        assertEquals(2, C11LogcatProtocol.gearValue("<Seat><gear type=\"int\">2</gear></Seat>"))
        assertNull(C11LogcatProtocol.gearValue("AroundService: dealShift mShift 3"))
    }

    @Test fun anotherEventIdIsNotReadAsTheGear() {
        assertNull(C11LogcatProtocol.gearValue("onMessage  eventId: 11100 value: 3"))
        assertNull(C11LogcatProtocol.gearValue("onMessage  eventId: 9110 value: 3"))
        assertNull(C11LogcatProtocol.gearValue("nothing interesting here"))
    }

    /** The captured battery XML: `<Battery><BatteryModel .../><figure type="int">36</figure>`. */
    @Test fun theBatteryFigureIsReadAsTheSoc() {
        assertEquals(36, C11LogcatProtocol.socValue(
            "C11CarXml: xmlStr: <carcontrol><Battery><BatteryModel type=\"int\">2</BatteryModel>" +
                "<figure type=\"int\">36</figure></Battery></carcontrol>"))
        assertNull(C11LogcatProtocol.socValue("<figureMin type=\"int\">0</figureMin>"))
        assertNull(C11LogcatProtocol.socValue("nothing interesting here"))
    }

    /** The speed candidates the capture shows while parked; the unit still awaits a driving capture. */
    @Test fun theBridgeSpeedCandidatesAreRead() {
        assertEquals(10, C11LogcatProtocol.speedValue(
            "logerss : SpeechSomeIP CanSubSomeIP eventId==21180, message==10"))
        assertEquals(20, C11LogcatProtocol.speedValue(
            "logerss : SpeechSomeIP CanSubSomeIP eventId==21181, message==20"))
        assertEquals(0, C11LogcatProtocol.speedValue(
            "logerss : SpeechSomeIP CanSubSomeIP eventId==21180, message==0"))
        assertNull(C11LogcatProtocol.speedValue("eventId==21181, message==400"))
    }

    @Test fun theEdgeFadeWidthsFollowTheMeasuredMask() {
        assertEquals(27, LeapmotorClusterLayout.DEFAULT_MASK_LEFT_PERCENT)
        assertEquals(10, LeapmotorClusterLayout.DEFAULT_MASK_TOP_PERCENT)
    }

    /** A bare `<figure>` is not the battery: the line must name the battery block, or nothing is read. */
    @Test fun aBareFigureIsNotTheBatteryReading() {
        assertNull(C11LogcatProtocol.socValue("<figure type=\"int\">36</figure>"))
        assertEquals(36, C11LogcatProtocol.socValue(
            "<Battery><figure type=\"int\">36</figure><figureMin type=\"int\">0</figureMin></Battery>"))
    }

    /** The rare battery and speed lines need a wider window than the one-second gear poll offers. */
    @Test fun theSlowPollWidensTheWindowForTheBattery() {
        val fast = C11LogcatProtocol.pollCommand()
        val slow = C11LogcatProtocol.slowPollCommand()
        assertTrue(slow.startsWith("logcat -d -t 40000"))
        assertTrue(slow.contains("<Battery"))
        assertTrue(slow.contains("eventId==2118"))
        assertFalse(slow.contains("C11SmartContrl"))
        assertFalse(fast.contains("<Battery"))
    }

    /** Both widths at zero means no fade at all, so the view is never built. */
    @Test fun aZeroWidthFadeIsNotBuiltAtAll() {
        AirPlayPersistence.saveClusterMaskLeftPercent(app, 0)
        AirPlayPersistence.saveClusterMaskTopPercent(app, 0)
        assertNull(clusterEdgeMask(app))
        AirPlayPersistence.saveClusterMaskLeftPercent(app, 27)
        assertEquals(27, clusterEdgeMask(app)?.leftPercent)
    }

    /** The alpha fade is the default; the painted band stays available and 0 widths still mean none. */
    @Test fun theTransparentFadeIsTheDefaultAndTheBandStaysAvailable() {
        assertEquals(LeapmotorClusterLayout.MASK_MODE_ALPHA, clusterEdgeFadeMode(app))
        val frame = clusterEdgeFadeFrame(app)
        assertNotNull("the C11 square gets a fade frame by default", frame)
        assertEquals(27, frame!!.leftPercent)
        assertEquals(10, frame.topPercent)

        AirPlayPersistence.saveClusterMaskMode(app, LeapmotorClusterLayout.MASK_MODE_COLOR)
        assertEquals(LeapmotorClusterLayout.MASK_MODE_COLOR, clusterEdgeFadeMode(app))
        assertNull("the frame belongs to the alpha mode only", clusterEdgeFadeFrame(app))
        assertNotNull("the colour mode still builds the painted band", clusterEdgeMask(app))

        AirPlayPersistence.saveClusterMaskMode(app, LeapmotorClusterLayout.MASK_MODE_OFF)
        assertEquals(LeapmotorClusterLayout.MASK_MODE_OFF, clusterEdgeFadeMode(app))
        assertNull(clusterEdgeFadeFrame(app))

        AirPlayPersistence.saveClusterMaskMode(app, LeapmotorClusterLayout.MASK_MODE_ALPHA)
        AirPlayPersistence.saveClusterMaskLeftPercent(app, 0)
        AirPlayPersistence.saveClusterMaskTopPercent(app, 0)
        assertNull("no width, no fade", clusterEdgeFadeFrame(app))
        AirPlayPersistence.saveClusterMaskLeftPercent(app, LeapmotorClusterLayout.DEFAULT_MASK_LEFT_PERCENT)
        AirPlayPersistence.saveClusterMaskTopPercent(app, LeapmotorClusterLayout.DEFAULT_MASK_TOP_PERCENT)
    }

    /**
     * The ramp runs from the panel edge inward: gone at the seam, fully back at the band's inner end.
     * Reversed (visible at the edge, erased inside) the hard edge survives and a band disappears out of
     * the middle of the map instead — the first build of this fade did exactly that on the car.
     */
    @Test fun theTransparentFadeStartsAtThePanelEdgeAndEndsOpaque() {
        val left = leftEdgeFadeRamp(720f, 27)!!
        assertEquals(0f, left.fromX)
        assertEquals(0f, left.fromY)
        assertEquals(194.4f, left.toX, 0.01f)
        assertEquals(0f, left.toY)
        assertEquals("the map must be gone at the left seam", 0, left.edgeAlpha)
        assertEquals("and fully back 27 % in", 255, left.innerAlpha)

        val top = topEdgeFadeRamp(720f, 10)!!
        assertEquals(0f, top.fromY)
        assertEquals(72f, top.toY, 0.01f)
        assertEquals("the map must be gone at the top seam", 0, top.edgeAlpha)
        assertEquals("and fully back 10 % down", 255, top.innerAlpha)

        assertNull("a zero width is no band at all", leftEdgeFadeRamp(720f, 0))
        assertNull(topEdgeFadeRamp(720f, 0))
        assertNull("no width to ramp over", leftEdgeFadeRamp(0f, 27))
    }

    /**
     * The fade has to sit above the video: a SurfaceView clears the canvas of the region it draws, so
     * a fade added before it is erased on the first frame and the map stays hard-edged. The helper
     * inserts it right after the video, and lays it out like the video, so it still covers the square
     * when the window is not pinned to the viewport.
     */
    @Test fun theEdgeFadeIsInsertedAboveTheVideo() {
        AirPlayPersistence.saveClusterMaskLeftPercent(app, 27)
        AirPlayPersistence.saveClusterMaskTopPercent(app, 10)
        val root = FrameLayout(app)
        val video = SurfaceView(app)
        val params = FrameLayout.LayoutParams(720, 720, Gravity.TOP or Gravity.LEFT).apply { leftMargin = 1200 }
        root.addView(video, params)
        root.addView(View(app), FrameLayout.LayoutParams(-1, -1))
        val mask = attachClusterEdgeMask(root, video, params, app, night = true)
        assertNotNull("the fade must be built when a width is set", mask)
        assertEquals(root.indexOfChild(video) + 1, root.indexOfChild(mask))
        assertEquals(720, mask!!.layoutParams.width)
        assertEquals(720, mask.layoutParams.height)
        assertEquals(1200, (mask.layoutParams as FrameLayout.LayoutParams).leftMargin)
    }

    /**
     * The route decision and the fade's fate have to reach the report: they used to be logcat-only,
     * and the report carries the session log, so a missing fade and a missing log line looked alike.
     */
    @Test fun theProjectionRouteAndFadeAreReported() {
        ClusterProjectionState.reset()
        val lines = mutableListOf<String>()
        ClusterProjectionState.onDiagnostic = { lines.add(it) }
        try {
            ClusterProjectionState.presentation("cluster presentation shown display=2 name=HDMI")
            ClusterProjectionState.mask("leapmotor mask attached route=presentation left=27% top=10%")
            val report = ClusterProjectionState.reportLines().joinToString("\n")
            assertTrue(report.contains("clusterProjectionRoutes=1"))
            assertTrue(report.contains("clusterProjectionPresentation=cluster presentation shown display=2"))
            assertTrue(report.contains("clusterProjectionMask=leapmotor mask attached route=presentation"))
            assertTrue(lines.any { it.contains("mask attached") })
        } finally {
            ClusterProjectionState.onDiagnostic = null
            ClusterProjectionState.reset()
        }
    }

    @Test fun thePollCommandStaysBoundedAndExcludesOurOwnPrints() {
        val command = C11LogcatProtocol.pollCommand()
        assertTrue(command.startsWith("logcat -d -t 2000"))
        assertTrue(command.contains("C11CarSomeIp"))
        assertTrue(command.contains("grep -v 'DiPlay-'"))
        assertTrue(command.contains("tail -n 600"))
    }

    @Test fun theWheelProbeRecognisesWheelAndVoiceLines() {
        assertTrue(C11LogcatProtocol.isWheelLine("onMessage WHEEL_360_ID value: 48"))
        assertTrue(C11LogcatProtocol.isWheelLine("com.leapmotor.steerwheelcontrol.media"))
        assertTrue(C11LogcatProtocol.isWheelLine("MediaTlog: keyEvent 87"))
        assertFalse(C11LogcatProtocol.isWheelLine("C11CarXml: <status><gear>2</gear></status>"))
        // The music widgets' progress refreshes are not wheel events; bare `music` floods the report.
        assertFalse(C11LogcatProtocol.isWheelLine("MediaTlog - BtMusicManager: mBtReceiver pbb is : PlaybackState"))
        assertFalse(C11LogcatProtocol.isWheelLine("MusicWidget: [播放状态] isPlaying=true"))
    }

    @Test fun theC11TagFilterMatchesOnlyTheBridgeTags() {
        assertTrue(C11LogcatProtocol.isC11Line("10-12 21:01:58.501 I C11CarSomeIp: hello"))
        assertFalse(C11LogcatProtocol.isC11Line("10-12 21:01:58.501 I ActivityManager: hello"))
    }
}
