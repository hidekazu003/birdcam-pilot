package io.mayu.birdpilot

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.Preferences


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