package io.mayu.birdpilot.detector

import io.mayu.birdpilot.core.roi.RoiCrop
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lightweight detector abstraction. Phase-1 ships with heuristic logic while keeping
 * the interface ready for the Phase-2 TFLite drop-in (IF 互換予定).
 */
interface BirdDetector {
    data class Result(
        val score: Float
    )

    suspend fun detect(crop: RoiCrop): Result

    companion object {
        fun noop(): BirdDetector = NoopBirdDetector
        fun heuristic(): BirdDetector = HeuristicBirdDetector
    }
}

private object NoopBirdDetector : BirdDetector {
    override suspend fun detect(crop: RoiCrop): BirdDetector.Result = BirdDetector.Result(score = 0f)
}

private object HeuristicBirdDetector : BirdDetector {
    override suspend fun detect(crop: RoiCrop): BirdDetector.Result {
        val bitmap = crop.bitmap
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) {
            return BirdDetector.Result(score = 0f)
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var sum = 0.0
        var sumSq = 0.0
        for (value in pixels) {
            val luminance = luminance(value)
            sum += luminance
            sumSq += luminance * luminance
        }
        val count = pixels.size.coerceAtLeast(1)
        val mean = sum / count
        val variance = max(sumSq / count - mean * mean, 0.0)
        val sigma = sqrt(variance)
        val normalized = (sigma / 64.0).coerceIn(0.0, 1.0)
        return BirdDetector.Result(score = normalized.toFloat())
    }

    private fun luminance(pixel: Int): Double {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}
