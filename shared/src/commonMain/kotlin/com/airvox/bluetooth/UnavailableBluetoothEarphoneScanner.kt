package com.airvox.bluetooth

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

class UnavailableBluetoothEarphoneScanner : BluetoothEarphoneScanner {
    override val scanResult: State<BluetoothScanResult> = mutableStateOf(
        BluetoothScanResult(
            statusMessage = "Bluetooth audio status is available on Android.",
        ),
    )

    override fun refreshAudioOutput() = Unit

    override fun scanNearby() = Unit

    override fun stopScan() = Unit
}
