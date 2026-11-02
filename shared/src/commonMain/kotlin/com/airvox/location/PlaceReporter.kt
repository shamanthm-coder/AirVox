package com.airvox.location

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

data class PlaceReading(
    val allowed: Boolean = false,
    val gpsOn: Boolean = false,
    val listening: Boolean = false,
    val detail: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * Reads this phone's GPS fix and hands it to the channel.
 * GPS does not need mobile data or Wi-Fi.
 */
interface PlaceReporter {
    val reading: State<PlaceReading>

    fun requestPermission()

    fun openLocationSettings()

    fun refresh()
}

class UnavailablePlaceReporter : PlaceReporter {
    override val reading: State<PlaceReading> = mutableStateOf(
        PlaceReading(detail = "Location is not available on this device."),
    )

    override fun requestPermission() = Unit

    override fun openLocationSettings() = Unit

    override fun refresh() = Unit
}
