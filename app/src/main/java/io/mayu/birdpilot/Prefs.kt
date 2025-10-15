package io.mayu.birdpilot

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


// DataStore (Preferences) をアプリ全体で共有
val Context.cameraPreferenceDataStore by preferencesDataStore(name = "camera_preferences")

// キーは共通公開（どこからでも使える）
val GRID_ENABLED_KEY = booleanPreferencesKey("grid_enabled")
val SHUTTER_SOUND_KEY = booleanPreferencesKey("shutter_sound")
val FINDER_PROFILE_KEY = stringPreferencesKey("finder_profile")
val FINDER_ENABLED_KEY = booleanPreferencesKey("finder_enabled")
fun finderEnabledFlow(context: Context): Flow<Boolean> =
    context.cameraPreferenceDataStore.data.map { prefs ->
        prefs[FINDER_ENABLED_KEY] ?: true   // 既定は true
    }

suspend fun setFinderEnabled(context: Context, enabled: Boolean) {
    context.cameraPreferenceDataStore.edit { prefs ->
        prefs[FINDER_ENABLED_KEY] = enabled
    }
}
