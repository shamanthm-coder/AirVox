package com.airvox.bluetooth

import androidx.compose.runtime.State
import com.airvox.intercom.Earphone

enum class BluetoothScanState {
    Idle,
    Scanning,
}

data class BluetoothScanResult(
    /** Earphone (or headset) Android is routing audio to right now. */
    val activeOutput: Earphone? = null,
    /** Paired / discovered devices — optional, for multi-device intercom later. */
    val nearbyOrPaired: List<Earphone> = emptyList(),
    val state: BluetoothScanState = BluetoothScanState.Idle,
    val statusMessage: String? = null,
)

interface BluetoothEarphoneScanner {
    val scanResult: State<BluetoothScanResult>

    /** Reads the phone's current Bluetooth audio output (no discovery scan). */
    fun refreshAudioOutput()

    /** Optional: discover more paired / nearby devices for future multi-user intercom. */
    fun scanNearby()

    fun stopScan()
}
