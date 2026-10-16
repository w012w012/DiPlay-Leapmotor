package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.HeadUnitPlatform
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Nonblocking boundary between phone control messages and vendor services. */
object BydNavigationOutputs {
    /** Recover a journaled interrupted output when the app opens, even before a phone reconnects. */
    fun onAppOpened(context: Context, preloadVehicleBattery: Boolean = true) {
        BydBluetoothSuspend.onAppOpened(context)
        BydOemClusterNavi.restoreIfNeeded(context)
        BydDiLink3ClusterOutput.restoreIfNeeded(context)
        com.shilapi.xcertplay.network.WifiScanPause.restoreIfNeeded(context)
        // Recovery above remains available for an interrupted older installation.
        if (HeadUnitPlatform.isLeapmotorC11()) return
        if (BydStandaloneHudOutput.available(context)) start(context)
        // Read the battery early, so a reading is ready when CarPlay identifies (see batteryStatus).
        if (preloadVehicleBattery && BydOutputSettings.batteryToIphoneActive(context)) BydBatteryStatus.start(context)
        // DiLink 3 creates its cluster map display only once the cluster has projected.
        if (BydOutputSettings.enabled(context)) BydClusterBridge.prepareProjectionDisplay(context.applicationContext)
    }
    fun setDiagnosticHold(hold: Boolean) { BydStandaloneHudOutput.syntheticHold = hold }
    @Volatile private var useStandalone = false
    @Volatile private var overlayListener: ((ClusterTurnGuidance?) -> Unit)? = null
    private val overlayLock = Any()
    private var publishedOverlay: ClusterTurnGuidance? = null
    private val overlayRoute = BydHudRouteState(
        staleRouteNs = 120_000_000_000L,
        emptyListHideNs = 8_000_000_000L,
        keepAcrossNoRoute = true,
    )
    private val standalone = NavigationOutputWorker("diplay-standalone-output", BydStandaloneNavigationBridge::clear)
    private val cluster = NavigationOutputWorker("diplay-cluster-output", BydClusterBridge::clear)
    private val fceCluster = NavigationOutputWorker("diplay-fce-cluster-output", FceClusterBridge::clear)

    /** The host reports whether its CarPlay map window is on the cluster (see [BydClusterMapPause]). */
    fun setClusterMapShown(shown: Boolean) {
        if (HeadUnitPlatform.isLeapmotorC11()) return
        BydClusterMapPause.clusterMapShown = shown
        BydClusterBridge.setMapShown(shown)
    }

    /** The running CarPlay session: told every second whether the cluster currently shows the map. */
    fun setClusterStreamControl(control: (Boolean) -> Unit) {
        // The controller registers this during construction, before its UI lock exists.
        // C11 keeps the controller's default visible state without a BYD mode ticker.
        if (!HeadUnitPlatform.isLeapmotorC11()) BydClusterMapPause.streamControl = control
    }

    /** Latest ADB wheel-menu navi mode, or null when it cannot be read. */
    fun clusterNaviMode(): BydClusterNaviMode? =
        if (HeadUnitPlatform.isLeapmotorC11()) null else BydClusterMapPause.lastNaviMode

    /** Called whenever the ADB navi mode changes. Pass null to stop following. */
    fun setClusterNaviModeListener(listener: ((BydClusterNaviMode?) -> Unit)?) {
        if (HeadUnitPlatform.isLeapmotorC11()) {
            listener?.invoke(null)
            return
        }
        BydClusterMapPause.onNaviMode = listener
        listener?.invoke(BydClusterMapPause.lastNaviMode)
    }

    fun clearClusterStreamControl(control: (Boolean) -> Unit) {
        if (HeadUnitPlatform.isLeapmotorC11()) return
        if (BydClusterMapPause.streamControl == control) BydClusterMapPause.streamControl = null
    }

    /**
     * The car's battery for the iPhone's vehicle status; starts reading it over adb. The electric
     * vehicle is declared only once a reading is there (see withVehicleStatusFrom).
     */
    fun batteryStatus(context: Context): com.shilapi.xcertplay.transport.VehicleStatusProvider =
        BydBatteryStatus.also { it.start(context) }

    /** The car's wheel speed and gear for the iPhone's dead reckoning; read over adb while asked for. */
    fun wheelSpeed(context: Context): com.shilapi.xcertplay.transport.VehicleSpeedSource =
        BydWheelSpeedSource.attach(context)
    /** Whether the car is in P (read over adb), or null when it cannot tell. Blocking. */
    fun parked(context: Context): Boolean? = BydParkedState.parked(context.applicationContext)

