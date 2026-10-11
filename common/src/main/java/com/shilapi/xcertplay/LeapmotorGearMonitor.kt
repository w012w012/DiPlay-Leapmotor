package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * Leapmotor's gear position, and pausing CarPlay while reversing.
 *
 * The head unit publishes gear changes over CAN as `leapmotor.cantoapp.update` with the position in
 * the int extra `1010105`. Entering reverse must yield audio to the reversing camera and its parking
 * chimes, and the car to hand the screen back; leaving reverse resumes playback exactly where the
 * driver left off. Broadcasting pause is the only channel that reaches the iPhone — CarPlay has no
 * notion of gear.
 *
 * The same poll also refreshes the head unit's own record of DiPlay: pausing is only honoured while
 * the CarPlay is acknowledged as the active source, and no callback announces a lost registry entry.
 */
internal object LeapmotorGearMonitor {
    private const val TAG = "DiPlay-Gear"
    private const val GEAR_ACTION = "leapmotor.cantoapp.update"
    /** The CAN signal carrying the current gear. */
    private const val GEAR_EXTRA = "1010105"
    /** The reverse poll toward the head unit's CAN service. */
    private const val APP_TO_CAN_ACTION = "leapmotor.apptocan.update"
    private const val CAN_SERVER_PACKAGE = "com.leapmotor.carcanserver"
    private const val UPGRADE_ASK = "upgradeAsk"
    private const val UPGRADE_VALUE = "upgrade"
    private const val POLL_INTERVAL_MILLIS = 1000L
    private const val UNKNOWN_GEAR = -1
    private const val GEAR_REVERSE = 1

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null
    @Volatile private var controller: CarPlayController? = null
    @Volatile var onDiagnostic: ((String) -> Unit)? = null
    private var pollThread: Thread? = null
    private var rawGear: Int = UNKNOWN_GEAR
    private var pausedByReverse = false

    fun gearName(): String? = when (rawGear) {
        UNKNOWN_GEAR -> null
        GEAR_REVERSE -> "R"
        2 -> "N"
        3 -> "D"
        else -> "?"
    }

    @Synchronized
    fun attach(context: Context, controller: CarPlayController) {
        appContext = context.applicationContext
        this.controller = controller
        if (receiver != null) return
        val filter = IntentFilter(GEAR_ACTION)
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pending = goAsync()
                mainHandler.post {
                    try {
                        handle(intent)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(created, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.applicationContext.registerReceiver(created, filter)
            }
        }.isSuccess
        receiver = created.takeIf { registered }
        startPoll()
        report("gear monitor listening on $GEAR_ACTION registered=$registered")
    }

    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected != null && controller !== expected && controller != null) return
        val context = appContext
        val existing = receiver
        if (context != null && existing != null) {
            runCatching { context.unregisterReceiver(existing) }
        }
        receiver = null
        appContext = null
        controller = null
        pollThread?.interrupt()
        pollThread = null
        rawGear = UNKNOWN_GEAR
        pausedByReverse = false
        report("gear monitor detached")
    }

    private fun startPoll() {
        if (pollThread != null) return
        pollThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                askUpgrade()
                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }, "diplay-gear-poll").apply {
            isDaemon = true
            start()
        }
    }

    /** Keeps the head unit aware that DiPlay still owns multimedia. */
    private fun askUpgrade() {
        val context = appContext ?: return
        if (controller == null) return
        runCatching {
            context.sendBroadcast(
                Intent(APP_TO_CAN_ACTION)
                    .setPackage(CAN_SERVER_PACKAGE)
                    .putExtra(UPGRADE_ASK, UPGRADE_VALUE)
            )
        }
    }

    private fun handle(intent: Intent?) {
        if (intent?.action != GEAR_ACTION) return
        val next = runCatching { intent.getIntExtra(GEAR_EXTRA, UNKNOWN_GEAR) }
            .getOrDefault(UNKNOWN_GEAR)
        if (next == UNKNOWN_GEAR) return
        val previous = rawGear
        rawGear = next
        if (previous != next) {
            report("gear raw=$previous -> $next (${gearName() ?: "?"})")
            updateReverse(controller, next == GEAR_REVERSE)
        }
    }

    /** Pause once on entering reverse; resume exactly once on leaving it. */
    private fun updateReverse(controller: CarPlayController?, reverse: Boolean) {
        if (reverse) {
            if (pausedByReverse) return
            var sent = false
            if (controller != null) {
                sent = controller.sendMediaButton(CarPlayMediaButton.PAUSE)
            }
            pausedByReverse = true
            report("reverse: pause CarPlay sent=$sent")
        } else {
            if (!pausedByReverse) return
            pausedByReverse = false
            var sent = false
            if (controller != null) {
                sent = controller.sendMediaButton(CarPlayMediaButton.PLAY)
            }
            report("reverse ended: resume CarPlay sent=$sent")
        }
    }

    private fun report(message: String) {
        android.util.Log.i(TAG, message)
        runCatching { onDiagnostic?.invoke(message) }
    }
}
