package com.shilapi.xcertplay

import android.os.Build

/** The C11 firmware family already used to select its measured instrument layout. */
object HeadUnitPlatform {
    fun isLeapmotorC11(fingerprint: String = Build.FINGERPRINT): Boolean =
        fingerprint.contains("qti/msmnile_gvmq", ignoreCase = true)
}
