package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.json.JSONObject

/**
 * The media broadcasts Leapmotor's head unit sends instead of key events.
 *
 * The wheel does not reach apps as Android key events on this firmware: the instrument cluster (ICU)
 * reads the CAN keys and republishes them, so DiPlay can only observe them as broadcasts. Which
 * action a given firmware actually uses was not knowable, so the channels list every spelling seen
 * across reports; duplicates are collapsed by the dispatch de-duplication window below.
 */
internal object LeapmotorMediaProtocol {
    /** The published media controls, including aliases reported from different firmware builds. */
    private val CHANNEL_A = listOf(
        "car.meter.music.BROADCAST",
        "car.hmi.music.BROADCAST",
        "com.leapmotor.command.music",
        "com.leapmotor.command.multimedia",
        "com.leapmotor.command.media",
        "com.leapmotor.command.playpause",
        "com.leapmotor.command.next",
        "com.leapmotor.command.previous",
        "com.leapmotor.command.key",
        "com.leapmotor.command.mediaKey",
        "com.leapmotor.multimedia.mediaKey",
        "com.leapmotor.multimedia.MediaKey",
        "com.leapmotor.media.keyevent",
        "com.leapmotor.media.KEY_EVENT",
        "com.leapmotor.audio.key",
        "com.leapmotor.audio.KEY_EVENT",
        "com.leapmotor.audiocenter.mediaKey",
        "com.leapmotor.vehicle.keyevent",
        "com.leapmotor.vehicle.mediaKey",
        "car.hmi.media.KEY_EVENT",
        "car.hmi.keyevent",
        "car.media.keyevent",
        "car.media.next",
        "car.media.previous",
        "com.leapmotor.ICUMainCtrl",
        "com.leapmotor.ICUCtrl",
        "com.leapmotor.customkey.media.next",
        "com.leapmotor.customkey.media.prev",
        "com.leapmotor.customkey.media.pauseplay",
        "com.leapmotor.customkey.music.next",
        "com.leapmotor.customkey.music.prev",
    )

    /**
     * Head-unit commands that share the channels but belong to the car's own radio and assistant.
     * They must be recognised so they can be dropped: forwarding them would hijack the radio.
     */
    private val STOCK_HOUSEKEEPING = listOf(
        "com.leapmotor.command.netRadio",
        "com.leapmotor.command.fmradio",
        "com.leapmotor.music.netradio.search",
        "car.meter.query.BROADCAST",
        "com.leapmotor.pohone.toxmly.calling",
    )

    /** Channels carrying the key as an int extra rather than as text. */
    val EXTRA_CHANNELS = listOf(
        "com.leapmotor.customkey.music.pauseplay",
        "com.leapmotor.ICU2MMICtrl",
    )

    /** The C11's own cluster/voice control channels. */
    val C11_CHANNEL = listOf(
        "com.leapmotor.action.METER.CTRL",
        "com.leapmotor.action.VOICE.CTRL",
    )

    /** Everything registered at runtime. */
    val ACTIONS: List<String> = CHANNEL_A + STOCK_HOUSEKEEPING + C11_CHANNEL

    /** Registered actions plus the int-extra channels, for diagnostics. */
    val ALL_ACTIONS: List<String> = ACTIONS + EXTRA_CHANNELS

    private val MEDIA_KEY_EXTRA = arrayOf("ICU_MediaKey", "mediaKey")
    private val MEDIA_SWITCH_EXTRA = arrayOf("ICU_MediaSwitch", "mediaSwitch")

    private const val RECEIVER_EXTRA = "receiver"
    private const val ACTION_EXTRA = "action"
    private const val KEY_EVENT_TYPE_EXTRA = "keyEventType"
    private const val VALUE_EXTRA = "value"

    fun isExtraChannel(action: String?): Boolean = action != null && EXTRA_CHANNELS.contains(action)
    fun isC11Channel(action: String?): Boolean = action != null && C11_CHANNEL.contains(action)

