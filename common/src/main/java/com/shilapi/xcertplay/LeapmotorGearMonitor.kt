package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * Leapmotor's gear position, and pausing CarPlay while reversing.
 *
 * Entering reverse must yield audio to the reversing camera and its parking chimes, and the car to
 * hand the screen back; leaving reverse resumes playback exactly where the driver left off.
 * Broadcasting pause is the only channel that reaches the iPhone — CarPlay has no notion of gear.
 *
 * Two sources feed the gear. The S01/T03 builds publish `leapmotor.cantoapp.update` with the
 * position in the int extra `1010105`, which stays registered as the broadcast route. The C11
 * never sends it: there the gear and the steering-wheel keys are read from the head unit's own
 * logs ([C11LogcatProtocol]) over the local ADB shell, the same route the car's own tools use.
 * The log link is read-only and needs the driver's one-time ADB approval; without it the poll
 * reports the access state once and keeps retrying quietly. Lines that look like steering-wheel
 * or voice events are copied into the diagnostic report verbatim (bounded), so the key formats
 * can be calibrated from a real capture.
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
    private const val ADB_RETRY_MILLIS = 30_000L
    /** Steering-wheel lines copied into the report per session; the format is the point. */
    private const val WHEEL_LINE_CAP = 400
    private const val SAMPLE_LINE_CAP = 600
    private const val SAMPLE_PER_POLL = 3
    private const val HEARTBEAT_POLLS = 15L
    /** The slowly changing readings get a much wider window, every this many polls. */
    private const val SLOW_POLL_EVERY = 15L
    /** The event-id census runs this many polls apart (about five minutes) while speed is unknown. */
    private const val CENSUS_POLLS = 300L
    /**
     * How long a reading stays current. The car prints the battery's XML only when a value in it
     * changes, so without this the settings card would show an old number as the present one.
     */
    private const val READING_FRESH_MILLIS = 600_000L
    /** How many messages are remembered so a re-read buffer cannot double-process a line. */
    private const val LINE_MEMORY = 600

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null
    @Volatile private var controller: CarPlayController? = null
    @Volatile var onDiagnostic: ((String) -> Unit)? = null
    private var pollThread: Thread? = null
    @Volatile private var logcatEnabled = true
    private var rawGear: Int = UNKNOWN_GEAR
    private var pausedByReverse = false
    private var adb: LocalAdb? = null
    private var adbRetryAtMillis = 0L
    private var lastAdbAccess: LocalAdb.Access? = null
    @Volatile private var socValue: Int? = null
    @Volatile private var speedKmhValue: Int? = null
    @Volatile private var socAtMillis = 0L
    @Volatile private var speedAtMillis = 0L
    private val seenMessages = LinkedHashSet<String>()
    private var wheelLinesLogged = 0
    private var sampleLinesLogged = 0
    private var pollCount = 0L

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
        logcatEnabled = !LeapmotorClusterLayout.isC11() || AirPlayPersistence.loadC11VehicleLogsEnabled(context)
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
        report("DiPlay-C11 vehicle log reader=${if (logcatEnabled) "enabled" else "disabled"} " +
            "apply=connection multimediaHeartbeat=enabled")
    }

    fun logcatState(): String = if (controller == null) "detached" else if (logcatEnabled) "enabled" else "disabled"

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
        adb?.close()
        adb = null
        adbRetryAtMillis = 0L
        lastAdbAccess = null
        socValue = null
        speedKmhValue = null
        socAtMillis = 0L
        speedAtMillis = 0L
        synchronized(lock) { seenMessages.clear() }
        wheelLinesLogged = 0
        sampleLinesLogged = 0
        pollCount = 0
        report("gear monitor detached")
    }

    private fun startPoll() {
        if (pollThread != null) return
        pollThread = Thread({
            while (!Thread.currentThread().isInterrupted) {
                askUpgrade()
                pollLogcat()
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
        applyGear(next, "broadcast")
    }

    /** Applies a gear reading from any source; true when the value actually changed. */
    @Synchronized
    private fun applyGear(next: Int, source: String): Boolean {
        if (next == UNKNOWN_GEAR) return false
        val previous = rawGear
        if (previous == next) return false
        rawGear = next
        report("gear raw=$previous -> $next (${gearName() ?: "?"}) source=$source")
        updateReverse(controller, next == GEAR_REVERSE)
        return true
    }

    /**
     * Reads the C11's own logs for gear and steering-wheel events. The newest gear line in the
     * window is the current position; wheel lines are copied into the report so a real capture
     * can calibrate the key formats, and the remaining C11 lines are sampled so an unknown
     * format still shows up.
     */
    private fun pollLogcat() {
        // Keep multimedia ownership broadcasts and passive receivers, without opening an ADB shell
        // or running any fast/slow/census logcat commands when the driver does not need vehicle data.
        if (!logcatEnabled) return
        val context = appContext ?: return
        if (controller == null) return
        val output = runAdb(context, C11LogcatProtocol.pollCommand()) ?: return
        val lines = output.lineSequence().filter { it.isNotBlank() }.toList()
        var lastGear: Int? = null
        var lastSoc: Int? = null
        var lastSpeed: Int? = null
        var wheel = 0
        var samples = 0
        for (line in lines) {
            val gear = C11LogcatProtocol.gearValue(line)
            if (gear != null) {
                lastGear = gear
                continue
            }
            C11LogcatProtocol.socValue(line)?.let { lastSoc = it }
            C11LogcatProtocol.speedValue(line)?.let { lastSpeed = it }
            if (C11LogcatProtocol.isWheelLine(line)) {
                if (wheelLinesLogged < WHEEL_LINE_CAP && rememberMessage(line)) {
                    wheelLinesLogged++
                    wheel++
                    report("DiPlay-C11 wheel-line: $line")
                }
                continue
            }
            if (C11LogcatProtocol.isC11Line(line) && samples < SAMPLE_PER_POLL &&
                sampleLinesLogged < SAMPLE_LINE_CAP && rememberMessage(line)
            ) {
                samples++
                sampleLinesLogged++
                report("DiPlay-C11 sample: $line")
            }
        }
        val matched = if (lastGear != null && applyGear(lastGear, "logcat")) 1 else 0
        lastSoc?.let { publishSoc(it) }
        lastSpeed?.let { publishSpeed(it) }
        pollCount++
        // Battery and wheel speed change slowly and the car prints them rarely, so the one-second
        // window above seldom holds them; the same link is read wider every few seconds instead.
        if (pollCount % SLOW_POLL_EVERY == 0L) pollSlowLogcat(context)
        // Until a speed reading is found, ask the car which event ids it prints: the ids this protocol
        // looks for came from another firmware, and a report taken after driving still had no match.
        if (speedKmh == null && pollCount % CENSUS_POLLS == 0L) censusEventIds(context)
        if (matched > 0 || wheel > 0 || pollCount % HEARTBEAT_POLLS == 0L) {
            report(
                "DiPlay-C11 poll rows=${lines.size} matched=$matched" +
                    " gear=${gearName() ?: "?"} wheel=$wheel" +
                    (lastSoc?.let { " soc=$it%" } ?: "") +
                    (lastSpeed?.let { " speed=${it}km/h" } ?: ""),
            )
        }
    }

    /**
     * Reads the same link over a much wider window for the readings that change slowly. Gear stays on
     * the fast poll alone: it has to be caught within a second of the lever moving.
     */
    private fun pollSlowLogcat(context: Context) {
        val output = runAdb(context, C11LogcatProtocol.slowPollCommand()) ?: return
        var soc: Int? = null
        var speed: Int? = null
        var socLine: String? = null
        for (line in output.lineSequence()) {
            if (line.isBlank()) continue
            C11LogcatProtocol.socValue(line)?.let { soc = it; socLine = line }
            C11LogcatProtocol.speedValue(line)?.let { speed = it }
        }
        soc?.let { publishSoc(it, socLine) }
        speed?.let { publishSpeed(it) }
    }

    /**
     * Lists the event ids the car prints, so the wheel-speed id can be identified from a real driving
     * report instead of assumed from another firmware's capture. Runs only while no speed is known.
     */
    private fun censusEventIds(context: Context) {
        val output = runAdb(context, C11LogcatProtocol.censusCommand()) ?: return
        val rows = C11LogcatProtocol.eventIdCensus(output)
        if (rows.isEmpty()) {
            report("DiPlay-C11 event ids: none in the window")
            return
        }
        report("DiPlay-C11 event ids: ${rows.size} listed")
        rows.forEach { report("DiPlay-C11 event $it") }
    }

    /** Publishes the newest SOC reading for the settings page and the CarPlay battery report. */
    @Synchronized
    private fun publishSoc(percent: Int, evidence: String? = null) {
        val changed = socValue != percent
        socValue = percent
        socAtMillis = SystemClock.elapsedRealtime()
        // The raw line travels with the reading so the next report shows the exact format the car used.
        if (changed) report("DiPlay-C11 soc=$percent%" + (evidence?.let { " line=${it.take(200)}" } ?: ""))
    }

    /** Publishes the newest wheel-speed reading for the settings page. */
    @Synchronized
    private fun publishSpeed(kmh: Int) {
        val changed = speedKmhValue != kmh
        speedKmhValue = kmh
        speedAtMillis = SystemClock.elapsedRealtime()
        if (changed) report("DiPlay-C11 speed=$kmh km/h")
    }

    /** The latest logcat readings; the settings page on the C11 shows these instead of BYD's probe. */
    val socPercent: Int? get() = fresh(socValue, socAtMillis)
    val speedKmh: Int? get() = fresh(speedKmhValue, speedAtMillis)

    fun batteryReading(): Int? = fresh(socValue, socAtMillis)

    /** A reading the car has not repeated for a while is not shown as if it were the current one. */
    private fun fresh(value: Int?, atMillis: Long): Int? = value?.takeIf {
        atMillis > 0L && SystemClock.elapsedRealtime() - atMillis < READING_FRESH_MILLIS
    }

    /** Runs one bounded command over the head unit's own adbd; never asks for approval. */
    private fun runAdb(context: Context, command: String): String? {
        val now = SystemClock.elapsedRealtime()
        if (now < adbRetryAtMillis) return null
        val client = adb ?: LocalAdb(AdbKeys.load(context)).also { adb = it }
        val access = client.connect(mayAsk = false)
        if (access != lastAdbAccess) {
            lastAdbAccess = access
            report("DiPlay-C11 adb=$access")
        }
        if (access != LocalAdb.Access.READY) {
            adbRetryAtMillis = now + ADB_RETRY_MILLIS
            return null
        }
        adbRetryAtMillis = 0L
        return runCatching { client.shell(command) }.getOrNull()
    }

    /** True the first time a line's message is seen; repeated prints of the same text are dropped. */
    private fun rememberMessage(line: String): Boolean = synchronized(lock) {
        val key = line.substringAfter(": ", line)
        if (!seenMessages.add(key)) return false
        if (seenMessages.size > LINE_MEMORY) {
            val oldest = seenMessages.iterator()
            oldest.next()
            oldest.remove()
        }
        true
    }

    /** Pause once on entering reverse; resume exactly once on leaving it. */
    private fun updateReverse(controller: CarPlayController?, reverse: Boolean) {
        if (reverse) {
            if (pausedByReverse) return
            if (controller == null) {
                // Without a session there is nothing to pause, and setting the flag here would make
                // the next gear change send a stray resume.
                report("reverse: no CarPlay session; nothing to pause")
                return
            }
            val sent = controller.sendMediaButton(CarPlayMediaButton.PAUSE)
            pausedByReverse = true
            report("reverse: pause CarPlay sent=$sent")
        } else {
            if (!pausedByReverse) return
            pausedByReverse = false
            val sent = controller?.sendMediaButton(CarPlayMediaButton.PLAY) ?: false
            report("reverse ended: resume CarPlay sent=$sent")
        }
    }

    private fun report(message: String) {
        android.util.Log.i(TAG, message)
        runCatching { onDiagnostic?.invoke(message) }
    }
}
