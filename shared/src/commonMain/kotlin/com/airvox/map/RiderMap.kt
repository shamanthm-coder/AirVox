package com.airvox.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.airvox.intercom.ChannelPlace

data class RiderMapState(
    val following: Boolean = true,
    val saving: Boolean = false,
    val progress: Float = 0f,
    val savedAreas: Int = 0,
    val detail: String? = null,
)

/**
 * Street map under the riders. The map data is OpenStreetMap. Areas saved
 * while there is internet still show on a ride with no internet.
 */
interface RiderMap {
    val available: Boolean

    /** The phone can download the streets for the area on screen and show them with no internet. */
    val savesOffline: Boolean get() = true

    val state: State<RiderMapState>

    @Composable
    fun Show(self: ChannelPlace?, others: List<ChannelPlace>, modifier: Modifier)

    fun recenter()

    fun saveVisibleArea()

    fun deleteSavedAreas()
}

class UnavailableRiderMap : RiderMap {
    override val available: Boolean = false

    override val state: State<RiderMapState> = mutableStateOf(RiderMapState())

    @Composable
    override fun Show(self: ChannelPlace?, others: List<ChannelPlace>, modifier: Modifier) = Unit

    override fun recenter() = Unit

    override fun saveVisibleArea() = Unit

    override fun deleteSavedAreas() = Unit
}
