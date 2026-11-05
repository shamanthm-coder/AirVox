package com.airvox.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airvox.auth.AuthClient
import com.airvox.theme.AirvoxPalette

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileScreen(auth: AuthClient) {
    val state = auth.state.value
    val account = state.account
    var name by remember(account?.id) { mutableStateOf(account?.displayName.orEmpty()) }
    var country by remember { mutableStateOf("+91") }
    var national by remember(account?.id) {
        mutableStateOf(account?.phoneNumber?.removePrefix("+91").orEmpty())
    }
    val scroll = rememberScrollState()
    var signingOut by remember { mutableStateOf(false) }
    val showKeyboardNow = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides showKeyboardNow) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AirvoxPalette.Black)
            .windowInsetsPadding(
                WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
            .verticalScroll(scroll)
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        SectionLabel("Profile")
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your details",
            style = MaterialTheme.typography.headlineLarge,
            fontSize = 36.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Add your name and mobile number. Airvox opens after this is saved.",
            style = MaterialTheme.typography.bodyMedium,
        )
        account?.email?.let { email ->
            Spacer(Modifier.height(8.dp))
            Text(text = email, color = AirvoxPalette.Dim, fontSize = 14.sp)
        }
        Spacer(Modifier.height(24.dp))
        AirvoxCard {
            ProfileField(
                value = name,
                onValueChange = { name = it.take(40) },
                placeholder = "Name",
                keyboard = KeyboardType.Text,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileField(
                    value = country,
                    onValueChange = { country = it.take(4) },
                    placeholder = "+91",
                    keyboard = KeyboardType.Phone,
                    enabled = !state.busy,
                    modifier = Modifier.width(96.dp),
                )
                Spacer(Modifier.width(10.dp))
                ProfileField(
                    value = national,
                    onValueChange = { national = it.filter(Char::isDigit).take(14) },
                    placeholder = "Mobile number",
                    keyboard = KeyboardType.Phone,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { auth.saveProfile(name, country.trim() + national) },
                enabled = !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AirvoxPalette.White,
                    contentColor = AirvoxPalette.Black,
                    disabledContainerColor = AirvoxPalette.SurfaceHigh,
                    disabledContentColor = AirvoxPalette.Dim,
                ),
            ) {
                Text(
                    text = if (state.busy) "Saving…" else "Save and continue",
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        state.error?.let { message ->
            Spacer(Modifier.height(16.dp))
            Text(text = message, color = Color(0xFFFFB4B4), fontSize = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = {
                signingOut = true
                auth.signOut()
            },
            enabled = !state.busy && !signingOut,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (signingOut) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = AirvoxPalette.Muted,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Signing out…", color = AirvoxPalette.Muted)
                }
            } else {
                Text("Sign out", color = AirvoxPalette.Muted)
            }
        }
    }
    }
}

@Composable
private fun ProfileField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardType,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(placeholder) },
        visualTransformation = VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = AirvoxPalette.White,
            unfocusedTextColor = AirvoxPalette.White,
            disabledTextColor = AirvoxPalette.Muted,
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
