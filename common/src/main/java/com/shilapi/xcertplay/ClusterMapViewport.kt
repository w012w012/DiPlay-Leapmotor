package com.shilapi.xcertplay

/**
 * Where CarPlay's instrument-cluster picture is drawn inside the cluster's projection window, and
 * which part of the iPhone's stream it shows.
 *
 * The cluster panel and the iPhone's cluster stream rarely have the same shape: a cluster can be a
 * long strip while CarPlay sends a square dashboard map. [sourceLeft]/[sourceTop] crop the stream
 * at native pixel scale; [left]/[top]/[width]/[height] place that crop on the panel.
 * [streamWidth]/[streamHeight] are the native video size the texture must be created with.
 *
 * Introduced for the Leapmotor C11 adaptation so one generic renderer can serve both the measured
 * BYD DiLink 5.1 plan and Leapmotor's fixed square window.
 */
internal data class ClusterMapViewport(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val sourceLeft: Int,
    val sourceTop: Int,
    val streamWidth: Int,
    val streamHeight: Int,
    /** Fill the whole cluster panel, letting the map bleed past the video box. */
    val fullMap: Boolean,
    /** Keep the video window pinned exactly inside this viewport instead of stretching to the panel. */
    val pinWindowToViewport: Boolean,
)
