package io.mayu.birdpilot.core.roi

import android.graphics.Rect

object RoiUtils {
    /** viewOffsetX/Y は 0..1 の正規化座標。frac は短辺比。 */
    fun squareFromOffset(viewW: Int, viewH: Int, viewOffsetX: Float, viewOffsetY: Float, frac: Float = 0.35f): Rect {
        val s = (minOf(viewW, viewH) * frac).toInt()
        val cx = (viewW * viewOffsetX).toInt()
        val cy = (viewH * viewOffsetY).toInt()
        val left = (cx - s / 2).coerceIn(0, viewW - 1)
        val top  = (cy - s / 2).coerceIn(0, viewH - 1)
        val right = (left + s).coerceAtMost(viewW)
        val bottom = (top + s).coerceAtMost(viewH)
        return Rect(left, top, right, bottom)
    }
}
