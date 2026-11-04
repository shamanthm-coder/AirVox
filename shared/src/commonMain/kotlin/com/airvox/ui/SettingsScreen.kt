package com.airvox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airvox.auth.AuthClient
import com.airvox.auth.providerLabel
import com.airvox.platformLabel
import com.airvox.theme.AirvoxPalette

@Composable
fun SettingsScreen(auth: AuthClient) {
    val account = auth.state.value.account
    val busy = auth.state.value.busy
    var signingOut by remember { mutableStateOf(false) }
    var name by remember(account?.id, account?.displayName) {
        mutableStateOf(account?.displayName.orEmpty())
    }
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
        if (account != null) {
            AirvoxCard {
                Text("Signed in", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = account.phoneNumber ?: account.email ?: "Rider",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = providerLabel(account.signInProvider),
                    color = AirvoxPalette.Dim,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    placeholder = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AirvoxPalette.White,
                        unfocusedTextColor = AirvoxPalette.White,
                        focusedBorderColor = AirvoxPalette.White,
                        unfocusedBorderColor = AirvoxPalette.Line,
                        cursorColor = AirvoxPalette.White,
                        focusedPlaceholderColor = AirvoxPalette.Dim,
                        unfocusedPlaceholderColor = AirvoxPalette.Dim,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { auth.updateDisplayName(name) },
                    enabled = !busy && name.isNotBlank() && name.trim() != account.displayName.orEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AirvoxPalette.White,
                        contentColor = AirvoxPalette.Black,
                        disabledContainerColor = AirvoxPalette.SurfaceHigh,
                        disabledContentColor = AirvoxPalette.Dim,
                    ),
                ) {
                    Text(if (busy) "Saving…" else "Save name", fontWeight = FontWeight.Medium)
                }
                auth.state.value.error?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(text = message, color = Color(0xFFFFB4B4), fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        signingOut = true
                        auth.signOut()
                    },
                    enabled = !busy && !signingOut,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AirvoxPalette.SurfaceHigh,
                        contentColor = AirvoxPalette.White,
                        disabledContainerColor = AirvoxPalette.SurfaceHigh,
                        disabledContentColor = AirvoxPalette.White,
                    ),
                ) {
                    if (signingOut) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = AirvoxPalette.White,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Signing out…", fontWeight = FontWeight.Medium)
                        }
                    } else {
                        Text("Sign out", fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        } else if (!auth.enforced) {
            AirvoxCard {
                Text(
                    text = "Phone, Google, and email sign-in run on the Android app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
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
