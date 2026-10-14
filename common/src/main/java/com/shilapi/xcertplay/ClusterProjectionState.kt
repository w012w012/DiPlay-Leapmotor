package com.shilapi.xcertplay

import android.util.Log

/**
 * What the last C11 projection window actually did: which route drew the map, how big its square was,
 * and whether the edge fade was attached above the video.
 *
 * On the C11 the projection is drawn by [ClusterMapPresentation]; the ADB activity route cannot run
 * there because a public projection display already exists ([AdbClusterRouter.enabled] requires
 * `public == null`). Which window a firmware picked used to be answered from `Log.i` lines only, and
 * those never reach the diagnostic report: the report carries DiPlay's own session log files
 * ([SessionLogFile.REPORT_NAMES]), not logcat. A report therefore could not tell "the fade was not
 * attached" from "nobody ever read the line that says it was".
 *
 * Everything here is written twice — to logcat for a live look, and to the session log through
 * [onDiagnostic] — and the same snapshot is printed into the report header, so one exported report
 * answers the route and fade questions without another car run.
 */
internal object ClusterProjectionState {
    private const val TAG = "DiPlay-Cluster"

    /** Bound to the running session's log file; null outside a session. */
    @Volatile var onDiagnostic: ((String) -> Unit)? = null

    private val lock = Any()
    private var presentation: String? = null
    private var presentations = 0
    private var activity: String? = null
    private var mask: String? = null

    /** The cluster Presentation route: shown, skipped because no display, or failed. */
    fun presentation(line: String) {
        synchronized(lock) { presentation = line; presentations++ }
        note(line)
    }

    /** The independent-task route (`am start-activity --display N`). */
    fun activityRoute(line: String) {
        synchronized(lock) { activity = line }
        note(line)
    }

    /** The edge fade: attached (with its widths) or deliberately off. */
    fun mask(line: String) {
        synchronized(lock) { mask = line }
        note(line)
    }

    /** Any other route fact worth keeping, such as the viewport the window was measured for. */
    fun note(line: String) {
        Log.i(TAG, line)
        runCatching { onDiagnostic?.invoke(line) }
    }

    /** The snapshot the diagnostic report prints. */
    fun reportLines(): List<String> = synchronized(lock) {
        listOf(
            "clusterProjectionRoutes=$presentations",
            "clusterProjectionPresentation=${presentation ?: "none"}",
            "clusterProjectionActivityRoute=${activity ?: "none"}",
            "clusterProjectionMask=${mask ?: "none"}",
        )
    }

    /** Test seam: forget everything a previous case recorded. */
    internal fun reset() = synchronized(lock) {
        presentation = null
        presentations = 0
        activity = null
        mask = null
    }
}
