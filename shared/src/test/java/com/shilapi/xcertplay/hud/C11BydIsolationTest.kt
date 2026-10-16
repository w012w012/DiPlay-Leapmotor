package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.HeadUnitPlatform
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class C11BydIsolationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var fingerprint: String

    @Before fun setup() {
        fingerprint = android.os.Build.FINGERPRINT
        ShadowBuild.setFingerprint("qti/msmnile_gvmq/msmnile_gvmq:9/PQ1A/test:user/test-keys")
        app.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun cleanup() {
        ShadowBuild.setFingerprint("qti/msmnile_gvmq/msmnile_gvmq:9/PQ1A/test:user/test-keys")
        BydNavigationOutputs.setTurnOverlayListener(null)
        BydNavigationOutputs.endNow()
        BydClusterMapPause.streamControl = null
        BydClusterMapPause.onNaviMode = null
        ShadowBuild.setFingerprint(fingerprint)
    }

    @Test fun hardwareSettingsIgnoreOldValuesWithoutErasingGenericOptions() {
        val prefs = app.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE)
        val keys = listOf("navigation_enabled", "cluster_stream_pause", "battery_to_iphone",
            "wheel_speed_to_iphone", "video_while_parked", "cluster_song", "carplay_calls",
            "carplay_call_controls_experimental", "legacy_vehicle_probe", "hud_song",
            "phone_audio_route_experimental", "phone_audio_route_apps_experimental")
        prefs.edit().also { edit -> keys.forEach { edit.putBoolean(it, true) } }.commit()
        assertTrue(HeadUnitPlatform.isLeapmotorC11())
        assertTrue(HeadUnitPlatform.isLeapmotorC11("QTI/MSMNILE_GVMQ/test"))
        assertFalse(HeadUnitPlatform.isLeapmotorC11("BYD/dilink/5.1"))
        assertFalse(BydOutputSettings.enabled(app))
        assertFalse(BydOutputSettings.clusterStreamPause(app))
        assertFalse(BydOutputSettings.batteryToIphoneActive(app))
        assertFalse(BydOutputSettings.wheelSpeedToIphoneActive(app))
        assertFalse(BydOutputSettings.videoWhileParkedActive(app))
        assertFalse(BydOutputSettings.clusterSong(app))
        assertFalse(BydOutputSettings.carPlayCalls(app))
        assertFalse(BydOutputSettings.carPlayCallControls(app))
        assertFalse(BydOutputSettings.legacyVehicleProbe(app))
        assertFalse(BydOutputSettings.hudSong(app))
        assertTrue(BydOutputSettings.phoneAudioRoute(app))
        assertTrue(BydOutputSettings.phoneAudioRouteApps(app))
        assertTrue(keys.all { prefs.getBoolean(it, false) })

        ShadowBuild.setFingerprint("BYD/dilink/test")
        assertTrue(BydOutputSettings.enabled(app))
        assertTrue(BydOutputSettings.clusterSong(app))
        assertTrue(BydOutputSettings.carPlayCalls(app))
        assertTrue(BydOutputSettings.legacyVehicleProbe(app))
    }

    @Test fun directReadersAndProbeDoNotStartOnC11() {
        // Robolectric reuses these singletons after non-C11 tests. Check that a C11
        // call cannot create or refresh work, including when a reader already exists.
        val tyreTask = ReflectionHelpers.getField<Any?>(BydTyreStatus, "task")
        val speedExecutor = ReflectionHelpers.getField<Any?>(BydWheelSpeedSource, "executor")
        val batteryStarted = ReflectionHelpers.getField<Boolean>(BydBatteryStatus, "started")
        val batteryAsked = ReflectionHelpers.getField<Long>(BydBatteryStatus, "askedMillis")
        val batteryContext = ReflectionHelpers.getField<Any?>(BydBatteryStatus, "context")
        val tickerStarted = ReflectionHelpers.getField<AtomicBoolean>(BydClusterMapPause, "tickerStarted").get()
        val tickerContext = ReflectionHelpers.getField<Any?>(BydClusterMapPause, "context")
        BydBatteryStatus.start(app)
        BydTyreStatus.start(app)
        BydWheelSpeedSource.attach(app).start()
        BydClusterMapPause.initialize(app)
        assertSame(tyreTask, ReflectionHelpers.getField<Any?>(BydTyreStatus, "task"))
        assertSame(speedExecutor, ReflectionHelpers.getField<Any?>(BydWheelSpeedSource, "executor"))
        assertEquals(batteryStarted, ReflectionHelpers.getField<Boolean>(BydBatteryStatus, "started"))
        assertEquals(batteryAsked, ReflectionHelpers.getField<Long>(BydBatteryStatus, "askedMillis"))
        assertSame(batteryContext, ReflectionHelpers.getField<Any?>(BydBatteryStatus, "context"))
        assertEquals(tickerStarted, ReflectionHelpers.getField<AtomicBoolean>(BydClusterMapPause, "tickerStarted").get())
        assertSame(tickerContext, ReflectionHelpers.getField<Any?>(BydClusterMapPause, "context"))
        var batteryReads = 0
        assertNull(BydBatteryStatus.read(app) { batteryReads++; null })
        assertEquals(0, batteryReads)
        var statusReads = 0
        val status = BydAdbAccess.readStatus(app) { statusReads++; error("BYD shell reached") }
        assertEquals(BydAdbAccess.State.READY, status.state)
        assertNull(status.batteryPercent)
        assertEquals(0, statusReads)
        assertNull(BydParkedState.parked(app))
        val updates = mutableListOf<Int?>()
        BydClusterSpeedReader.observe(app) { updates += it }.invoke()
        assertEquals(listOf<Int?>(null), updates)
        val rejected = runCatching { BydVehicleCapabilityProbe.probe(app) }.exceptionOrNull()
        assertTrue(rejected is IllegalStateException)
        assertTrue(rejected?.message.orEmpty().contains("C11"))
    }

    @Test fun c11KeepsPhoneRouteAndCallStateWhileVendorWorkersStayIdle() {
        val workers = listOf("standalone", "cluster").associateWith { name ->
            ReflectionHelpers.getField<NavigationOutputWorker>(BydNavigationOutputs, name)
        }
        val submitted = workers.mapValues { (_, worker) ->
            worker.clearIfStarted()
            val executor = ReflectionHelpers.getField<ThreadPoolExecutor>(worker, "executor")
            // Drain a worker left by another test; do not create a fresh thread ourselves.
            if (executor.poolSize > 0) executor.submit {}.get(5, TimeUnit.SECONDS)
            executor.taskCount
        }
        val watcher = ReflectionHelpers.getField<Any?>(BydCarPlayCall, "watcherToken")
        BydOutputSettings.setCarPlayCalls(app, true)
        BydNavigationOutputs.start(app)
        BydNavigationOutputs.carPlaySessionStarted()
        BydNavigationOutputs.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 2); string(1, "Caller"); string(4, "call-1")
        })
        assertEquals(CarPlayCallCard.Phase.RINGING, BydNavigationOutputs.carPlayCall()?.phase)
        for ((name, worker) in workers) {
            val executor = ReflectionHelpers.getField<ThreadPoolExecutor>(worker, "executor")
            assertFalse("C11 must not activate $name", ReflectionHelpers.getField<Boolean>(worker, "accepting"))
            assertEquals("C11 must not submit work to $name", submitted.getValue(name), executor.taskCount)
        }
        assertSame(watcher, ReflectionHelpers.getField<Any?>(BydCarPlayCall, "watcherToken"))
        BydNavigationOutputs.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 0); string(4, "call-1")
        })
        assertNull(BydNavigationOutputs.carPlayCall())
    }

    @Test fun c11StillReceivesTurnCardsFromRealIphoneFramesAndRetainsThemAcrossReconnect() {
        val events = mutableListOf<ClusterTurnGuidance?>()
        BydNavigationOutputs.setTurnOverlayListener { events += it }
        BydNavigationOutputs.onFrame(Iap2Messages.buildRaw(BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) {
            u16(0x01, 7); u8(0x03, 1)
        })
        BydNavigationOutputs.onFrame(Iap2Messages.buildRaw(BydHudRouteState.ROUTE_GUIDANCE_UPDATE) {
            u8(0x01, 1); u32(0x0a, 150); u16(0x0d, 7)
        })
        assertEquals(150, events.last()?.distanceMeters)
        BydNavigationOutputs.endNow(preserveTurnOverlay = true)
        BydNavigationOutputs.refreshTurnOverlay()
        assertEquals(150, events.last()?.distanceMeters)
        BydNavigationOutputs.endNow()
        assertNull(events.last())
    }

    @Test fun registeringC11StreamControlNeverCallsBackIntoAHalfBuiltController() {
        var calls = 0
        val control: (Boolean) -> Unit = { calls++ }
        BydNavigationOutputs.setClusterStreamControl(control)
        assertEquals(0, calls)
        assertNull(BydClusterMapPause.streamControl)
    }

    @Test @Config(sdk = [32], manifest = Config.NONE)
    fun c11DoesNotStartTheBydCallPopupWorkerEvenOnAndroid12L() {
        val status = BydCallPopupGuard.status
        val generation = ReflectionHelpers.getField<Long>(BydCallPopupGuard, "generation")
        BydCallPopupGuard.update(app, Any(), true) { true }
        assertNull(ReflectionHelpers.getField<Any?>(BydCallPopupGuard, "owner"))
        assertFalse(ReflectionHelpers.getField<Boolean>(BydCallPopupGuard, "running"))
        assertEquals(generation, ReflectionHelpers.getField<Long>(BydCallPopupGuard, "generation"))
        assertEquals(status, BydCallPopupGuard.status)
    }
}
