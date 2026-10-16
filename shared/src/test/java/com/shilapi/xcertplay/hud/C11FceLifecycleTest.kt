package com.shilapi.xcertplay.hud

import android.os.Binder
import android.os.IInterface
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowServiceManager
import org.robolectric.util.ReflectionHelpers

/** Exercise the real dispatcher, including opening the FCE switch after a session has started. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class C11FceLifecycleTest {
    @Test fun independentlyAvailableFceOutputCanBeEnabledLiveAndClearsOnDisconnect() {
        val app = RuntimeEnvironment.getApplication()
        val fingerprint = android.os.Build.FINGERPRINT
        ShadowBuild.setFingerprint("qti/msmnile_gvmq/msmnile_gvmq:9/PQ1A/test:user/test-keys")
        val service = "fce_misc_service_hal_property"
        ShadowServiceManager.addBinderService(service, IInterface::class.java, object : IInterface {
            override fun asBinder() = Binder()
        })
        ShadowServiceManager.setServiceAvailability(service, true)
        val writes = CopyOnWriteArrayList<Pair<Int, Int>>()
        FceClusterBridge.resetForTest({ id, value -> writes += id to value; true }, app)
        val worker = ReflectionHelpers.getField<Any>(BydNavigationOutputs, "fceCluster")
        val executor = ReflectionHelpers.getField<ThreadPoolExecutor>(worker, "executor")
        fun drain() { executor.submit {}.get(5, TimeUnit.SECONDS) }
        fun route(distance: Int) = BydNavigationOutputs.onFrame(
            Iap2Messages.buildRaw(BydHudRouteState.ROUTE_GUIDANCE_UPDATE) {
                u8(0x01, 1); u32(0x0a, distance); u16(0x0d, 2)
            })
        try {
            assertTrue(FceClusterBridge.available())
            BydOutputSettings.setFceCluster(app, false)
            BydNavigationOutputs.start(app)
            drain()
            BydNavigationOutputs.onFrame(Iap2Messages.buildRaw(BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) {
                u16(0x01, 2); u8(0x03, 1)
            })
            route(200)
            drain()
            assertTrue(writes.isEmpty())
            // No reconnect or second start() call: the existing worker accepts the next phone update.
            BydOutputSettings.setFceCluster(app, true)
            route(178)
            drain()
            assertEquals(listOf(FceClusterProperties.NAVI_STATUS to 2, FceClusterProperties.ICON_SET_2 to 0,
                FceClusterProperties.ICON to 19, FceClusterProperties.DISTANCE to 180), writes.toList())
            writes.clear()
            BydNavigationOutputs.endNow()
            drain()
            assertEquals(listOf(FceClusterProperties.ICON to 0, FceClusterProperties.ICON_SET_2 to 0,
                FceClusterProperties.DISTANCE to 0, FceClusterProperties.NAVI_STATUS to 1,
                FceClusterProperties.NAVI_STATUS to 0), writes.toList())
        } finally {
            BydNavigationOutputs.endNow()
            if (executor.poolSize > 0) drain()
            FceClusterBridge.resetForTest({ _, _ -> true }, null)
            ShadowServiceManager.setServiceAvailability(service, false)
            ShadowBuild.setFingerprint(fingerprint)
        }
    }
}
