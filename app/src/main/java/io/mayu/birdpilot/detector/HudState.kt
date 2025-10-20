package io.mayu.birdpilot.detector

/**
 * Represents the Heads-Up Display color decision for the ROI indicator.
 */
enum class Hud {
    GREEN,
    YELLOW
}

/**
 * Calculates the HUD state for the ROI overlay.
 *
 * @param score The detector score, or `null` when not yet evaluated.
 * @param roiTh The threshold separating GREEN and YELLOW states.
 * @return [Hud.GREEN] when `score` is not null and meets `roiTh`, otherwise [Hud.YELLOW].
 */
fun hudState(score: Float?, roiTh: Float): Hud {
    return if (score != null && score >= roiTh) {
        Hud.GREEN
    } else {
        Hud.YELLOW
    }
}
