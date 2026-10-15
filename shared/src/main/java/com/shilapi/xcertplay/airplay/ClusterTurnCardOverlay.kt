package com.shilapi.xcertplay.airplay

/**
 * Where DiPlay draws the instruction card on top of the dashboard map.
 *
 * Placement is a percent of the full 1920×720 panel so Left/Right can reach the
 * cluster edges. Size still follows the measured centre navi window so Large
 * does not cover half the cluster.
 */
object ClusterTurnCardOverlay {
    data class CardRect(val left: Int, val top: Int, val width: Int, val height: Int)

    const val STEP_PERCENT = 1
    const val DEFAULT_X_PERCENT = 76
    const val DEFAULT_Y_PERCENT = 30
    val xPercents = (8..92 step 1).toList()
    /** Opacity choices for the slider: 20..100 in steps of 5, default 85. */
    val opacityPercents = listOf(20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100)
    const val DEFAULT_OPACITY_PERCENT = 85

    val yPercents = (12..72 step 1).toList()

    /**
     * Card width as percent of the visible navi window; one slider step = 5 %.
     *
     * The list used to stop at 95 %, which on a 1920 px panel is 529 px and reads as a small card. The
     * window is what it is measured against, so a wider window (the C11 square's fade-free strip) and
     * the values above 100 % are both needed to reach the size a driver asks for. The original 5 % steps
     * stay, so a saved setting keeps snapping to the same value it always did.
     */
    val sizePercents = (30..95 step 5).toList() + listOf(100, 110, 125, 150, 175, 200)

    /** Small-window card placement (percent of the panel), 1 % steps. */
    val smallWindowXPercents = (10..90 step 1).toList()
    val smallWindowYPercents = (15..70 step 1).toList()

    const val DEFAULT_SIZE_PERCENT = 55

    fun card(
        panelWidth: Int,
        panelHeight: Int,
        xPercent: Int,
        yPercent: Int,
        sizePercent: Int,
        windowInsets: AirPlayInsets = CarPlayClusterDisplay.SAFE_AREA_PERCENT,
    ): CardRect {
        require(panelWidth > 0 && panelHeight > 0)
        val window = visibleWindow(panelWidth, panelHeight, windowInsets)
        val size = snap(sizePercent, sizePercents)
        // The card keeps its ~2.6:1 banner aspect at any size. The height is capped by the panel, not by
        // the window, so a card above 100 % grows instead of being squashed flat.
        val width = (window.width * size / 100).coerceAtLeast(150).coerceAtMost(panelWidth)
        val height = (width / 2.64f).toInt().coerceAtLeast(58).coerceAtMost(panelHeight)
        val x = snap(xPercent, xPercents)
        val y = snap(yPercent, yPercents)
        val left = (panelWidth * x / 100 - width / 2).coerceIn(0, panelWidth - width)
        val top = (panelHeight * y / 100 - height / 2).coerceIn(0, panelHeight - height)
        return CardRect(left, top, width, height)
    }

    fun snap(value: Int, choices: List<Int>): Int =
        choices.minByOrNull { kotlin.math.abs(it - value) } ?: choices.first()

    internal fun visibleWindow(
        panelWidth: Int,
        panelHeight: Int,
        windowInsets: AirPlayInsets = CarPlayClusterDisplay.SAFE_AREA_PERCENT,
    ): CardRect {
        val area = windowInsets
        val left = panelWidth * area.left / 100
        val top = panelHeight * area.top / 100
        val width = panelWidth * (100 - area.left - area.right) / 100
        val height = panelHeight * (100 - area.top - area.bottom) / 100
        return CardRect(left, top, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }
}
