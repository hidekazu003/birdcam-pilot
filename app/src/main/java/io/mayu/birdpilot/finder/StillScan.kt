package io.mayu.birdpilot.finder

import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.view.PreviewView
import io.mayu.birdpilot.core.roi.RoiUtils
import kotlinx.coroutines.*

object StillScan {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastInvokeMs = 0L

    /** 安定/長押し直後に1回だけ呼ぶ想定。ROIを算出して表示コールバックへ。 */
    fun ensureBirdness(
        viewOffset: PointF,
        previewView: PreviewView,
        showRoi: (Rect, /*lowConfidence=*/Boolean) -> Unit
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastInvokeMs < 300) return
        lastInvokeMs = now

        val w = previewView.width
        val h = previewView.height
        scope.launch {
            val roi = RoiUtils.squareFromOffset(w, h, viewOffset.x, viewOffset.y, 0.35f)
            withContext(Dispatchers.Main) { showRoi(roi, true) } // 低確度リング表示
            // TODO: T-C2 で推論を差し込み、scoreで昇格(枠色/実線化など)
        }
    }
}
