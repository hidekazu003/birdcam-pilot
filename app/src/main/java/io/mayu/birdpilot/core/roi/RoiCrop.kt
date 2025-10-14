package io.mayu.birdpilot.core.roi

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import androidx.camera.view.PreviewView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** ROI crop pulled from [PreviewView] screenshots for the C2 hook. */
data class RoiCrop(
    val bitmap: Bitmap,
    val rect: Rect,
    val normalizedCenter: PointF,
    val sourceWidth: Int,
    val sourceHeight: Int
) {
    companion object {
        fun fromPreview(
            previewView: PreviewView,
            normalizedPoint: PointF,
            sidePx: Int
        ): RoiCrop? {
            val viewWidth = previewView.width
            val viewHeight = previewView.height
            if (viewWidth <= 0 || viewHeight <= 0 || sidePx <= 0) {
                return null
            }
            val clamped = PointF(
                normalizedPoint.x.coerceIn(0f, 1f),
                normalizedPoint.y.coerceIn(0f, 1f)
            )
            val centerX = (clamped.x * viewWidth).roundToInt()
            val centerY = (clamped.y * viewHeight).roundToInt()
            val half = sidePx / 2
            val left = (centerX - half).coerceIn(0, viewWidth - 1)
            val top = (centerY - half).coerceIn(0, viewHeight - 1)
            val rectWidth = min(sidePx, viewWidth - left)
            val rectHeight = min(sidePx, viewHeight - top)
            if (rectWidth <= 0 || rectHeight <= 0) {
                return null
            }
            val roiRect = Rect(left, top, left + rectWidth, top + rectHeight)

            val fullBitmap = previewView.bitmap ?: return null
            val scaleX = fullBitmap.width.toFloat() / viewWidth.toFloat()
            val scaleY = fullBitmap.height.toFloat() / viewHeight.toFloat()
            val cropLeft = (roiRect.left * scaleX).roundToInt().coerceIn(0, max(fullBitmap.width - 1, 0))
            val cropTop = (roiRect.top * scaleY).roundToInt().coerceIn(0, max(fullBitmap.height - 1, 0))
            val cropWidth = min(
                fullBitmap.width - cropLeft,
                max(1, (roiRect.width() * scaleX).roundToInt())
            )
            val cropHeight = min(
                fullBitmap.height - cropTop,
                max(1, (roiRect.height() * scaleY).roundToInt())
            )
            if (cropWidth <= 0 || cropHeight <= 0) {
                if (!fullBitmap.isRecycled) {
                    fullBitmap.recycle()
                }
                return null
            }
            val cropped = Bitmap.createBitmap(fullBitmap, cropLeft, cropTop, cropWidth, cropHeight)
            if (fullBitmap !== cropped && !fullBitmap.isRecycled) {
                fullBitmap.recycle()
            }
            return RoiCrop(
                bitmap = cropped,
                rect = roiRect,
                normalizedCenter = clamped,
                sourceWidth = viewWidth,
                sourceHeight = viewHeight
            )
        }
    }
}