    fun start(context: Context) {
        val app = context.applicationContext
        // Shared iPhone call state and Telecom routing are still needed on the C11.
        BydCarPlayCall.attach(app)
        // FCE is a separate adapter. Keep its capability check and explicit opt-in available.
        if (FceClusterBridge.available()) {
            fceCluster.start { FceClusterBridge.initialize(app) }
        }
        if (HeadUnitPlatform.isLeapmotorC11()) return
        useStandalone = BydStandaloneHudOutput.available(app)
        if (useStandalone) standalone.start { BydStandaloneNavigationBridge.initialize(app) }
        else {
            cluster.start { BydClusterBridge.initialize(app) }
        }
        BydClusterMapPause.initialize(app)
        BydClusterSong.attach(app)
    }

    internal fun onFrame(frame: Iap2Frame) {
        if (frame.messageId == ClusterSongState.NOW_PLAYING_UPDATE) {
            if (!HeadUnitPlatform.isLeapmotorC11()) BydClusterSong.onFrame(frame)
            return
        }
        if (frame.messageId == CarPlayCallState.CALL_STATE_UPDATE) {
            BydCarPlayCall.onFrame(frame)
            return
        }
        if (frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) return
        val owned = frame // Iap2Frame is immutable and defensively copies its payload.
        updateOverlay(owned)
        fceCluster.submit { FceClusterBridge.onFrame(owned) }
        if (HeadUnitPlatform.isLeapmotorC11()) return
        if (useStandalone) standalone.submit { BydStandaloneNavigationBridge.onFrame(owned) }
        else {
            cluster.submit { BydClusterBridge.onFrame(owned) }
        }
    }

    /** Live next-turn state for the dashboard overlay. Called from the iAP2 thread. */
    fun setTurnOverlayListener(listener: ((ClusterTurnGuidance?) -> Unit)?) {
        overlayListener = listener
        val next = currentOverlay()
        synchronized(overlayLock) { publishedOverlay = next }
        listener?.invoke(next)
    }

    private fun updateOverlay(frame: Iap2Frame) {
        val change = synchronized(overlayLock) { overlayRoute.accept(frame.messageId, frame.payload) }
        if (change != BydHudRouteChange.NONE) refreshTurnOverlay()
    }

    /** Called every second while the presentation owner lives, even without incoming frames. */
    fun refreshTurnOverlay() {
        val next = synchronized(overlayLock) {
            val current = currentOverlay()
            if (current == publishedOverlay) return
            publishedOverlay = current
            current
        }
        overlayListener?.invoke(next)
    }

    private fun currentOverlay(): ClusterTurnGuidance? = synchronized(overlayLock) {
        overlayRoute.currentApple()?.let { apple ->
            ClusterTurnGuidance.from(BydClusterFrame.from(apple)).copy(
                arrivalEpochSeconds = apple.arrivalEpochSeconds,
                remainingSeconds = apple.remainingSeconds,
                remainingMeters = apple.remainingMeters,
            )
        }
    }

    /** The dashboard song setting changed; applies at once. */
    fun clusterSongChanged(enabled: Boolean) {
        if (!HeadUnitPlatform.isLeapmotorC11()) BydClusterSong.settingChanged(enabled)
    }

    /** The CarPlay call setting changed; applies at once. */
    fun carPlayCallsChanged(enabled: Boolean) = BydCarPlayCall.settingChanged(enabled)

    /** A CarPlay session became active; ready the call card so calls show without the watcher's start delay. */
    fun carPlaySessionStarted() = BydCarPlayCall.sessionStarted()

    /** The iPhone's current call, for the steering wheel's call keys. */
    fun carPlayCall(): CarPlayCallCard? = BydCarPlayCall.current()

    /** The dashboard song's "only when it changes" setting changed; applies at once. */
    fun clusterSongOnChangeChanged() {
        if (!HeadUnitPlatform.isLeapmotorC11()) BydClusterSong.onChangeSettingChanged()
    }

    /** A short note where the song shows on the dashboard; needs the same ADB access as the song. */
    fun dashboardNote(text: String, source: Int? = null) {
        if (!HeadUnitPlatform.isLeapmotorC11()) BydClusterSong.note(text, source)
    }

    /** Best effort while alive; Android does not guarantee callbacks before force-stop. */
    fun endNow(preserveTurnOverlay: Boolean = false) {
        if (!HeadUnitPlatform.isLeapmotorC11()) {
            standalone.clear(); cluster.clear(); BydClusterSong.end()
        }
        fceCluster.clearIfStarted()
        BydCarPlayCall.end()
        // Only a wireless session replacement retains the card. Explicit controller close
        // and wired disconnect still clear it immediately.
        if (!preserveTurnOverlay) {
            synchronized(overlayLock) { overlayRoute.clear() }
            refreshTurnOverlay()
        } else {
            synchronized(overlayLock) { overlayRoute.holdAcrossDrop() }
        }
    }
}
