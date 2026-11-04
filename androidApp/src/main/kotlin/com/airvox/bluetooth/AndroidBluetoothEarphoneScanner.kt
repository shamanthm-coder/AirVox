package com.airvox.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.airvox.intercom.Earphone
import java.util.concurrent.ConcurrentHashMap

class AndroidBluetoothEarphoneScanner(
    private val activity: ComponentActivity,
) : BluetoothEarphoneScanner {
    private val scanState = mutableStateOf(BluetoothScanResult())
    override val scanResult: State<BluetoothScanResult> = scanState

    private val mainHandler = Handler(Looper.getMainLooper())
    private val foundDevices = ConcurrentHashMap<String, Earphone>()
    private var receiverRegistered = false
    private var pendingAction: PendingAction? = null

    private val bluetoothManager: BluetoothManager? =
        activity.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val audioManager: AudioManager? =
        activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private enum class PendingAction {
        Refresh,
        ScanNearby,
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = readDevice(intent) ?: return
                    if (isLikelyEarphone(device)) {
                        addNearbyDevice(device)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    finishNearbyScan("Nearby search finished.")
                }
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                -> refreshAudioOutput()
            }
        }
    }

    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = pendingAction?.let { action ->
                requiredPermissions(action).all { results[it] == true }
            } ?: false
            val action = pendingAction
            pendingAction = null
            if (granted && action != null) {
                when (action) {
                    PendingAction.Refresh -> refreshAudioOutputInternal()
                    PendingAction.ScanNearby -> startNearbyScanInternal()
                }
            } else if (action != null) {
                update(
                    scanState.value.copy(
                        state = BluetoothScanState.Idle,
                        statusMessage = "Allow Nearby devices (Bluetooth) in Settings.",
                    ),
                )
            }
        }

    private val enableBluetoothLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            when (pendingAction) {
                PendingAction.Refresh -> refreshAudioOutputInternal()
                PendingAction.ScanNearby -> startNearbyScanInternal()
                null -> Unit
            }
            pendingAction = null
        }

    init {
        registerConnectionReceiver()
    }

    override fun refreshAudioOutput() {
        if (adapter == null) {
            update(
                BluetoothScanResult(
                    statusMessage = "This device does not support Bluetooth.",
                ),
            )
            return
        }
        if (!hasPermissions(PendingAction.Refresh)) {
            pendingAction = PendingAction.Refresh
            permissionLauncher.launch(requiredPermissions(PendingAction.Refresh))
            return
        }
        if (!adapter.isEnabled) {
            update(
                scanState.value.copy(
                    activeOutput = null,
                    statusMessage = "Turn on Bluetooth. Intercom audio uses your phone's current output.",
                ),
            )
            return
        }
        refreshAudioOutputInternal()
    }

    override fun scanNearby() {
        if (adapter == null) return
        if (!hasPermissions(PendingAction.ScanNearby)) {
            pendingAction = PendingAction.ScanNearby
            permissionLauncher.launch(requiredPermissions(PendingAction.ScanNearby))
            return
        }
        if (!adapter.isEnabled) {
            pendingAction = PendingAction.ScanNearby
            @Suppress("DEPRECATION")
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        startNearbyScanInternal()
    }

    override fun stopScan() {
        cancelDiscoveryIfRunning()
        update(scanState.value.copy(state = BluetoothScanState.Idle))
    }

    fun dispose() {
        stopScan()
        unregisterConnectionReceiver()
    }

    @SuppressLint("MissingPermission")
    private fun refreshAudioOutputInternal() {
        if (!hasPermissions(PendingAction.Refresh)) return
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            update(
                BluetoothScanResult(
                    statusMessage = "Bluetooth is off. Audio will play on the phone speaker.",
                ),
            )
            return
        }

        val connected = linkedSetOf<BluetoothDevice>()
        var profilesPending = 2

        fun publishActiveOutput() {
            profilesPending--
            if (profilesPending > 0) return

            val active = connected.firstOrNull()?.toEarphone()
                ?: readActiveOutputFromAudioManager()

            val message = when {
                active != null ->
                    "Intercom audio goes to this output. You don't need to pick it in Airvox — Android routes sound for you."
                else ->
                    "No Bluetooth headset active. Connect earphones in Settings, or audio plays on the phone speaker."
            }

            update(
                scanState.value.copy(
                    activeOutput = active,
                    statusMessage = message,
                ),
            )
        }

        fetchProfileDevices(bluetoothAdapter, BluetoothProfile.A2DP) {
            connected.addAll(it)
            publishActiveOutput()
        }
        fetchProfileDevices(bluetoothAdapter, BluetoothProfile.HEADSET) {
            connected.addAll(it)
            publishActiveOutput()
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchProfileDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        onResult: (List<BluetoothDevice>) -> Unit,
    ) {
        adapter.getProfileProxy(
            activity,
            object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile?) {
                    val devices = proxy?.connectedDevices ?: emptyList()
                    onResult(devices)
                    proxy?.let { adapter.closeProfileProxy(profileId, it) }
                }

                override fun onServiceDisconnected(profileId: Int) = Unit
            },
            profile,
        )
    }

    @SuppressLint("MissingPermission")
    private fun readActiveOutputFromAudioManager(): Earphone? {
        val manager = audioManager ?: return null
        val types = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
        val device = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type in types }
        val name = device?.productName?.toString()?.takeIf { it.isNotBlank() }
        return name?.let { Earphone(id = "audio-output", name = it) }
    }

    @SuppressLint("MissingPermission")
    private fun startNearbyScanInternal() {
        if (!hasPermissions(PendingAction.ScanNearby)) return
        val bluetoothAdapter = adapter ?: return

        foundDevices.clear()
        bluetoothAdapter.bondedDevices
            .filter { isLikelyEarphone(it) }
            .forEach { addNearbyDevice(it) }

        update(
            scanState.value.copy(
                nearbyOrPaired = foundDevices.values.sortedBy { it.name.lowercase() },
                state = BluetoothScanState.Scanning,
                statusMessage = "Searching for extra devices (for future multi-earphone intercom)…",
            ),
        )

        cancelDiscoveryIfRunning()
        if (!bluetoothAdapter.startDiscovery()) {
            finishNearbyScan("Showing paired audio devices only.")
            return
        }
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    private val scanTimeoutRunnable = Runnable {
        cancelDiscoveryIfRunning()
        finishNearbyScan("Nearby search finished.")
    }

    @SuppressLint("MissingPermission")
    private fun addNearbyDevice(device: BluetoothDevice) {
        val earphone = device.toEarphone()
        foundDevices[earphone.id] = earphone
        update(
            scanState.value.copy(
                nearbyOrPaired = foundDevices.values.sortedBy { it.name.lowercase() },
            ),
        )
    }

    private fun cancelDiscoveryIfRunning() {
        val bluetoothAdapter = adapter ?: return
        if (!hasPermissions(PendingAction.ScanNearby)) return
        try {
            if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()
        } catch (_: SecurityException) {
        }
    }

    private fun finishNearbyScan(message: String) {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        cancelDiscoveryIfRunning()
        update(
            scanState.value.copy(
                state = BluetoothScanState.Idle,
                statusMessage = message,
            ),
        )
    }

    private fun registerConnectionReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(discoveryReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterConnectionReceiver() {
        if (!receiverRegistered) return
        activity.unregisterReceiver(discoveryReceiver)
        receiverRegistered = false
    }

    @SuppressLint("MissingPermission")
    private fun readDevice(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toEarphone(): Earphone {
        val address = address ?: "unknown"
        val name = name?.takeIf { it.isNotBlank() } ?: "Bluetooth device"
        return Earphone(id = address, name = name)
    }

    @SuppressLint("MissingPermission")
    private fun isLikelyEarphone(device: BluetoothDevice): Boolean {
        val major = device.bluetoothClass?.majorDeviceClass
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) return true
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            return major != BluetoothClass.Device.Major.COMPUTER &&
                major != BluetoothClass.Device.Major.PHONE
        }
        return false
    }

    private fun hasPermissions(action: PendingAction): Boolean {
        return requiredPermissions(action).all { permission ->
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(action: PendingAction): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && action == PendingAction.Refresh ->
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                )
            action == PendingAction.Refresh ->
                arrayOf(Manifest.permission.BLUETOOTH)
            else ->
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
        }
    }

    private fun update(result: BluetoothScanResult) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scanState.value = result
        } else {
            mainHandler.post { scanState.value = result }
        }
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 12_000L
    }
}
