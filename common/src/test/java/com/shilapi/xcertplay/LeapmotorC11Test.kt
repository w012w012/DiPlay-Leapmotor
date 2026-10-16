package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
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
        // that meet the panel. The route is centred in what is left, so the leftover 3 px sit on the
        // right rather than being split evenly.
        assertEquals(AirPlayInsets(top = 72, bottom = 0, left = 190, right = 3), config.safeArea)
    }

    /**
     * The BYD cluster's measured safe area (35 % per side) must not reach the C11 square: it left the
     * route a 210x426 column of a 720 px square, which reads as a tiny map on the instrument cluster.
     * The route also no longer follows the marker's height, which used to push it up against the top
     * edge.
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
        assertEquals(527 * 648, area(c11))
        assertTrue(
            "the C11 square must give the map far more room: ${area(c11)} vs $previous before" +
                " and ${area(byd)} on the BYD safe area",
            area(c11) > previous * 3,
        )
    }

    @Test fun theMarkerSlidersMoveTheSafeArea() {
        val centred = LeapmotorClusterLayout.streamConfig(
            CarPlayClusterDisplay.Content.MAP, markerYPercent = 50,
        )
        // The marker no longer decides where the route sits, only where the car icon is.
        assertEquals(AirPlayInsets(top = 72, bottom = 0, left = 190, right = 3), centred.safeArea)
    }

    /**
     * C11 hides vendor-only sections while keeping generic Siri and external-controller controls.
     */
    @Test fun theC11HidesTheSettingsItCannotUse() {
        assertEquals(
            setOf(SettingsSection.BYD_ADB, SettingsSection.BYD_NAVIGATION),
            hiddenSettingsSections(c11 = true),
        )
        assertTrue("every other head unit keeps every section", hiddenSettingsSections(c11 = false).isEmpty())
        // The hidden sections are real sections of the architecture, not names that no longer exist.
        val all = SettingsInformationArchitecture.sectionsByCategory.values.flatten().toSet()
        assertTrue(all.containsAll(C11_HIDDEN_SETTINGS_SECTIONS))
    }

    /**
     * The car button CarPlay shows on its home screen carries the car maker's logo and label. The
     * packaged icon has to be a square no larger than the icon sizing passes through untouched, or the
     * iPhone is sent a resized copy (and a huge one can stop it connecting, #567).
     */
    @Test fun theCarButtonShipsTheLeapmotorLogoAndLabel() {
        assertEquals("leapmotor", AirPlayPersistence.DEFAULT_OEM_LABEL)
        // Resolved by name: this module's R class is not in this test's package.
        val id = app.resources.getIdentifier("leapmotor_logo", "raw", app.packageName)
        assertTrue("the packaged icon resource exists", id != 0)
        val bytes = app.resources.openRawResource(id).use { it.readBytes() }
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        assertEquals("the packaged icon is square", options.outWidth, options.outHeight)
        assertTrue(
            "the packaged icon must not need resizing: ${options.outWidth}px",
            options.outWidth in 1..AirPlayIconSizing.MAX_PIXELS,
        )
        val (size, fitted) = AirPlayIconSizing.fit(bytes)!!
        assertEquals("the bytes travel as they are", bytes.size, fitted.size)
        assertEquals(options.outWidth, size)
    }

    /**
     * The R angle sits exactly on the joint of the two band ramps, so it moves with the fade width
     * sliders instead of staying pinned to the panel.
     */
    @Test fun theCornerFollowsTheFadeWidths() {
        val wide = edgeFadeFillet(720f, 720f, 27, 10)!!
        assertEquals(194.4f, wide.x, 0.01f)
        assertEquals(72f, wide.y, 0.01f)
        assertEquals(36f, wide.radius)
        val narrow = edgeFadeFillet(720f, 720f, 15, 5)!!
        assertEquals(108f, narrow.x, 0.01f)
        assertEquals(36f, narrow.y, 0.01f)
        assertEquals(18f, narrow.radius)
        assertNull("one band alone has no corner to round", edgeFadeFillet(720f, 720f, 0, 10))
    }

    /** The C11's battery reading for CarPlay: the charge level, and no invented range. */
    @Test fun theC11BatteryReachesCarPlayWithoutARange() {
        assertNull("no reading, no electric vehicle", leapmotorBatterySnapshot(null))
        val snapshot = leapmotorBatterySnapshot(35)!!
        assertEquals(35.0, snapshot.batteryPercent, 0.0)
        assertNull("the car publishes no range", snapshot.rangeKm)
        assertNull(snapshot.maxRangeKm)
        assertFalse(snapshot.rangeWarning)
        assertEquals("a nonsense reading is clamped, never passed on", 100.0, leapmotorBatterySnapshot(120)!!.batteryPercent, 0.0)
    }

    /**
     * The wheel-speed ids this protocol looks for came from another firmware; a report taken after
     * driving still had no match. The census lists what the car really prints.
     */
    @Test fun theEventIdCensusListsWhatTheCarPrints() {
        val output = """
            1792020932.690  1484  1803 D C11CarSomeIp: onMessage length=20 eventId: 1110 value: 3
            1792020933.301  1484  1484 D C11CarSomeIp: eventid: 1402 msg:1
            1792020933.302  1484  1484 D C11CarSomeIp: eventid: 1402 msg:2
            1792020934.001  1484  1484 D C11CarSomeIp: eventId==21180, message==42
            DiPlay-C11 poll rows=1 matched=0
        """.trimIndent()
        val rows = C11LogcatProtocol.eventIdCensus(output)
        assertEquals("the most frequent id comes first", "id=1402 n=2", rows.first().substringBefore(" sample="))
        assertTrue(rows.any { it.startsWith("id=1110 n=1") })
        assertTrue("the guessed id is listed too", rows.any { it.startsWith("id=21180 n=1") })
        assertTrue("DiPlay's own lines are not counted", rows.none { it.contains("DiPlay-C11") })
        assertTrue(C11LogcatProtocol.censusCommand().contains("event[Ii]d"))
    }

    /**
     * The size presets are the only lever on how large the route looks: the stream's own size cancels
     * out (720 px window over a 480..600 px stream), so the preset shrinks how much of the fade band
     * stays off-limits instead.
     */
    @Test fun theSizePresetsTradeTheFadeBandForMapArea() {
        assertEquals(100, LeapmotorClusterLayout.contentInsetPercent(100))
        assertEquals(50, LeapmotorClusterLayout.contentInsetPercent(83))
        assertEquals(25, LeapmotorClusterLayout.contentInsetPercent(67))
        assertEquals(150, LeapmotorClusterLayout.contentInsetPercent(125))
        fun content(scale: Int): Int {
            val safe = LeapmotorClusterLayout.streamConfig(
                CarPlayClusterDisplay.Content.MAP,
                scalePercent = scale,
                contentInsetPercent = LeapmotorClusterLayout.contentInsetPercent(scale),
            ).safeArea!!
            return (720 - safe.left - safe.right) * (720 - safe.top - safe.bottom)
        }
        val standard = content(100)
        assertTrue("larger must really be larger: ${content(83)} vs $standard", content(83) > standard)
        assertTrue("largest must beat larger", content(67) > content(83))
        assertTrue("smallest must be smallest", content(125) < standard)
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
        assertNull(clusterEdgeFadeFrame(app))
        AirPlayPersistence.saveClusterMaskLeftPercent(app, 27)
        assertEquals(27, clusterEdgeFadeFrame(app)?.leftPercent)
    }

    /** Transparent fading remains the default, and the existing off choice is preserved. */
    @Test fun theTransparentFadeIsTheDefaultAndCanStillBeDisabled() {
        assertEquals(LeapmotorClusterLayout.MASK_MODE_ALPHA, AirPlayPersistence.loadClusterMaskMode(app))
        val frame = clusterEdgeFadeFrame(app)
        assertNotNull("the C11 square gets a fade frame by default", frame)
        assertEquals(27, frame!!.leftPercent)
        assertEquals(10, frame.topPercent)

        AirPlayPersistence.saveClusterMaskMode(app, LeapmotorClusterLayout.MASK_MODE_OFF)
        assertEquals(LeapmotorClusterLayout.MASK_MODE_OFF, AirPlayPersistence.loadClusterMaskMode(app))
        assertNull(clusterEdgeFadeFrame(app))

        AirPlayPersistence.saveClusterMaskMode(app, LeapmotorClusterLayout.MASK_MODE_ALPHA)
        AirPlayPersistence.saveClusterMaskLeftPercent(app, 0)
        AirPlayPersistence.saveClusterMaskTopPercent(app, 0)
        assertNull("no width, no fade", clusterEdgeFadeFrame(app))
        AirPlayPersistence.saveClusterMaskLeftPercent(app, LeapmotorClusterLayout.DEFAULT_MASK_LEFT_PERCENT)
        AirPlayPersistence.saveClusterMaskTopPercent(app, LeapmotorClusterLayout.DEFAULT_MASK_TOP_PERCENT)
    }

    @Test fun retiredColourAndUnknownSavedModesUseTransparencyWhileOffRemainsOff() {
        val prefs = app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        for (saved in listOf(1, -1, 99)) {
            // Seed the actual old value, bypassing the new writer's input normalization.
            prefs.edit().putInt("leapmotor_cluster_mask_mode", saved).commit()
            assertEquals(LeapmotorClusterLayout.MASK_MODE_ALPHA, AirPlayPersistence.loadClusterMaskMode(app))
            assertNotNull(clusterEdgeFadeFrame(app))
        }
        prefs.edit().putInt("leapmotor_cluster_mask_mode", 2).commit()
        assertEquals(LeapmotorClusterLayout.MASK_MODE_OFF, AirPlayPersistence.loadClusterMaskMode(app))
        assertNull(clusterEdgeFadeFrame(app))
        AirPlayPersistence.saveClusterMaskMode(app, 1)
        assertEquals(0, prefs.getInt("leapmotor_cluster_mask_mode", -1))
        AirPlayPersistence.saveClusterMaskMode(app, LeapmotorClusterLayout.MASK_MODE_OFF)
        assertEquals(2, prefs.getInt("leapmotor_cluster_mask_mode", -1))
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
     * Two straight ramps leave the map's corner as a right angle; the frame takes that tip away with a
     * radial fade — the R angle of the corner. Half the narrower band keeps it in proportion, and there
     * is nothing to round unless both bands exist.
     */
    @Test fun theCornerIsRoundedByHalfTheNarrowerBand() {
        assertEquals(36f, cornerFilletRadius(194.4f, 72f))
        assertEquals(0f, cornerFilletRadius(0f, 72f))
        assertEquals(0f, cornerFilletRadius(194.4f, 0f))
        // Narrowing the bands keeps the rounding in proportion, with a floor and a ceiling.
        assertEquals(8f, cornerFilletRadius(6f, 4f))
        assertEquals(72f, cornerFilletRadius(400f, 300f))
    }

    /**
     * The frame redraws with every video frame, so the gradients and the corner mask are built once per
     * size rather than once per frame — creating render objects inside draw calls bypasses the hardware
     * pipeline's caches. This also exercises the real draw path over a canvas.
     */
    @Test fun theFadeBuildsItsShadersOncePerSize() {
        val frame = ClusterEdgeFadeFrame(app, 27, 10)
        frame.layout(0, 0, 720, 720)
        val canvas = Canvas(Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888))
        frame.draw(canvas)
        frame.draw(canvas)
        assertEquals("two frames of the same size reuse the shaders", 1, frame.shaderRebuilds)
        frame.layout(0, 0, 480, 480)
        frame.draw(canvas)
        assertEquals("a resize rebuilds them", 2, frame.shaderRebuilds)
    }

    /**
     * The R angle only takes the map's own corner away. Unclipped, the same disc also darkened the two
     * bands next to the corner, which showed on the car as a distinct circular mark rather than a
     * rounded corner. Drawn for real, so the alpha values are the ones the cluster would show.
     */
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun theCornerRoundingDoesNotDarkenTheBands() {
        val frame = ClusterEdgeFadeFrame(app, 27, 10)
        frame.addView(
            View(app).apply { setBackgroundColor(Color.WHITE) },
            FrameLayout.LayoutParams(720, 720),
        )
        val spec = View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
        frame.measure(spec, spec)
        frame.layout(0, 0, 720, 720)
        val bitmap = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888)
        frame.draw(Canvas(bitmap))
        fun alpha(x: Int, y: Int) = Color.alpha(bitmap.getPixel(x, y))
        assertEquals("the panel edge is fully transparent", 0, alpha(0, 0))
        // Inside the top band, left of the joint: the band's own ramp (about 174/194 of full), not the
        // half of it the unclipped disc used to leave.
        val band = alpha(174, 71)
        assertTrue("the band keeps its own ramp: $band", band > 200)
        assertTrue("the corner tip is rounded away", alpha(196, 74) < 40)
        assertEquals("the map just beyond the fillet is untouched", 255, alpha(230, 100))
        assertEquals("the map inside is untouched", 255, alpha(400, 400))
    }

    /** The custom card is sized against the C11 square, not the BYD cluster's 29 %-wide window. */
    @Test fun theCustomTurnCardCanGrowOnTheC11Square() {
        val square = AirPlayInsets(top = 10, bottom = 0, left = 27, right = 0)
        val at95 = ClusterTurnCardOverlay.card(720, 720, 76, 30, 95, square)
        assertEquals(498, at95.width)
        // 200 % is now a choice; the panel caps it, and the card keeps its aspect instead of flattening.
        assertEquals(200, ClusterTurnCardOverlay.sizePercents.max())
        val at200 = ClusterTurnCardOverlay.card(720, 720, 76, 30, 200, square)
        assertEquals(720, at200.width)
        assertTrue("a bigger card is a taller card too", at200.height > at95.height)
        // The same percent on the BYD window is a much smaller share of its panel: the window differs.
        val byd = ClusterTurnCardOverlay.card(1920, 720, 76, 30, 95)
        assertTrue(
            "the C11 window must give a bigger card for its panel: ${at95.width}/720 vs ${byd.width}/1920",
            at95.width * 100 / 720 > byd.width * 100 / 1920,
        )
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
