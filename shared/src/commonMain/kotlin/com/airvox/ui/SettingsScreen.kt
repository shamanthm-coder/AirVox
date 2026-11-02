package com.airvox.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airvox.platformLabel
import com.airvox.theme.AirvoxPalette

@Composable
fun SettingsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp),
    ) {
        SectionLabel("Settings")
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Airvox",
            style = MaterialTheme.typography.headlineLarge,
            fontSize = 32.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Vox pauses the music when someone talks and opens the voice link to the other phone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        AirvoxCard {
            SettingRow(label = "Version", value = "1.0.0")
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                color = AirvoxPalette.Line,
            )
            SettingRow(label = "Device", value = platformLabel())
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            color = AirvoxPalette.Muted,
            fontSize = 15.sp,
        )
    }
}
