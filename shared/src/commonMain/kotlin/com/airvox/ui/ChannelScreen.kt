package com.airvox.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airvox.generated.resources.Res
import com.airvox.generated.resources.airvox_logo
import com.airvox.intercom.ChannelPath
import com.airvox.intercom.Earphone
import com.airvox.intercom.IntercomConnection
import com.airvox.intercom.IntercomSession
import com.airvox.vox.Vox
import com.airvox.theme.AirvoxPalette
import org.jetbrains.compose.resources.painterResource

@Composable
fun ChannelScreen(
    session: IntercomSession,
    vox: Vox,
    activeAudioOutput: Earphone? = null,
) {
    val intercom by session.state
    val voxState by vox.state
    var roomCode by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var selectedPath by remember { mutableStateOf(ChannelPath.Hotspot) }
    val inRoom = intercom.connection == IntercomConnection.Connecting ||
        intercom.connection == IntercomConnection.Connected
    val shownPath = if (inRoom) intercom.path else selectedPath
    val voiceActive = intercom.localTalking || intercom.remoteTalking

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Channel")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(
                    when {
                        voiceActive && voxState.enabled -> "Vox"
                        voiceActive -> "Voice"
                        intercom.connection == IntercomConnection.Connected -> intercom.path.label
                        intercom.connection == IntercomConnection.Connecting -> "Joining"
                        intercom.connection == IntercomConnection.Unavailable -> "Android"
                        else -> "Off"
                    },
                )
                HeaderIconButton(description = "Wallet", onClick = {}) {
                    WalletIcon(tint = AirvoxPalette.White)
                }
                HeaderIconButton(
                    description = "SOS",
                    onClick = {},
                    borderColor = SosRed,
                ) {
                    SosIcon(tint = SosRed)
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                        ),
                    ),
            )
            Image(
                painter = painterResource(Res.drawable.airvox_logo),
                contentDescription = "Airvox",
                modifier = Modifier.size(104.dp),
                contentScale = ContentScale.Fit,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(text = "Airvox", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Vox pauses the music when someone talks, and connects your voice to the other phone.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(22.dp))
        AirvoxCard {
            VoxRow(
                enabled = voxState.enabled,
                onToggle = { vox.setEnabled(!voxState.enabled) },
            )
            Spacer(Modifier.height(14.dp))
            PathPicker(
                selected = shownPath,
                enabled = !inRoom,
                onSelect = {
                    selectedPath = it
                    vox.noteRoom(roomCode, it, password)
                },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = pathBlurb(shownPath, activeAudioOutput),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = when {
                    intercom.members.isEmpty() -> "No one on the channel"
                    else -> "${intercom.members.size} on the channel"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (intercom.members.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = intercom.members.joinToString { member ->
                        if (member.talking) "${member.name} · talking" else member.name
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!intercom.detail.isNullOrBlank() && intercom.members.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(text = intercom.detail.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(14.dp))
            ChannelField(
                value = if (inRoom) intercom.roomCode else roomCode,
                onValueChange = {
                    roomCode = it.take(16)
                    vox.noteRoom(roomCode, shownPath, password)
                },
                enabled = !inRoom,
                placeholder = "Room code",
                imeAction = ImeAction.Next,
                onDone = { vox.connect(roomCode, shownPath, password) },
            )
            Spacer(Modifier.height(10.dp))
            ChannelField(
                value = password,
                onValueChange = {
                    password = it.take(32)
                    vox.noteRoom(roomCode, shownPath, password)
                },
                enabled = !inRoom,
                placeholder = "Password",
                imeAction = ImeAction.Done,
                onDone = { vox.connect(roomCode, shownPath, password) },
                password = true,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    if (inRoom) vox.leave() else vox.connect(roomCode, shownPath, password)
                },
                enabled = intercom.connection != IntercomConnection.Unavailable,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AirvoxPalette.Line),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AirvoxPalette.White,
                    disabledContentColor = AirvoxPalette.Dim,
                ),
            ) {
                Text(
                    text = when (intercom.connection) {
                        IntercomConnection.Connecting -> "Leave"
                        IntercomConnection.Connected -> "Leave channel"
                        else -> "Join channel"
                    },
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        TalkButton(
            talking = intercom.localTalking,
            onToggle = { vox.toggleTalk() },
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (voxState.enabled) {
                voxState.line
            } else {
                talkHint(intercom.localTalking, voiceActive, intercom.connection, intercom.detail)
            },
            color = AirvoxPalette.Muted,
            fontSize = 14.sp,
        )
    }
}

private val SosRed = Color(0xFFFF4D4D)

@Composable
private fun HeaderIconButton(
    description: String,
    onClick: () -> Unit,
    borderColor: Color = AirvoxPalette.Line,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = description
            }
            .clip(CircleShape)
            .border(width = 1.dp, color = borderColor, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

@Composable
private fun StatusPill(label: String) {
    Text(
        text = label,
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(AirvoxPalette.SurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = AirvoxPalette.Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun TalkButton(
    talking: Boolean,
    onToggle: () -> Unit,
) {
    val fill = if (talking) AirvoxPalette.White else Color.Transparent
    val mark = if (talking) AirvoxPalette.Black else AirvoxPalette.White
    Box(
        modifier = Modifier
            .size(148.dp)
            .semantics {
                role = Role.Button
                contentDescription = if (talking) "Stop talking" else "Start talking"
            }
            .clip(CircleShape)
            .background(fill)
            .border(width = 1.5.dp, color = AirvoxPalette.White, shape = CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (talking) "Stop" else "Talk",
            color = mark,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
        )
    }
}

private fun talkHint(
    talking: Boolean,
    voiceActive: Boolean,
    connection: IntercomConnection,
    detail: String?,
): String {
    return when {
        talking && connection != IntercomConnection.Connected ->
            detail ?: "Join a channel first"
        talking || voiceActive -> "Talking — tap Stop when you are done"
        connection == IntercomConnection.Connected -> "Tap Talk to speak. Tap Stop to finish."
        else -> detail ?: "Pick Bluetooth, Hotspot, or Internet, then enter the same room code on every phone."
    }
}

@Composable
private fun VoxRow(
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Vox", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Pauses the music and connects your voice.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val mark = if (enabled) AirvoxPalette.Black else AirvoxPalette.White
        Text(
            text = if (enabled) "On" else "Off",
            color = mark,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .semantics {
                    role = Role.Button
                    contentDescription = if (enabled) "Turn Vox off" else "Turn Vox on"
                }
                .clip(RoundedCornerShape(100.dp))
                .background(if (enabled) AirvoxPalette.White else Color.Transparent)
                .border(
                    width = 1.dp,
                    color = if (enabled) AirvoxPalette.White else AirvoxPalette.Line,
                    shape = RoundedCornerShape(100.dp),
                )
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PathPicker(
    selected: ChannelPath,
    enabled: Boolean,
    onSelect: (ChannelPath) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChannelPath.entries.forEach { path ->
            val on = path == selected
            val mark = when {
                on -> AirvoxPalette.Black
                enabled -> AirvoxPalette.White
                else -> AirvoxPalette.Dim
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = path.label
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) AirvoxPalette.White else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (on) AirvoxPalette.White else AirvoxPalette.Line,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable(enabled = enabled, onClick = { onSelect(path) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = path.label,
                    color = mark,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ChannelField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    imeAction: ImeAction,
    onDone: () -> Unit,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(placeholder) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = placeholder },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = AirvoxPalette.White,
            unfocusedTextColor = AirvoxPalette.White,
            disabledTextColor = AirvoxPalette.White,
            focusedBorderColor = AirvoxPalette.White,
            unfocusedBorderColor = AirvoxPalette.Line,
            disabledBorderColor = AirvoxPalette.Line,
            cursorColor = AirvoxPalette.White,
            focusedPlaceholderColor = AirvoxPalette.Dim,
            unfocusedPlaceholderColor = AirvoxPalette.Dim,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
        ),
    )
}

private fun pathBlurb(path: ChannelPath, activeAudioOutput: Earphone?): String {
    val output = activeAudioOutput?.let { " Plays on ${it.name}." }.orEmpty()
    val body = when (path) {
        ChannelPath.Bluetooth ->
            "Voice goes over Bluetooth. Stay close, with Bluetooth on, and use the same room code and password."
        ChannelPath.Hotspot ->
            "Voice goes over a Wi-Fi hotspot between phones. No internet needed. Use the same room code and password."
        ChannelPath.Internet ->
            "Voice goes over the internet. Each phone needs data or Wi-Fi, plus the same room code and password."
    }
    return body + output
}
