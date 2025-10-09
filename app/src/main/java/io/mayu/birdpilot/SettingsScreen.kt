package io.mayu.birdpilot

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val dataStore = remember(context) { context.cameraPreferenceDataStore }
    val coroutineScope = rememberCoroutineScope()
    val shutterSoundFlow = remember(dataStore) {
        dataStore.data.map { preferences -> preferences[SHUTTER_SOUND_KEY] ?: false }
    }
    val shutterSoundEnabled by shutterSoundFlow.collectAsState(initial = false)
    val finderProfileFlow = remember(dataStore) {
        dataStore.data.map { preferences ->
            FinderProfile.fromPreference(preferences[FINDER_PROFILE_KEY])
        }
    }
    val finderProfile by finderProfileFlow.collectAsState(initial = FinderProfile.OUTDOOR)

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Top
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                TextButton(
                    onClick = onBack,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Text(text = "← 戻る")
                }
                Text(
                    text = "設定",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "シャッター音",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "ONにすると撮影時に効果音を再生します\n一部端末では端末仕様で音が鳴る場合があります",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Switch(
                    checked = shutterSoundEnabled,
                    onCheckedChange = { enabled ->
                        coroutineScope.launch {
                            dataStore.edit { preferences ->
                                preferences[SHUTTER_SOUND_KEY] = enabled
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Finder プロファイル",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "撮影環境に合わせて検出感度を切り替えます",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FinderProfile.values().forEach { profile ->
                        FilterChip(
                            selected = finderProfile == profile,
                            onClick = {
                                if (finderProfile != profile) {
                                    coroutineScope.launch {
                                        dataStore.edit { preferences ->
                                            preferences[FINDER_PROFILE_KEY] = profile.preferenceValue
                                        }
                                    }
                                }
                            },
                            label = {
                                Text(text = profile.label)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.Black,
                                selectedContainerColor = Color.White,
                                labelColor = Color.White,
                                selectedLabelColor = Color.Black
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = finderProfile == profile,
                                borderColor = Color.White,
                                selectedBorderColor = Color.White
                            )
                        )
                    }
                }
            }
        }
    }
}
