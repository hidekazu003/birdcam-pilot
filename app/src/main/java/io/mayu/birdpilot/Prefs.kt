package io.mayu.birdpilot

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey

val ROI_THRESHOLD_KEY = floatPreferencesKey("roi_threshold")   // 0.4f 既定

fun roiThresholdFlow(context: Context): Flow<Float> =
    context.cameraPreferenceDataStore.data.map { it[ROI_THRESHOLD_KEY] ?: 0.4f }

suspend fun setRoiThreshold(context: Context, value: Float) {
    context.cameraPreferenceDataStore.edit { it[ROI_THRESHOLD_KEY] = value }
}

// --- ROI frame scale preset -----------------------------------------------
// 0.8 (= -20%), 1.0 (= default), 1.2 (= +20%)
private val ROI_FRAME_SCALE_KEY = floatPreferencesKey("roi_frame_scale")

/** 現在の ROI 枠スケール。存在しなければ 1.0f を返す */
fun roiFrameScaleFlow(context: Context): Flow<Float> =
    context.cameraPreferenceDataStore.data.map { it[ROI_FRAME_SCALE_KEY] ?: 1.0f }

/** ROI 枠スケールを保存（0.8..1.2 に丸め） */
suspend fun setRoiFrameScale(context: Context, value: Float) {
    val v = value.coerceIn(0.8f, 1.2f)
    context.cameraPreferenceDataStore.edit { it[ROI_FRAME_SCALE_KEY] = v }
}

// DataStore (Preferences) をアプリ全体で共有
val Context.cameraPreferenceDataStore by preferencesDataStore(name = "camera_preferences")

// キーは共通公開（どこからでも使える）
val GRID_ENABLED_KEY = booleanPreferencesKey("grid_enabled")
val SHUTTER_SOUND_KEY = booleanPreferencesKey("shutter_sound")
val FINDER_PROFILE_KEY = stringPreferencesKey("finder_profile")

val FINDER_ENABLED_KEY = booleanPreferencesKey("finder_enabled")

val Context.finderEnabledFlow: Flow<Boolean>
    get() = cameraPreferenceDataStore.data.map { it[FINDER_ENABLED_KEY] ?: false }

suspend fun setFinderEnabled(ctx: Context, enabled: Boolean) {
    ctx.cameraPreferenceDataStore.edit { it[FINDER_ENABLED_KEY] = enabled }
}