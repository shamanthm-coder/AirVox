package com.airvox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogProperties
import com.airvox.auth.AuthClient
import com.airvox.auth.AuthPhase
import com.airvox.bluetooth.BluetoothEarphoneScanner
import com.airvox.credits.CREDIT_PER_MINUTE
import com.airvox.credits.CreditPlan
import com.airvox.credits.CreditWallet
import com.airvox.intercom.IntercomConnection
import com.airvox.intercom.IntercomSession
import com.airvox.location.PlaceReporter
import com.airvox.map.RiderMap
import com.airvox.playback.PlaybackController
import com.airvox.theme.AirvoxPalette
import com.airvox.vox.Vox
import com.airvox.voxy.Voxy
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

// The portal counts a rider as online for 5 minutes after a check-in.
private const val HEARTBEAT_MS = 120_000L

private enum class Destination(val label: String) {
    Channel("Channel"),
    Map("Map"),
    Plans("Plans"),
    Settings("Settings"),
}

@Composable
fun AirvoxApp(
    session: IntercomSession,
    playback: PlaybackController,
    bluetoothScanner: BluetoothEarphoneScanner,
    vox: Vox,
    voxy: Voxy,
    places: PlaceReporter,
    map: RiderMap,
    auth: AuthClient,
    wallet: CreditWallet = CreditWallet(),
    bindWallet: (String) -> Unit = {},
    offsetAlreadyRemoved: (String, Int) -> Boolean = { _, _ -> false },
    markOffsetRemoved: (String, Int) -> Unit = { _, _ -> },
) {
    val signedInId = auth.state.value.account?.id
    var destination by remember(signedInId) { mutableStateOf(Destination.Channel) }
    var plans by remember { mutableStateOf<List<CreditPlan>>(emptyList()) }
    val snackbarHostState = remember { SnackbarHostState() }
    var locationAnswered by remember { mutableStateOf(false) }
    val authState = auth.state.value
    var creatingAccount by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(true) }
    var holdingRestore by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1600.milliseconds)
        opening = false
        // A saved sign-in talks to the server in the background. Do not leave the
        // splash up if that call is slow or the phone cannot reach the server.
        delay(2000.milliseconds)
        holdingRestore = false
    }
    // Create account is only a choice on the signed-out screens. Leaving it set
    // sent Sign out back there instead of the sign-in screen.
    LaunchedEffect(authState.account?.id) {
        if (authState.account == null) creatingAccount = false
    }
    val restoring = holdingRestore && auth.enforced && authState.account == null && authState.phase == AuthPhase.Restoring
    if (opening || restoring) {
        SplashScreen()
        return
    }
    if (auth.enforced && authState.account == null) {
        if (creatingAccount) {
            CreateAccountScreen(
                auth = auth,
                onSignIn = {
                    auth.clearError()
                    creatingAccount = false
                },
            )
        } else {
            LoginScreen(
                auth = auth,
                onCreateAccount = {
                    auth.clearError()
                    creatingAccount = true
                },
            )
        }
        return
    }
    val signedIn = authState.account
    var walletReady by remember { mutableStateOf<String?>(null) }
    var clearOffset by remember { mutableStateOf(false) }
    LaunchedEffect(signedIn?.id, signedIn?.welcomeCredits, signedIn?.balanceOffset) {
        val account = signedIn
        if (account == null) {
            walletReady = null
            return@LaunchedEffect
        }
        bindWallet(account.id)
        if (account.welcomeCredits > 0) wallet.add(account.welcomeCredits)
        if (account.balanceOffset > 0) {
            if (!offsetAlreadyRemoved(account.id, account.balanceOffset)) {
                wallet.remove(account.balanceOffset)
                markOffsetRemoved(account.id, account.balanceOffset)
            }
            clearOffset = true
        }
        walletReady = account.id
    }
    val shownCredits = wallet.state.value.credits
    LaunchedEffect(signedIn?.id, shownCredits, walletReady) {
        if (signedIn?.id == null || walletReady != signedIn.id) return@LaunchedEffect
        val drop = clearOffset
        if (drop) clearOffset = false
        val live = session.state.value
        val onChannel = live.connection == IntercomConnection.Connected ||
            live.connection == IntercomConnection.Connecting
        auth.heartbeat(
            shownCredits,
            if (onChannel) live.roomCode else "",
            if (onChannel) live.path.label else "",
            clearOffset = drop,
        )
    }
    if (auth.enforced && authState.needsProfile && authState.account?.profileComplete != true) {
        ProfileScreen(auth)
        return
    }

    // Tells the Airvox API this rider is online, so the admin portal can count them.
    val riderId = authState.account?.id
    val riderName = authState.account?.displayName?.trim().orEmpty()
    LaunchedEffect(riderId, riderName) {
        if (riderName.isBlank()) {
            voxy.stop()
            return@LaunchedEffect
        }
        voxy.start(riderName)
        try {
            awaitCancellation()
        } finally {
            voxy.stop()
        }
    }
    LaunchedEffect(riderId) {
        if (riderId == null) return@LaunchedEffect
        while (true) {
            delay(HEARTBEAT_MS.milliseconds)
            val live = session.state.value
            val onChannel = live.connection == IntercomConnection.Connected ||
                live.connection == IntercomConnection.Connecting
            auth.heartbeat(
                wallet.state.value.credits,
                if (onChannel) live.roomCode else "",
                if (onChannel) live.path.label else "",
            )
        }
    }

    val connection = session.state.value.connection
    val liveRoom = session.state.value.roomCode
    val livePath = session.state.value.path
    var openChannel by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(connection, liveRoom, livePath) {
        if ((connection == IntercomConnection.Connected || connection == IntercomConnection.Connecting) && liveRoom.isNotBlank()) {
            auth.reportChannel(liveRoom, livePath.label, "join")
            openChannel = liveRoom to livePath.label
        } else if (openChannel != null && connection != IntercomConnection.Connected && connection != IntercomConnection.Connecting) {
            val (room, mode) = openChannel ?: return@LaunchedEffect
            auth.reportChannel(room, mode, "leave")
            openChannel = null
        }
    }
    LaunchedEffect(connection) {
        if (connection != IntercomConnection.Connected) return@LaunchedEffect
        while (session.state.value.connection == IntercomConnection.Connected) {
            if (!wallet.spendMinute()) {
                vox.leave()
                return@LaunchedEffect
            }
            auth.recordCredits("consume", CREDIT_PER_MINUTE)
            delay(60_000.milliseconds)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Leave the keyboard out of the content padding. Including it resizes the
        // screen on every frame of the keyboard animation and the field stays stuck.
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = AirvoxPalette.Surface) {
                Destination.entries.forEach { item ->
                    val selected = destination == item
                    val tint = if (selected) AirvoxPalette.Black else AirvoxPalette.Muted
                    NavigationBarItem(
                        selected = selected,
                        onClick = { destination = item },
                        icon = {
                            when (item) {
                                Destination.Channel -> ChannelIcon(tint)
                                Destination.Map -> MapIcon(tint)
                                Destination.Plans -> WalletIcon(tint)
                                Destination.Settings -> SettingsIcon(tint)
                            }
                        },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AirvoxPalette.Black,
                            selectedTextColor = AirvoxPalette.White,
                            unselectedIconColor = AirvoxPalette.Muted,
                            unselectedTextColor = AirvoxPalette.Muted,
                            indicatorColor = AirvoxPalette.White,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (destination) {
                Destination.Channel -> ChannelScreen(
                    session = session,
                    vox = vox,
                    voxy = voxy,
                    playback = playback,
                    bluetoothScanner = bluetoothScanner,
                    credits = wallet.state.value.credits,
                    onOpenPlans = { destination = Destination.Plans },
                )
                Destination.Map -> MapScreen(
                    session = session,
                    reporter = places,
                    map = map,
                )
                Destination.Plans -> PlansScreen(
                    account = auth.state.value.account,
                    balance = wallet.state.value,
                    plans = plans,
                    onRefresh = { auth.loadPlans { plans = it } },
                    onBuy = { pack ->
                        wallet.add(pack)
                        auth.recordCredits("purchase", pack)
                    },
                )
                Destination.Settings -> SettingsScreen(auth)
            }
        }
    }

    PhonePrompts(
        playback = playback,
        places = places,
        askForLocation = !locationAnswered,
        onLocationAnswered = { locationAnswered = true },
    )
}

@Composable
private fun PhonePrompts(
    playback: PlaybackController,
    places: PlaceReporter,
    askForLocation: Boolean,
    onLocationAnswered: () -> Unit,
) {
    val accessRequired = playback.nowPlaying.value.accessRequired
    val location = places.reading.value
    if (accessRequired) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                Button(
                    onClick = playback::openAccessSettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AirvoxPalette.White,
                        contentColor = AirvoxPalette.Black,
                    ),
                ) {
                    Text("Allow access", fontWeight = FontWeight.Medium)
                }
            },
            title = { Text("Notification access required") },
            text = {
                Text(
                    "Airvox needs notification access to see what is playing and show Pause, Play, and skip. This step is required. Airvox does not read your messages.",
                )
            },
            containerColor = AirvoxPalette.Surface,
            titleContentColor = AirvoxPalette.White,
            textContentColor = AirvoxPalette.Muted,
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        )
    } else if (askForLocation && (!location.allowed || !location.gpsOn)) {
        val needsPermission = !location.allowed
        AlertDialog(
            onDismissRequest = onLocationAnswered,
            confirmButton = {
                Button(
                    onClick = {
                        onLocationAnswered()
                        if (needsPermission) places.requestPermission() else places.openLocationSettings()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AirvoxPalette.White,
                        contentColor = AirvoxPalette.Black,
                    ),
                ) {
                    Text(
                        text = if (needsPermission) "Allow location" else "Turn on location",
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onLocationAnswered) {
                    Text("Not now", color = AirvoxPalette.Muted)
                }
            },
            title = { Text(if (needsPermission) "Share your location" else "Location is off") },
            text = {
                Text(
                    if (needsPermission) {
                        "Airvox shows where you are on the map and shares it with the riders on your channel, so you can find each other. GPS works without internet."
                    } else {
                        "Turn on location on this phone so the riders on your channel can see where you are. GPS works without internet."
                    },
                )
            },
            containerColor = AirvoxPalette.Surface,
            titleContentColor = AirvoxPalette.White,
            textContentColor = AirvoxPalette.Muted,
        )
    }
}