    /**
     * `receiver` is a length-prefixed UTF-8 JSON body rather than a plain string: the first two bytes
     * encode the length as `first * 100 + second`, which is how the ICU frames it.
     */
    fun actionFromPayload(payload: ByteArray?): String? {
        if (payload == null || payload.size <= 2) return null
        val declared = (payload[0].toInt() and 0xff) * 100 + (payload[1].toInt() and 0xff)
        val end = minOf(payload.size, declared + 2)
        if (end <= 2) return null
        return actionFromJson(String(payload, 2, end - 2, Charsets.UTF_8))
    }

    fun actionFromJson(text: String?): String? {
        val body = (text ?: "").trim()
        if (body.isEmpty() || !body.startsWith("{")) return null
        return runCatching {
            val root = JSONObject(body)
            // Ignore our own echoes so a write can never loop back as a keypress.
            if (root.optString("src") == "diplay") return@runCatching null
            val type = root.optString("type")
            if (type.isNotEmpty() && !type.equals("music", ignoreCase = true)) return@runCatching null
            root.optJSONObject("data")?.optString("action")?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** The C11 cluster forwards wheel and rear-seat keys with the same structure. */
    fun c11ChannelAction(intent: Intent?): String? {
        if (intent == null) return null
        val type = runCatching { intent.getStringExtra(KEY_EVENT_TYPE_EXTRA) }.getOrNull() ?: return null
        if (type != "steeringWheel" && type != "backRow") return null
        return when (runCatching { intent.getStringExtra(VALUE_EXTRA) }.getOrNull()) {
            "1" -> "preOne"
            "2" -> "nextOne"
            "3" -> "playpause"
            else -> null
        }
    }

    /** Reads the first present key of [keys], or 0 when the broadcast carries none of them. */
    private fun intExtra(intent: Intent, keys: Array<String>): Int {
        val missing = Int.MIN_VALUE
        for (key in keys) {
            val value = runCatching { intent.getIntExtra(key, missing) }.getOrDefault(missing)
            if (value != missing) return value
        }
        return 0
    }

    fun extraChannelAction(intent: Intent?): String? {
        if (intent == null) return null
        val switch = intExtra(intent, MEDIA_SWITCH_EXTRA)
        val key = intExtra(intent, MEDIA_KEY_EXTRA)
        if (key == 1) return "playpause"
        return when (switch) {
            2 -> "nextOne"
            1 -> "preOne"
            else -> null
        }
    }

    /** All extras as sorted `key=value`, for "unmatched" diagnostics. */
    fun extraChannelDetail(intent: Intent?): String {
        val extras = intent?.extras ?: return "no extras"
        return extras.keySet().sorted().joinToString(" ") { key ->
            val value = runCatching { extras.get(key) }.getOrNull()
            when {
                value == null -> "$key=null"
                value is ByteArray -> "$key=byte[${value.size}]"
                else -> "$key=$value"
            }
        }
    }

    internal fun receiverExtra(intent: Intent): ByteArray? =
        runCatching { intent.getByteArrayExtra(RECEIVER_EXTRA) }.getOrNull()

    internal fun actionExtra(intent: Intent): String? =
        runCatching { intent.getStringExtra(ACTION_EXTRA) }.getOrNull()
}

/**
 * Turns the head unit's media broadcasts into CarPlay presses.
 *
 * Mirrors [com.shilapi.xcertplay.CarPlayMediaKeys], which does the same job for real media keys. The
 * two must agree on one thing: a broadcast key is only acted on while a CarPlay session exists, and
 * while the wheel-key learning flow is capturing, the raw id goes to the learner instead.
 */
internal object LeapmotorMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"
    /** The same physical press often arrives on several channels; one winning press per window. */
    private const val DUPLICATE_WINDOW_MILLIS = 250L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null
    @Volatile private var controller: CarPlayController? = null
    @Volatile var onDiagnostic: ((String) -> Unit)? = null
    private var lastKeyUptimeMillis = 0L
    private var lastKeyMapped = 0

    @Synchronized
    fun attach(context: Context, controller: CarPlayController) {
        if (this.controller === controller && receiver != null) return
        appContext = context.applicationContext
        this.controller = controller
        if (receiver != null) return
        val filter = IntentFilter().apply {
            LeapmotorMediaProtocol.ACTIONS.forEach(::addAction)
        }
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
            ContextCompat.registerReceiver(
                context.applicationContext, created, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
        receiver = created.takeIf { registered }
        report("media keys listening on ${LeapmotorMediaProtocol.ACTIONS.size} actions registered=$registered")
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
        report("media keys detached")
    }

    /** True when the same button was already acted on inside the de-duplication window. */
    private fun claimDispatchWindow(button: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        if (button == lastKeyMapped && now - lastKeyUptimeMillis < DUPLICATE_WINDOW_MILLIS) return false
        lastKeyMapped = button
        lastKeyUptimeMillis = now
        return true
    }

    private fun handle(intent: Intent?) {
        val action = intent?.action ?: return
        when {
            LeapmotorMediaProtocol.isExtraChannel(action) -> {
                val mapped = LeapmotorMediaProtocol.extraChannelAction(intent)
                if (mapped != null) dispatch(mapped, action)
                else report("media key extra-channel unmatched action=$action extras=[${LeapmotorMediaProtocol.extraChannelDetail(intent)}]")
            }
            LeapmotorMediaProtocol.isC11Channel(action) -> {
                val mapped = LeapmotorMediaProtocol.c11ChannelAction(intent)
                if (mapped != null) dispatch(mapped, action)
                else report("media key c11-channel unmatched action=$action extras=[${LeapmotorMediaProtocol.extraChannelDetail(intent)}]")
            }
            else -> {
                val fromPayload = LeapmotorMediaProtocol.actionFromPayload(
                    LeapmotorMediaProtocol.receiverExtra(intent)
                )
                val mapped = fromPayload
                    ?: LeapmotorMediaProtocol.actionExtra(intent)?.takeIf { it.isNotBlank() }
                    ?: action
                dispatch(mapped, action)
            }
        }
    }

    fun dispatch(action: String?, source: String): Boolean {
        val raw = action ?: return false
        val controller = controller
        val trimmed = raw.trim()

        // Learning wins: the user is teaching DiPlay what this button means.
        if (LearnedWheelKeys.isCapturing() &&
            LearnedWheelKeys.onKey("CAR:$trimmed")
        ) return true
        // A learnt binding already owns this id, so the generic mapping must not double-fire it.
        if (LearnedWheelKeys.broadcastBindingConsumes(source)) {
            report("media key ignored: bus bound by broadcast binding source=$source")
            return true
        }

        val learned = LearnedWheelKeys.learnedCarButton(trimmed)
        val button = learned ?: CarPlayMediaButton.forLeapmotorAction(trimmed)

        if (learned == null && !CarPlayMediaButton.isWheelAction(trimmed)) {
            report("media key ignored: car-internal command source=$source action=$trimmed")
            return false
        }
        if (button == null) {
            report("media key unmapped source=$source action=$trimmed")
            return false
        }
        if (controller == null) {
            report("media key ignored (no CarPlay session) action=$trimmed")
            return false
        }
        if (!claimDispatchWindow(button)) {
            report("media key duplicate suppressed action=$trimmed")
            return false
        }
        // Local video playback handles keys first when it owns the screen.
        if (CarPlayVideo.onMediaKey(button)) {
            report("media key source=$source action=$trimmed -> video player $button")
            return true
        }
        val sent = controller.sendMediaButton(button)
        report("media key source=$source action=$trimmed -> CarPlay $button sent=$sent")
        return sent
    }

    private fun report(message: String) {
        android.util.Log.i(TAG, message)
        runCatching { onDiagnostic?.invoke(message) }
    }
}
