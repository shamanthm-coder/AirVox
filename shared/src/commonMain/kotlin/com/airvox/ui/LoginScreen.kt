package com.airvox.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.airvox.generated.resources.Res
import com.airvox.generated.resources.airvox_logo
import org.jetbrains.compose.resources.painterResource
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
fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AirvoxPalette.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(Res.drawable.airvox_logo),
                contentDescription = "Airvox",
                modifier = Modifier.size(96.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Airvox",
                style = MaterialTheme.typography.headlineLarge,
                fontSize = 40.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Riders, on the same channel.",
                color = AirvoxPalette.Muted,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(28.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = AirvoxPalette.White,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
fun LoginScreen(auth: AuthClient, onCreateAccount: () -> Unit) {
    val state = auth.state.value
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AuthForm {
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
            onClick = onCreateAccount,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Create account", color = AirvoxPalette.White)
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
fun CreateAccountScreen(auth: AuthClient, onSignIn: () -> Unit) {
    val state = auth.state.value
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }

    AuthForm {
        SectionLabel("Create account")
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Airvox",
            style = MaterialTheme.typography.headlineLarge,
            fontSize = 40.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Add your name, email, and a password of at least 6 characters.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        AuthField(
            value = name,
            onValueChange = { name = it.take(40) },
            placeholder = "Name",
            keyboard = KeyboardType.Text,
            enabled = !state.busy,
        )
        Spacer(Modifier.height(10.dp))
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
            placeholder = "New password",
            keyboard = KeyboardType.Password,
            password = true,
            enabled = !state.busy,
        )
        Spacer(Modifier.height(10.dp))
        AuthField(
            value = confirm,
            onValueChange = { confirm = it.take(64) },
            placeholder = "Enter the password",
            keyboard = KeyboardType.Password,
            password = true,
            enabled = !state.busy,
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton(
            text = if (state.busy) "Creating account…" else "Create account",
            loading = state.busy,
            enabled = !state.busy && state.configured,
            onClick = {
                auth.clearError()
                val trimmedName = name.trim()
                formError = when {
                    trimmedName.isEmpty() || trimmedName.length > 40 -> "Use a name of 1 to 40 characters."
                    password.length < 6 -> "Use a password of at least 6 characters."
                    password != confirm -> "Passwords do not match."
                    else -> null
                }
                if (formError == null) auth.createEmailAccount(email, password, trimmedName)
            },
        )
        formError?.let { message ->
            Spacer(Modifier.height(16.dp))
            Text(text = message, color = ErrorColor, fontSize = 14.sp)
        }
        state.error?.let { message ->
            Spacer(Modifier.height(16.dp))
            Text(text = message, color = ErrorColor, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onSignIn,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Already have an account? Sign in", color = AirvoxPalette.Muted)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AuthForm(content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    // Do not follow the keyboard inset. On iPhone that inset changes every frame
    // of the keyboard animation and the field then waits several seconds to open.
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
            content()
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
    loading: Boolean = false,
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
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = AirvoxPalette.Dim,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.size(10.dp))
        }
        Text(text, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardType,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    enabled: Boolean = true,
) {
    var revealed by remember { mutableStateOf(false) }
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
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth(),
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
