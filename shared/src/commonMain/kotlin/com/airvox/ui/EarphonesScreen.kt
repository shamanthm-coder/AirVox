package com.airvox.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airvox.bluetooth.BluetoothEarphoneScanner
import com.airvox.bluetooth.BluetoothScanState
import com.airvox.intercom.Earphone
import com.airvox.playback.PlaybackController
import com.airvox.theme.AirvoxPalette

@Composable
fun EarphonesScreen(
    playback: PlaybackController,
    bluetoothScanner: BluetoothEarphoneScanner,
) {
    val scan = bluetoothScanner.scanResult.value
    val scanning = scan.state == BluetoothScanState.Scanning

    LaunchedEffect(Unit) {
        bluetoothScanner.refreshAudioOutput()
        playback.refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel("Earphones")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Audio output",
                style = MaterialTheme.typography.headlineLarge,
                fontSize = 32.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Airvox plays voice on your phone. Android sends it to whatever headset is connected — you don't pick a device inside the app.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            PlaybackCard(playback)
            Spacer(Modifier.height(16.dp))

            ActiveOutputCard(scan.activeOutput)

            scan.statusMessage?.let { status ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AirvoxPalette.Muted,
                )
            }

            if (scan.nearbyOrPaired.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                SectionLabel("Other paired devices")
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    scan.nearbyOrPaired.forEach { device ->
                        EarphoneRow(device, subtitle = "Paired on phone")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        OutlinedButton(
            onClick = { bluetoothScanner.refreshAudioOutput() },
            enabled = !scanning,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AirvoxPalette.Line),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = AirvoxPalette.White,
                disabledContentColor = AirvoxPalette.Dim,
            ),
        ) {
            Text("Refresh audio output", fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { bluetoothScanner.scanNearby() },
            enabled = !scanning,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AirvoxPalette.Line),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = AirvoxPalette.Muted,
                disabledContentColor = AirvoxPalette.Dim,
            ),
        ) {
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = AirvoxPalette.Muted,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(8.dp))
                Text("Searching…", fontSize = 14.sp)
            } else {
                Text("Search nearby (advanced)", fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun ActiveOutputCard(active: Earphone?) {
    AirvoxCard {
        Text(
            text = if (active != null) "Playing through" else "No Bluetooth headset",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = active?.name ?: "Phone speaker or wired headphones",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (active != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Active now",
                color = AirvoxPalette.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun PlaybackCard(playback: PlaybackController) {
    val nowPlaying = playback.nowPlaying.value
    AirvoxCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Playback", style = MaterialTheme.typography.titleMedium)
            Text(
                text = when {
                    !nowPlaying.hasSession -> "Phone"
                    nowPlaying.paused -> "Paused"
                    else -> "Playing"
                },
                color = AirvoxPalette.Muted,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = nowPlaying.title ?: "No song title yet",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = listOfNotNull(nowPlaying.artist, nowPlaying.appName).joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = AirvoxPalette.Muted,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        nowPlaying.detail?.let { detail ->
            Spacer(Modifier.height(8.dp))
            Text(text = detail, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(14.dp))
        if (nowPlaying.accessRequired) {
            Text(
                text = "Allow notification access to see Pause, Play, and skip. This step is required.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = playback::openAccessSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AirvoxPalette.White,
                    contentColor = AirvoxPalette.Black,
                ),
            ) {
                Text("Allow notification access", fontWeight = FontWeight.Medium)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaybackAction("Previous", Modifier.weight(1f), playback::skipPrevious)
                PlaybackAction(
                    label = if (nowPlaying.paused) "Play" else "Pause",
                    modifier = Modifier.weight(1f),
                    onClick = playback::togglePlay,
                )
                PlaybackAction("Next", Modifier.weight(1f), playback::skipNext)
            }
        }
    }
}

@Composable
private fun PlaybackAction(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AirvoxPalette.Line),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AirvoxPalette.White),
    ) {
        Text(label, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun EarphoneRow(earphone: Earphone, subtitle: String) {
    AirvoxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(10.dp),
                shape = CircleShape,
                color = AirvoxPalette.Muted,
            ) {}
            Spacer(Modifier.size(12.dp))
            Column {
                Text(earphone.name, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = AirvoxPalette.Muted, fontSize = 13.sp)
            }
        }
    }
}
