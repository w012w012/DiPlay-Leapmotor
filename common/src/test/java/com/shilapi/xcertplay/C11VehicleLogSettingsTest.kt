package com.shilapi.xcertplay

import android.content.Context
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.backup.SettingsBackupCodec
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class C11VehicleLogSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun defaultC11ConnectionDoesNotOpenAnAdbLogReaderOrAdvertiseVehicleBattery() {
        val fingerprint = android.os.Build.FINGERPRINT
        ShadowBuild.setFingerprint("qti/msmnile_gvmq/msmnile_gvmq:9/PQ1A/test:user/test-keys")
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        val controller = Mockito.mock(CarPlayController::class.java)
        // Drive the actual connection and poll entry points on this thread, without a live car loop.
        ReflectionHelpers.setField(LeapmotorGearMonitor, "pollThread", Thread())
        Mockito.mockConstruction(LocalAdb::class.java) { client, _ ->
            Mockito.`when`(client.connect(false)).thenReturn(LocalAdb.Access.UNREACHABLE)
        }.use { clients ->
            try {
                LeapmotorGearMonitor.attach(app, controller)
                ReflectionHelpers.setField(LeapmotorGearMonitor, "pollCount", 299L)
                ReflectionHelpers.callInstanceMethod<Unit>(LeapmotorGearMonitor, "pollLogcat")
                assertTrue("Default-off C11 must not create an ADB log reader", clients.constructed().isEmpty())
                assertEquals("disabled", LeapmotorGearMonitor.logcatState())
                ReflectionHelpers.callInstanceMethod<Unit>(LeapmotorGearMonitor, "askUpgrade")
                assertTrue("multimedia ownership heartbeat remains independent of log scanning",
                    shadowOf(app).broadcastIntents.any { it.action == "leapmotor.apptocan.update" })
                val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
                assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(host, "vehicleStatusWanted"))
            } finally {
                LeapmotorGearMonitor.detach(controller)
                ShadowBuild.setFingerprint(fingerprint)
            }
        }
    }

    @Test fun explicitOptInRestoresFastSlowAndCensusReadsAndBatteryAdvertisementOnNextConnection() {
        val fingerprint = android.os.Build.FINGERPRINT
        ShadowBuild.setFingerprint("qti/msmnile_gvmq/msmnile_gvmq:9/PQ1A/test:user/test-keys")
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        AirPlayPersistence.saveC11VehicleLogsEnabled(app, true)
        val commands = mutableListOf<String>()
        val controller = Mockito.mock(CarPlayController::class.java)
        ReflectionHelpers.setField(LeapmotorGearMonitor, "pollThread", Thread())
        Mockito.mockConstruction(LocalAdb::class.java) { client, _ ->
            Mockito.`when`(client.connect(false)).thenReturn(LocalAdb.Access.READY)
            Mockito.`when`(client.shell(Mockito.anyString())).thenAnswer { call ->
                commands += call.getArgument<String>(0); ""
            }
        }.use {
            try {
                LeapmotorGearMonitor.attach(app, controller)
                ReflectionHelpers.setField(LeapmotorGearMonitor, "pollCount", 299L)
                ReflectionHelpers.callInstanceMethod<Unit>(LeapmotorGearMonitor, "pollLogcat")
                assertEquals(listOf(C11LogcatProtocol.pollCommand(), C11LogcatProtocol.slowPollCommand(),
                    C11LogcatProtocol.censusCommand()), commands)
                assertEquals("enabled", LeapmotorGearMonitor.logcatState())
                val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
                assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(host, "vehicleStatusWanted"))
                // A saved preference does not silently change a running connection.
                AirPlayPersistence.saveC11VehicleLogsEnabled(app, false)
                assertEquals("enabled", LeapmotorGearMonitor.logcatState())
            } finally {
                LeapmotorGearMonitor.detach(controller)
                ShadowBuild.setFingerprint(fingerprint)
            }
        }
    }

    @Test fun controlIsAdvancedAndSavingOnlyMarksReconnectWhileBackupPreservesTheOptIn() {
        val app = app
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val card = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "c11VehicleLogControls",
            ReflectionHelpers.ClassParameter(LinearLayout::class.java, card))
        val control = descendants(card).filterIsInstance<Switch>().single()
        assertFalse(control.isChecked)
        assertNull(ReflectionHelpers.getField<Any?>(activity, "c11AdbState"))
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "c11AdbCheckInProgress"))
        assertFalse(descendants(card).filterIsInstance<android.widget.TextView>().any {
            it.text == activity.getString(R.string.check_adb_access)
        })
        assertEquals(listOf(SettingsCategory.ADVANCED), SettingsInformationArchitecture.sectionsByCategory
            .filterValues { SettingsSection.C11_VEHICLE_LOGS in it }.keys.toList())
        val session = Mockito.mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, Mockito.mock(AndroidMediaSink::class.java), 1920, 1080, Any(),
            CarPlaySessionDisplay(1920, 1080, Surface.ROTATION_0, false, false, 1920, 1080)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            ReflectionHelpers.setField(activity, "c11AdbState", com.shilapi.xcertplay.hud.BydAdbAccess.State.READY)
            control.isChecked = true
            assertTrue(AirPlayPersistence.loadC11VehicleLogsEnabled(app))
            assertTrue(PendingReconnect.isPending(session))
            assertEquals(0, stops)
            val backup = SettingsBackupCodec.export(app, "fix23-test")
            AirPlayPersistence.saveC11VehicleLogsEnabled(app, false)
            SettingsBackupCodec.import(app, backup)
            assertTrue(AirPlayPersistence.loadC11VehicleLogsEnabled(app))
        } finally { CarPlayBackgroundSession.clear(); PendingReconnect.clear() }
    }

    @Test fun staleBydVehiclePreferencesCannotActivateWheelSpeedOnC11ButStillWorkOnOtherCars() {
        val fingerprint = android.os.Build.FINGERPRINT
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        BydOutputSettings.setLegacyVehicleProbe(app, false)
        BydOutputSettings.setBatteryToIphone(app, true)
        BydOutputSettings.setWheelSpeedToIphone(app, true)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(host, "airPlayIdentity", AirPlayIdentity.generate())
        ReflectionHelpers.setField(host, "locationReportingEnabled", true)
        try {
            ShadowBuild.setFingerprint("qti/msmnile_gvmq/msmnile_gvmq:9/PQ1A/test:user/test-keys")
            val c11 = ReflectionHelpers.callInstanceMethod<CarPlayRuntimeConfig>(host, "createRuntimeConfig")
            assertTrue("GPS reporting stays enabled", c11.locationReportingEnabled)
            assertFalse("C11 cannot read BYD wheel-speed services", c11.identification.vehicleSpeedEnabled)
            assertFalse(c11.identification.vehicleStatusEnabled)
            ShadowBuild.setFingerprint("generic/test/device:9/test:user/test-keys")
            val other = ReflectionHelpers.callInstanceMethod<CarPlayRuntimeConfig>(host, "createRuntimeConfig")
            assertTrue(other.identification.vehicleSpeedEnabled)
            assertTrue(other.identification.vehicleStatusEnabled)
        } finally {
            BydOutputSettings.setBatteryToIphone(app, false)
            BydOutputSettings.setWheelSpeedToIphone(app, false)
            ShadowBuild.setFingerprint(fingerprint)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
