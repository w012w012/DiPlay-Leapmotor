package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot

/**
 * The C11's battery for the iPhone's vehicle status.
 *
 * BYD cars answer the transport's own services, which the C11 does not have, so the reading comes from
 * the head unit's log instead (see [LeapmotorGearMonitor]). The car publishes a state of charge and no
 * range, so the snapshot carries the percentage alone: the update then omits the range parameters
 * instead of claiming a range of zero.
 */
internal object LeapmotorVehicleStatus {
    fun batteryStatus(): VehicleStatusProvider = VehicleStatusProvider { leapmotorBatterySnapshot(LeapmotorGearMonitor.socPercent) }
}

/** The mapping on its own, so what reaches CarPlay can be checked without a head unit. */
internal fun leapmotorBatterySnapshot(percent: Int?): VehicleStatusSnapshot? {
    val soc = percent ?: return null
    return VehicleStatusSnapshot(
        rangeKm = null,
        // No range means no range warning either; the charge level is all this car reports.
        rangeWarning = false,
        batteryPercent = soc.coerceIn(0, 100).toDouble(),
        currentChargeWh = null,
        maxChargeWh = null,
        maxRangeKm = null,
    )
}
