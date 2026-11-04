package com.airvox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import kotlinx.coroutines.delay

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
    places: PlaceReporter,
    map: RiderMap,
    auth: AuthClient,
    wallet: CreditWallet = CreditWallet(),
    bindWallet: (String) -> Unit = {},
) {
    var destination by remember { mutableStateOf(Destination.Channel) }
    var plans by remember { mutableStateOf<List<CreditPlan>>(emptyList()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val accessRequired = playback.nowPlaying.value.accessRequired
    val location = places.reading.value
    var locationAnswered by remember { mutableStateOf(false) }
    val authState = auth.state.value
    if (auth.enforced && authState.account == null) {
        if (authState.phase == AuthPhase.Restoring) {
            RestoringScreen()
        } else {
            LoginScreen(auth)
        }
        return
    }
    val signedIn = authState.account
    LaunchedEffect(signedIn?.id, signedIn?.welcomeCredits) {
        val account = signedIn ?: return@LaunchedEffect
        bindWallet(account.id)
        if (account.welcomeCredits > 0) wallet.add(account.welcomeCredits)
    }
    val shownCredits = wallet.state.value.credits
    LaunchedEffect(signedIn?.id, shownCredits) {
        if (signedIn?.id == null) return@LaunchedEffect
        auth.heartbeat(shownCredits)
    }
    if (auth.enforced && authState.account?.profileComplete != true) {
        ProfileScreen(auth)
        return
    }

    // Tells the Airvox API this rider is online, so the admin portal can count them.
    val riderId = authState.account?.id
    LaunchedEffect(riderId) {
        if (riderId == null) return@LaunchedEffect
        while (true) {
            delay(HEARTBEAT_MS)
            auth.heartbeat(wallet.state.value.credits)
        }
    }

    val connection = session.state.value.connection
    LaunchedEffect(connection) {
        if (connection != IntercomConnection.Connected) return@LaunchedEffect
        while (session.state.value.connection == IntercomConnection.Connected) {
            if (!wallet.spendMinute()) {
                vox.leave()
                return@LaunchedEffect
            }
            auth.recordCredits("consume", CREDIT_PER_MINUTE)
            delay(60_000)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
    } else if (!locationAnswered && (!location.allowed || !location.gpsOn)) {
        // Asked once per launch; the Map tab keeps its own button for later.
        val needsPermission = !location.allowed
        AlertDialog(
            onDismissRequest = { locationAnswered = true },
            confirmButton = {
                Button(
                    onClick = {
                        locationAnswered = true
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
                TextButton(onClick = { locationAnswered = true }) {
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
