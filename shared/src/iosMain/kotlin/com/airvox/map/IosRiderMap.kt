package com.airvox.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.airvox.intercom.ChannelPlace
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.MapKit.MKAnnotationProtocol
import platform.MapKit.MKAnnotationView
import platform.MapKit.MKCoordinateRegionMake
import platform.MapKit.MKCoordinateRegionMakeWithDistance
import platform.MapKit.MKCoordinateSpanMake
import platform.MapKit.MKFeatureVisibility
import platform.MapKit.MKMapView
import platform.MapKit.MKMapViewDelegateProtocol
import platform.MapKit.MKMarkerAnnotationView
import platform.MapKit.MKPointAnnotation
import platform.UIKit.UIColor
import platform.UIKit.UIUserInterfaceStyle
import platform.darwin.NSObject

/**
 * Apple Maps for the iPhone. Android uses MapLibre; this screen was left on the
 * ring drawing because no iPhone map was connected.
 */
@OptIn(ExperimentalForeignApi::class)
class IosRiderMap : RiderMap {
    override val available: Boolean = true
    override val savesOffline: Boolean = false

    private val stateHolder = mutableStateOf(RiderMapState())
    override val state: State<RiderMapState> = stateHolder

    private var mapView: MKMapView? = null
    private var riders: List<ChannelPlace> = emptyList()
    private var shownKey = ""
    private var framed = false
    private var ignoreMovesUntil = TimeSource.Monotonic.markNow()

    private val delegate = object : NSObject(), MKMapViewDelegateProtocol {
        override fun mapView(mapView: MKMapView, regionDidChangeAnimated: Boolean) {
            if (ignoreMovesUntil.hasNotPassedNow()) return
            if (stateHolder.value.following) {
                publish(stateHolder.value.copy(following = false))
            }
        }

        override fun mapView(mapView: MKMapView, viewForAnnotation: MKAnnotationProtocol): MKAnnotationView? {
            if (viewForAnnotation is MKPointAnnotation) {
                val marker = mapView.dequeueReusableAnnotationViewWithIdentifier(PIN) as? MKMarkerAnnotationView
                    ?: MKMarkerAnnotationView(annotation = viewForAnnotation, reuseIdentifier = PIN)
                marker.annotation = viewForAnnotation
                val selfPin = viewForAnnotation.subtitle == SELF
                marker.markerTintColor = if (selfPin) {
                    UIColor.whiteColor
                } else {
                    UIColor.colorWithRed(0.29, green = 0.62, blue = 0.95, alpha = 1.0)
                }
                marker.glyphTintColor = if (selfPin) UIColor.blackColor else UIColor.whiteColor
                marker.titleVisibility = MKFeatureVisibility.MKFeatureVisibilityVisible
                marker.subtitleVisibility = MKFeatureVisibility.MKFeatureVisibilityHidden
                return marker
            }
            return null
        }
    }

    @Composable
    override fun Show(self: ChannelPlace?, others: List<ChannelPlace>, modifier: Modifier) {
        // One map for the life of the screen. A new view on each tab visit starts
        // over India and drops the pins.
        val view = mapView ?: createMap()
        UIKitView(
            factory = { view },
            modifier = modifier,
            update = { showRiders(listOfNotNull(self) + others) },
            properties = UIKitInteropProperties(
                interactionMode = UIKitInteropInteractionMode.NonCooperative,
            ),
        )
    }

    override fun recenter() {
        publish(stateHolder.value.copy(following = true))
        framed = false
        mapView?.let { frame(it, animate = true) }
    }

    override fun saveVisibleArea() = Unit

    override fun deleteSavedAreas() = Unit

    private fun createMap(): MKMapView {
        val view = MKMapView()
        view.delegate = delegate
        view.overrideUserInterfaceStyle = UIUserInterfaceStyle.UIUserInterfaceStyleDark
        view.rotateEnabled = false
        view.pitchEnabled = false
        view.showsCompass = false
        view.backgroundColor = UIColor.blackColor
        mapView = view
        return view
    }

    private fun showRiders(places: List<ChannelPlace>) {
        riders = places
        val view = mapView ?: return
        val key = places.joinToString(separator = "|") { place ->
            "${place.id}:${place.latitude}:${place.longitude}:${place.name}:${place.self}"
        }
        val pinsMissing = places.isNotEmpty() && view.annotations.isEmpty()
        if (key != shownKey || pinsMissing) {
            shownKey = key
            view.removeAnnotations(view.annotations)
            places.forEach { place ->
                val pin = MKPointAnnotation()
                pin.setCoordinate(CLLocationCoordinate2DMake(place.latitude, place.longitude))
                pin.setTitle(place.name)
                pin.setSubtitle(if (place.self) SELF else OTHER)
                view.addAnnotation(pin)
            }
        }
        if (stateHolder.value.following) frame(view, animate = framed)
    }

    private fun frame(view: MKMapView, animate: Boolean) {
        if (riders.isEmpty()) return
        val wideEnough = view.bounds.useContents { size.width >= 20.0 && size.height >= 20.0 }
        if (!wideEnough) return
        ignoreMovesUntil = TimeSource.Monotonic.markNow() + 1500.milliseconds
        val region = if (riders.size == 1) {
            val place = riders.first()
            MKCoordinateRegionMakeWithDistance(
                CLLocationCoordinate2DMake(place.latitude, place.longitude),
                SINGLE_METERS,
                SINGLE_METERS,
            )
        } else {
            val minLat = riders.minOf { it.latitude }
            val maxLat = riders.maxOf { it.latitude }
            val minLng = riders.minOf { it.longitude }
            val maxLng = riders.maxOf { it.longitude }
            val latPad = (maxLat - minLat).coerceAtLeast(0.004) * 0.35
            val lngPad = (maxLng - minLng).coerceAtLeast(0.004) * 0.35
            MKCoordinateRegionMake(
                CLLocationCoordinate2DMake((minLat + maxLat) / 2.0, (minLng + maxLng) / 2.0),
                MKCoordinateSpanMake((maxLat - minLat) + latPad * 2, (maxLng - minLng) + lngPad * 2),
            )
        }
        view.setRegion(region, animated = animate)
        framed = true
    }

    private fun publish(next: RiderMapState) {
        stateHolder.value = next
    }

    private companion object {
        const val PIN = "airvox-rider"
        const val SELF = "self"
        const val OTHER = "other"
        const val SINGLE_METERS = 800.0
    }
}
