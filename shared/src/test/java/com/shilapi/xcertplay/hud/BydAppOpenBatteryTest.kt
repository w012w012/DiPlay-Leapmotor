package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydAppOpenBatteryTest {
    @Test fun appWithoutBydBatterySupportDoesNotWarmUpAStaleEnabledPreference() = checkWarmup(false)

    @Test fun existingBydCallersKeepTheirDefaultBatteryWarmup() = checkWarmup(true)

    private fun checkWarmup(enabled: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        BydOutputSettings.setLegacyVehicleProbe(app, false)
        BydOutputSettings.setBatteryToIphone(app, true)
        val previousReader = BydBatteryStatus.readBattery
        BydBatteryStatus.readBattery = { null }
        val before = ReflectionHelpers.getField<Long>(BydBatteryStatus, "askedMillis")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        try {
            if (enabled) BydNavigationOutputs.onAppOpened(app)
            else BydNavigationOutputs.onAppOpened(app, preloadVehicleBattery = false)
            val after = ReflectionHelpers.getField<Long>(BydBatteryStatus, "askedMillis")
            if (enabled) assertTrue(after > before)
            else assertEquals("no battery reader is started or refreshed", before, after)
        } finally {
            BydBatteryStatus.readBattery = previousReader
            BydOutputSettings.setBatteryToIphone(app, false)
        }
    }
}
