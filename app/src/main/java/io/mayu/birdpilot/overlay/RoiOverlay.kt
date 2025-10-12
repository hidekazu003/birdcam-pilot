package io.mayu.birdpilot.overlay

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun RoiOverlay(modifier: Modifier = Modifier, roi: Rect?, lowConfidence: Boolean) {
    Canvas(modifier = modifier) {
        roi ?: return@Canvas

        // 点線の枠（低確度は細め）
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
        val stroke = Stroke(width = if (lowConfidence) 4f else 6f, pathEffect = dash)

        // 黄系の色（低確度は少し薄め）
        val color = if (lowConfidence) Color.Yellow.copy(alpha = 0.9f) else Color.Yellow

        drawRect(
            color = color, // ← 必須
            topLeft = Offset(roi.left.toFloat(), roi.top.toFloat()),
            size = Size(
                (roi.right - roi.left).toFloat(),
                (roi.bottom - roi.top).toFloat()
            ),
            style = stroke
        )
    }
}

