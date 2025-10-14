package io.mayu.birdpilot.finder

import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import androidx.camera.view.PreviewView
import io.mayu.birdpilot.core.roi.RoiCrop
import io.mayu.birdpilot.detector.BirdDetector
import io.mayu.birdpilot.detector.DetectorGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

object StillScan {
    private const val TAG = "StillScan"
    private const val ROI_SIDE_PX = 128
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 安定/長押し直後に1回だけ呼ぶ想定。ROIを算出して表示コールバックへ。 */
    fun ensureBirdness(
        normalizedPoint: PointF,
        previewView: PreviewView,
        gate: DetectorGate,
        detector: BirdDetector,
        showRoi: (Rect, Float?) -> Unit
    ) {
        when (val decision = gate.tryAcquire()) {
            is DetectorGate.Decision.Rejected -> {
                Log.d(
                    TAG,
                    "C2 skip: gate ${decision.elapsedSinceLastMs}ms < ${gate.minIntervalMs}"
                )
                return
            }
            is DetectorGate.Decision.Accepted -> {
                Log.d(TAG, "C2 gate opened after ${decision.elapsedSinceLastMs}ms")
            }
        }

        val w = previewView.width
        val h = previewView.height
        if (w <= 0 || h <= 0) {
            Log.d(TAG, "C2 skip: previewView has no size")
            return
        }

        scope.launch {
            val crop = withContext(Dispatchers.Main) {
                RoiCrop.fromPreview(previewView, normalizedPoint, ROI_SIDE_PX)
            }

            if (crop == null) {
                Log.d(TAG, "C2 skip: ROI crop unavailable")
                return@launch
            }

            withContext(Dispatchers.Main) { showRoi(crop.rect, null) }

            val result = runCatching { detector.detect(crop) }
                .onFailure { Log.e(TAG, "C2 detect error", it) }
                .getOrNull()

            val score = result?.score
            if (score != null) {
                Log.d(TAG, "C2 call score=${String.format(Locale.US, "%.3f", score)}")
            }

            withContext(Dispatchers.Main) { showRoi(crop.rect, score) }
        }
    }
}
