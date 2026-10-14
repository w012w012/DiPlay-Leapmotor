package com.shilapi.xcertplay

/**
 * The C11 head unit publishes its vehicle data only in its own system logs, never as the broadcasts
 * the S01/T03 builds listen for. The car's own tools read the same logs; DiPlay follows that route
 * over the local ADB shell (read-only; the driver approves the key once on the car screen).
 *
 * The line formats below come from a 2023 C11 capture (diagnostic report 20261014-122957), not from
 * the LeapTool table — that table was written for a different firmware, and several of its rules
 * (`mCurElectricity`, `eventId: NNNN value:`) never print on this car. The formats are debug output,
 * not an API, so every rule degrades to "no match" instead of failing when a firmware changes a line.
 */
internal object C11LogcatProtocol {
    /** Head-unit tags whose lines can carry vehicle events. */
    private val TAGS = listOf(
        "C11CarSomeIp",
        "C11CarXml",
        "C11SmartContrl",
        "C11AirConditioner",
        "MediaTlog",
        "AroundService",
        "SpeechSomeIP",
        "BleControlService",
    )

    /** Words that mark a line as a candidate steering-wheel or voice event. Bare `music` is not one:
     * the head unit's music widgets print progress refreshes every second and would flood the report. */
    private const val WHEEL_WORDS = "wheel|steer|siri|voice|keyEvent|mediaKey|WHEEL_360"

    private val GEAR = Regex("""onMessage\s+eventId:\s*1110(?!\d)\s+value:\s*(\d+)""")
    /** The bridge prints the gear over three lines; the value line is `Recive the gear is : N`. */
    private val BRIDGE_GEAR = Regex("""Recive the gear is\s*:\s*(\d+)""")
    /** The cluster and air-conditioner republish the same position as a named XML node. */
    private val NODE_GEAR = Regex("""node_name\s*:\s*gear\s*setTextContent:\s*(\d+)""")
    /** The seat-status XML: `<gear type="int">2</gear>`. */
    private val XML_GEAR = Regex("""<gear(?:\s+type="[^"]*")?>(\d+)</gear>""")
    private val WHEEL = Regex("""(?i)wheel|steer|siri|voice|keyEvent|mediaKey|WHEEL_360""")

    /**
     * The battery's state of charge: `<Battery><BatteryModel .../><figure type="int">36</figure>`.
     * `figureMin`/`figureMax` sit in the same block, so the attributes are matched exactly, and a
     * bare `<figure>` is not unique in the car's XML — the line has to name the battery block.
     */
    private val FIGURE_SOC = Regex("""<figure(?:\s+type="[^"]*")?>(\d+)</figure>""")
    private const val BATTERY_MARK = "<Battery"

    /** An older firmware spelling of the same reading. */
    private val SOC = Regex("""mCurElectricity\s*=\s*(\d+)""")
    /**
     * Wheel-speed candidates from the same capture: `SpeechSomeIP CanSubSomeIP eventId==21180/21181,
     * message==N`. Their unit is still unproven (the capture was taken while parked, values 0..20);
     * the range check keeps a misread from feeding CarPlay until a driving capture confirms them.
     */
    private val BRIDGE_SPEED = Regex("""eventId==(21180|21181),\s*message==(\d+)""")

    /**
     * The poll command. The window is bounded (`-t 2000`), the interesting lines are pulled out on
     * the car (grep), DiPlay's own prints are excluded, and the newest 600 lines win so a burst
     * cannot push fresh events out of the report.
     */
    fun pollCommand(): String {
        val filter = (TAGS + WHEEL_WORDS).joinToString("|")
        return "logcat -d -t 2000 -v epoch | grep -iE '$filter' | grep -v 'DiPlay-' | tail -n 600"
    }

    /**
     * The slowly changing readings. The car prints the battery's XML only when a value in it changes,
     * which can be minutes apart, so the one-second window above almost never holds it. This command
     * asks for a much wider window but only pulls the battery and wheel-speed lines out of it, and it
     * is run every few seconds instead of every second.
     */
    fun slowPollCommand(): String =
        "logcat -d -t 40000 -v epoch | grep -iE '<Battery|figure|eventId==2118' | tail -n 200"

    /** The gear value in a line: 1=R 2=N 3=D 4=P; null when the line carries no gear. */
    fun gearValue(line: String): Int? {
        GEAR.find(line)?.let { return it.groupValues[1].toIntOrNull() }
        BRIDGE_GEAR.find(line)?.let { return it.groupValues[1].toIntOrNull() }
        NODE_GEAR.find(line)?.let { return it.groupValues[1].toIntOrNull() }
        XML_GEAR.find(line)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    fun gearName(value: Int): String? = when (value) {
        1 -> "R"
        2 -> "N"
        3 -> "D"
        4 -> "P"
        else -> null
    }

    fun isWheelLine(line: String): Boolean = WHEEL.containsMatchIn(line)

    fun isC11Line(line: String): Boolean = TAGS.any { line.contains(it) }

    /** The battery's state of charge in a line, 0..100; null when the line carries none. */
    fun socValue(line: String): Int? {
        if (line.contains(BATTERY_MARK)) {
            FIGURE_SOC.find(line)?.let { return it.groupValues[1].toIntOrNull()?.takeIf { v -> v in 0..100 } }
        }
        SOC.find(line)?.let { return it.groupValues[1].toIntOrNull()?.takeIf { v -> v in 0..100 } }
        return null
    }

    /** The wheel speed in km/h in a line, 0..300; null when the line carries none. */
    fun speedValue(line: String): Int? =
        BRIDGE_SPEED.find(line)?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 0..300 }
}
