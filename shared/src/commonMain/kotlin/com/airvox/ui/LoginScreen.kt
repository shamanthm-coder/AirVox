package com.airvox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airvox.auth.AuthClient
import com.airvox.theme.AirvoxPalette

private val ErrorColor = Color(0xFFFFB4B4)

@Composable
fun RestoringScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AirvoxPalette.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Airvox",
            style = MaterialTheme.typography.headlineLarge,
        )
    }
}

@Composable
fun LoginScreen(auth: AuthClient) {
    val state = auth.state.value
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AirvoxPalette.Black)
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
            .verticalScroll(scroll)
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        SectionLabel("Sign in")
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Airvox",
            style = MaterialTheme.typography.headlineLarge,
            fontSize = 40.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Sign in with Google. Email is there if you need it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))

        if (!state.configured) {
            AirvoxCard {
                Text(
                    text = "Firebase is not connected on this build yet.",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.error
                        ?: "Add the Firebase Android app values, then rebuild.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "In the Firebase console, enable Google and Email/Password. Register the Android app as com.airvox and add its SHA-1.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        PrimaryButton(
            text = if (state.busy) "Opening Google…" else "Continue with Google",
            enabled = !state.busy && state.configured,
            onClick = auth::signInWithGoogle,
        )

        Spacer(Modifier.height(22.dp))
        OrDivider()
        Spacer(Modifier.height(18.dp))

        Text(
            text = "Email",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))
        AuthField(
            value = email,
            onValueChange = { email = it.take(80) },
            placeholder = "Email",
            keyboard = KeyboardType.Email,
            enabled = !state.busy,
        )
        Spacer(Modifier.height(10.dp))
        AuthField(
            value = password,
            onValueChange = { password = it.take(64) },
            placeholder = "Password",
            keyboard = KeyboardType.Password,
            password = true,
            enabled = !state.busy,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { auth.signInWithEmail(email, password) },
            enabled = !state.busy && state.configured,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AirvoxPalette.Line),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = AirvoxPalette.White,
                disabledContentColor = AirvoxPalette.Dim,
            ),
        ) {
            Text("Sign in", fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { auth.createEmailAccount(email, password) },
            enabled = !state.busy && state.configured,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Create account", color = AirvoxPalette.Muted)
        }

        if (state.pendingSession) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = if (state.busy) "Connecting…" else "Connect to Airvox",
                enabled = !state.busy,
                onClick = auth::refreshSession,
            )
        }

        state.error?.let { message ->
            if (!state.configured) return@let
            Spacer(Modifier.height(16.dp))
            Text(text = message, color = ErrorColor, fontSize = 14.sp)
        }
    }
}

@Composable
private fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(AirvoxPalette.Line),
        )
        Text(
            text = "or",
            modifier = Modifier.padding(horizontal = 12.dp),
            color = AirvoxPalette.Dim,
            fontSize = 13.sp,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(AirvoxPalette.Line),
        )
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AirvoxPalette.White,
            contentColor = AirvoxPalette.Black,
            disabledContainerColor = AirvoxPalette.SurfaceHigh,
            disabledContentColor = AirvoxPalette.Dim,
        ),
    ) {
        Text(text, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardType,
    modifier: Modifier = Modifier.fillMaxWidth(),
    password: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(placeholder) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
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
