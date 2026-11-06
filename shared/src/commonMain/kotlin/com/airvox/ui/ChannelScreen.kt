package com.airvox.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airvox.bluetooth.BluetoothEarphoneScanner
import com.airvox.generated.resources.Res
import com.airvox.generated.resources.airvox_logo
import com.airvox.intercom.ChannelPath
import com.airvox.intercom.Earphone
import com.airvox.intercom.IntercomConnection
import com.airvox.intercom.IntercomSession
import com.airvox.playback.PlaybackController
import com.airvox.vox.Vox
import com.airvox.voxy.Voxy
import com.airvox.voxy.VoxyState
import com.airvox.theme.AirvoxPalette
import org.jetbrains.compose.resources.painterResource

/** Room code, password, and path. Kept by the app so a tab change does not clear them. */
class ChannelFormState {
    val roomCode = mutableStateOf("")
    val password = mutableStateOf("")
    val path = mutableStateOf(ChannelPath.Hotspot)
}

@Composable
fun ChannelScreen(
    session: IntercomSession,
    vox: Vox,
    voxy: Voxy,
    playback: PlaybackController,
    bluetoothScanner: BluetoothEarphoneScanner,
    credits: Int,
    onOpenPlans: () -> Unit,
    form: ChannelFormState,
) {
    val intercom by session.state
    val voxState by vox.state
    val voxyState by voxy.state
    val audio = bluetoothScanner.scanResult.value
    var roomCode by form.roomCode
    var password by form.password
    var selectedPath by form.path
    var creditNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        bluetoothScanner.refreshAudioOutput()
        playback.refresh()
    }
    val inRoom = intercom.connection == IntercomConnection.Connecting ||
        intercom.connection == IntercomConnection.Connected
    val shownPath = if (inRoom) intercom.path else selectedPath
    val voiceActive = intercom.localTalking || intercom.remoteTalking
    val activeAudioOutput = audio.activeOutput

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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(Res.drawable.airvox_logo),
                contentDescription = "Airvox",
                modifier = Modifier.size(28.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "Airvox",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = AirvoxPalette.White,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = credits.toString(),
                color = AirvoxPalette.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.size(8.dp))
            HeaderIconButton(description = "Plans, $credits credits", onClick = onOpenPlans) {
                WalletIcon(tint = AirvoxPalette.White)
            }
            Spacer(Modifier.size(8.dp))
            HeaderIconButton(
                description = "SOS",
                onClick = {},
                borderColor = SosRed,
            ) {
                SosIcon(tint = SosRed)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
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
        }

        Spacer(Modifier.height(16.dp))
        VoxyCard(
            state = voxyState,
            canBackground = voxy.canBackground,
            onBackground = voxy::setBackground,
        )
        Spacer(Modifier.height(16.dp))
        AudioControlCard(
            playback = playback,
            active = activeAudioOutput,
            onRefresh = bluetoothScanner::refreshAudioOutput,
        )
        Spacer(Modifier.height(16.dp))
        AirvoxCard {
            VoxRow(
                enabled = voxState.enabled,
                onToggle = {
                    val turnOn = !voxState.enabled
                    vox.setEnabled(turnOn)
                    voxy.setEnabled(turnOn)
                },
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
                onDone = {},
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
                onDone = {},
                password = true,
            )
            Spacer(Modifier.height(10.dp))
            val actionColor = if (inRoom) LeaveRed else JoinGreen
            OutlinedButton(
                onClick = {
                    when {
                        inRoom -> vox.leave()
                        credits < 1 -> creditNote = "Add credits on the Plans page. Joining uses 1 credit, even for a short stay."
                        else -> {
                            creditNote = null
                            vox.connect(roomCode, shownPath, password)
                        }
                    }
                },
                enabled = intercom.connection != IntercomConnection.Unavailable,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    if (intercom.connection == IntercomConnection.Unavailable) AirvoxPalette.Line else actionColor,
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = actionColor,
                    disabledContainerColor = Color.Transparent,
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
            creditNote?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(text = message, color = AirvoxPalette.Muted, fontSize = 13.sp)
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
private val JoinGreen = Color(0xFF1F9D55)
private val LeaveRed = Color(0xFFE23B3B)

@Composable
private fun HeaderIconButton(
    description: String,
    onClick: () -> Unit,
    borderColor: Color = AirvoxPalette.Line,
    buttonSize: Dp = 40.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(buttonSize)
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
private fun VoxyCard(
    state: VoxyState,
    canBackground: Boolean,
    onBackground: (Boolean) -> Unit,
) {
    var confirmLock by remember { mutableStateOf(false) }
    AirvoxCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Voxy", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(text = state.line, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.size(12.dp))
            StatusPill(if (state.listening) "Listening" else "Quiet")
        }
        if (canBackground) {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "While locked", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Listens when the phone is locked. A notification stays on.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AirvoxPalette.Dim,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.size(12.dp))
                val on = state.background
                Text(
                    text = if (on) "On" else "Off",
                    color = if (on) AirvoxPalette.Black else AirvoxPalette.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (on) AirvoxPalette.White else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (on) AirvoxPalette.White else AirvoxPalette.Line,
                            shape = RoundedCornerShape(100.dp),
                        )
                        .clickable {
                            if (on) onBackground(false) else confirmLock = true
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
    if (confirmLock) {
        AlertDialog(
            onDismissRequest = { confirmLock = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmLock = false
                    onBackground(true)
                }) {
                    Text("Allow", color = AirvoxPalette.White, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLock = false }) {
                    Text("Not now", color = AirvoxPalette.Muted)
                }
            },
            title = { Text("Listen while the phone is locked?") },
            text = {
                Text(
                    "Voxy can hear you when the screen is locked. Android will ask for notification access, and may ask to leave battery use unrestricted. A notification stays visible the whole time the microphone is on. Turn this off here whenever you want.",
                )
            },
            containerColor = AirvoxPalette.Surface,
            titleContentColor = AirvoxPalette.White,
            textContentColor = AirvoxPalette.Muted,
        )
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
            Text(text = "Voxy", style = MaterialTheme.typography.titleMedium)
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
                    contentDescription = if (enabled) "Turn Voxy off" else "Turn Voxy on"
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
    var revealed by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeKeyboard = {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(placeholder) },
        visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) {
            {
                IconButton(
                    onClick = { revealed = !revealed },
                    modifier = Modifier.semantics {
                        contentDescription = if (revealed) "Hide password" else "Show password"
                    },
                ) {
                    EyeIcon(visible = revealed, tint = AirvoxPalette.Muted)
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                closeKeyboard()
                onDone()
            },
            onGo = {
                closeKeyboard()
                onDone()
            },
            onNext = { closeKeyboard() },
        ),
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

@Composable
private fun AudioControlCard(
    playback: PlaybackController,
    active: Earphone?,
    onRefresh: () -> Unit,
) {
    val nowPlaying = playback.nowPlaying.value
    AirvoxCard {
        Text(
            text = if (active != null) "Playing through" else "Earphones",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = active?.name ?: "Phone speaker or wired headphones",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
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
        Spacer(Modifier.height(14.dp))
        if (nowPlaying.accessRequired) {
            Button(
                onClick = playback::openAccessSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
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
                PlaybackAction("Play", Modifier.weight(1f), playback::play)
                PlaybackAction("Pause", Modifier.weight(1f), playback::pause)
                PlaybackAction("Next", Modifier.weight(1f), playback::skipNext)
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, AirvoxPalette.Line),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AirvoxPalette.White),
        ) {
            Text("Refresh earphones", fontWeight = FontWeight.Medium)
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

private fun pathBlurb(path: ChannelPath, activeAudioOutput: Earphone?): String {
    val output = activeAudioOutput?.let { " Plays on ${it.name}." }.orEmpty()
    val body = when (path) {
        ChannelPath.Bluetooth ->
            "Voice goes over Bluetooth. Stay close, with Bluetooth on, and use the same room code and password."
        ChannelPath.Hotspot ->
            "Voice goes over the Wi-Fi hotspot. No internet needed. Both phones join that same hotspot, with the same room code and password."
        ChannelPath.Internet ->
            "Voice goes over the internet. Each phone needs data or Wi-Fi, plus the same room code and password."
    }
    return body + output
}
