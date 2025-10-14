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
fun RoiOverlay(modifier: Modifier = Modifier, roi: Rect?, score: Float?) {
    Canvas(modifier = modifier) {
        roi ?: return@Canvas

        val highConfidence = score != null && score >= 0.4f
        val lowConfidence = score == null

        val dash = if (highConfidence) null else PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
        val strokeWidth = if (highConfidence) 6f else 4f
        val stroke = Stroke(width = strokeWidth, pathEffect = dash)

        val color = when {
            highConfidence -> Color(0xFF4CAF50)
            lowConfidence -> Color.Yellow.copy(alpha = 0.9f)
            else -> Color.Yellow
        }

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

