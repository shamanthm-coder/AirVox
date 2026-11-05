package com.airvox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airvox.intercom.ChannelPlace
import com.airvox.intercom.IntercomConnection
import com.airvox.intercom.IntercomSession
import com.airvox.location.PlaceReading
import com.airvox.location.PlaceReporter
import com.airvox.map.RiderMap
import com.airvox.theme.AirvoxPalette
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun MapScreen(
    session: IntercomSession,
    reporter: PlaceReporter,
    map: RiderMap,
    riderName: String = "",
) {
    val intercom = session.state.value
    val reading = reporter.reading.value
    val mapState = map.state.value
    // The channel list is preferred. The GPS reading covers this phone before a channel echoes it back.
    val self = intercom.places.firstOrNull { it.self } ?: reading.selfPlace(riderName)
    val others = intercom.places.filter { !it.self }
    val places = listOfNotNull(self) + others
    val onChannel = intercom.connection == IntercomConnection.Connecting ||
        intercom.connection == IntercomConnection.Connected
    var expanded by remember { mutableStateOf(false) }

    if (expanded && map.available) {
        Box(modifier = Modifier.fillMaxSize()) {
            map.Show(self = self, others = others, modifier = Modifier.fillMaxSize())
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MapIconButton(description = "Recenter", onClick = map::recenter) {
                    RecenterIcon(tint = AirvoxPalette.White)
                }
                MapIconButton(description = "Leave full screen", onClick = { expanded = false }) {
                    FullScreenIcon(exit = true, tint = AirvoxPalette.White)
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 12.dp),
    ) {
        SectionLabel("Map")
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Where you are",
            style = MaterialTheme.typography.headlineLarge,
            fontSize = 32.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "GPS on this phone, shared with the channel. It keeps working when there is no internet.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(22.dp))
        AirvoxCard {
            if (map.available) {
                map.Show(
                    self = self,
                    others = others,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    MapIconButton(description = "Recenter", onClick = map::recenter) {
                        RecenterIcon(tint = AirvoxPalette.White)
                    }
                    MapIconButton(description = "Full screen", onClick = { expanded = true }) {
                        FullScreenIcon(exit = false, tint = AirvoxPalette.White)
                    }
                }
            } else {
                PlaceMap(self = self, others = others)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = reading.detail ?: "Waiting for GPS.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!reading.allowed || !reading.gpsOn) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        if (!reading.allowed) reporter.requestPermission() else reporter.openLocationSettings()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AirvoxPalette.Line),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AirvoxPalette.White),
                ) {
                    Text(
                        text = if (!reading.allowed) "Allow location" else "Turn on location",
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        if (map.available && map.savesOffline) {
            Spacer(Modifier.height(16.dp))
            AirvoxCard {
                Text(text = "Offline map", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        mapState.saving -> "Saving this area. ${(mapState.progress * 100).roundToInt()}%"
                        mapState.detail != null -> mapState.detail
                        mapState.savedAreas == 0 ->
                            "Move the map to your ride area and save it while you have internet. It then shows with no internet."
                        mapState.savedAreas == 1 -> "1 area saved. It shows with no internet."
                        else -> "${mapState.savedAreas} areas saved. They show with no internet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (mapState.saving) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { mapState.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = AirvoxPalette.White,
                        trackColor = AirvoxPalette.Line,
                    )
                } else {
                    Spacer(Modifier.height(12.dp))
                    MapButton(text = "Save this area", onClick = map::saveVisibleArea)
                    if (mapState.savedAreas > 0) {
                        Spacer(Modifier.height(10.dp))
                        MapButton(text = "Delete saved maps", onClick = map::deleteSavedAreas)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        AirvoxCard {
            Text(
                text = when {
                    !onChannel -> "Not on a channel"
                    others.isEmpty() -> "No other phone yet"
                    else -> "${others.size + 1} on the map"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    !onChannel -> "Join a channel to see the other phones. Your own GPS fix still shows here."
                    others.isEmpty() -> "The other phones share their GPS over this channel, including Bluetooth and hotspot."
                    else -> "Distances are from you."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (places.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                places.forEach { place ->
                    PlaceRow(place = place, self = self)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun MapIconButton(
    description: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = description
            }
            .clip(CircleShape)
            .background(AirvoxPalette.SurfaceHigh)
            .border(width = 1.dp, color = AirvoxPalette.Line, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { icon() },
    )
}

@Composable
private fun MapButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AirvoxPalette.Line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AirvoxPalette.White),
    ) {
        Text(text = text, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PlaceMap(
    self: ChannelPlace?,
    others: List<ChannelPlace>,
) {
    val ring = ringMeters(self, others)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp),
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.38f
        drawCircle(
            color = AirvoxPalette.Line,
            radius = radius,
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCircle(
            color = AirvoxPalette.Line,
            radius = radius / 2f,
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
        if (self == null) return@Canvas
        val metersPerPixel = ring / radius
        others.forEach { place ->
            val east = eastMeters(self, place)
            val north = northMeters(self, place)
            val point = Offset(
                x = center.x + (east / metersPerPixel).toFloat(),
                y = center.y - (north / metersPerPixel).toFloat(),
            )
            drawCircle(color = AirvoxPalette.White, radius = 6.dp.toPx(), center = point)
        }
        drawCircle(color = AirvoxPalette.White, radius = 7.dp.toPx(), center = center)
        drawCircle(color = AirvoxPalette.Black, radius = 3.dp.toPx(), center = center)
    }
    Text(
        text = "Outer ring ${formatDistance(ring)}",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun PlaceRow(place: ChannelPlace, self: ChannelPlace?) {
    val distance = if (self == null || place.self) {
        if (place.self) "This phone" else ""
    } else {
        formatDistance(metersBetween(self, place))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = place.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${formatCoordinate(place.latitude)}, ${formatCoordinate(place.longitude)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (distance.isNotEmpty()) {
            Spacer(Modifier.size(12.dp))
            Text(text = distance, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun PlaceReading.selfPlace(riderName: String): ChannelPlace? {
    val lat = latitude ?: return null
    val lng = longitude ?: return null
    val label = riderName.trim().ifBlank { "You" }
    return ChannelPlace(id = "self", name = label, latitude = lat, longitude = lng, self = true)
}

private fun ringMeters(self: ChannelPlace?, others: List<ChannelPlace>): Double {
    val farthest = if (self == null) 100.0 else {
        others.maxOfOrNull { metersBetween(self, it) } ?: 100.0
    }
    val steps = doubleArrayOf(50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0, 25000.0)
    return steps.firstOrNull { it >= farthest.coerceAtLeast(40.0) } ?: farthest
}

private fun metersBetween(from: ChannelPlace, to: ChannelPlace): Double {
    val earth = 6_371_000.0
    val lat1 = kotlin.math.PI * from.latitude / 180.0
    val lat2 = kotlin.math.PI * to.latitude / 180.0
    val dLat = lat2 - lat1
    val dLng = kotlin.math.PI * (to.longitude - from.longitude) / 180.0
    val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
    return 2 * earth * asin(sqrt(a))
}

private fun northMeters(self: ChannelPlace, place: ChannelPlace): Double {
    return kotlin.math.PI * (place.latitude - self.latitude) / 180.0 * 6_371_000.0
}

private fun eastMeters(self: ChannelPlace, place: ChannelPlace): Double {
    val mid = kotlin.math.PI * ((self.latitude + place.latitude) / 2.0) / 180.0
    return kotlin.math.PI * (place.longitude - self.longitude) / 180.0 * 6_371_000.0 * cos(mid)
}

private fun formatCoordinate(value: Double): String {
    val scaled = abs(value * 100_000).roundToLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return "$sign${scaled / 100_000}.${(scaled % 100_000).toString().padStart(5, '0')}"
}

private fun formatDistance(meters: Double): String {
    return if (meters < 1000) {
        "${meters.roundToInt()} m"
    } else {
        val km = meters / 1000.0
        val rounded = (km * 10).roundToInt() / 10.0
        "$rounded km"
    }
}
