package com.airvox.map

import androidx.activity.ComponentActivity
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.airvox.intercom.ChannelPlace
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * MapLibre with OpenStreetMap data from OpenFreeMap. Rider positions come from
 * the channel, so they move with no internet. Streets need internet the first
 * time, unless the area was saved with [saveVisibleArea].
 */
class AndroidRiderMap(
    private val activity: ComponentActivity,
) : RiderMap {
    override val available: Boolean = true

    private val stateHolder = mutableStateOf(RiderMapState())
    override val state: State<RiderMapState> = stateHolder

    private val offlineManager: OfflineManager
    private var mapView: MapView? = null
    private var mapCreated = false
    private var map: MapLibreMap? = null
    private var riders: List<ChannelPlace> = emptyList()
    private var saving: OfflineRegion? = null

    // A new map starts at the whole world; jump to the riders instead of flying there.
    private var framed = false

    init {
        MapLibre.getInstance(activity)
        offlineManager = OfflineManager.getInstance(activity)
        offlineManager.setOfflineMapboxTileCountLimit(TILE_LIMIT)
        countSavedAreas()
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                mapView?.onDestroy()
                mapView = null
                map = null
                mapCreated = false
            }
        })
    }

    @Composable
    override fun Show(self: ChannelPlace?, others: List<ChannelPlace>, modifier: Modifier) {
        val view = mapView ?: createMapView().also { mapView = it }
        DisposableEffect(view) {
            val lifecycle = activity.lifecycle
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_CREATE -> ensureCreated(view)
                    Lifecycle.Event.ON_START -> view.onStart()
                    Lifecycle.Event.ON_RESUME -> view.onResume()
                    Lifecycle.Event.ON_PAUSE -> view.onPause()
                    Lifecycle.Event.ON_STOP -> view.onStop()
                    else -> Unit
                }
            }
            // Replays ON_CREATE up to the current state, so the view catches up.
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                // Leaving the Map tab pauses the map. Destroying it would drop the
                // camera and the pins the next time the tab opens.
                if (lifecycle.currentState == Lifecycle.State.DESTROYED) return@onDispose
                val current = lifecycle.currentState
                if (current.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
                if (current.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            }
        }
        AndroidView(
            factory = {
                (view.parent as? ViewGroup)?.removeView(view)
                view
            },
            modifier = modifier,
        )
        LaunchedEffect(self, others) {
            showRiders(listOfNotNull(self) + others)
        }
    }

    override fun recenter() {
        publish(stateHolder.value.copy(following = true))
        map?.let(::frame)
    }

    override fun saveVisibleArea() {
        if (saving != null) return
        val current = map ?: run {
            publish(stateHolder.value.copy(detail = "The map is still loading. Try again in a moment."))
            return
        }
        val bounds = current.projection.visibleRegion.latLngBounds
        if (tileCount(bounds, MIN_SAVE_ZOOM.toInt(), MAX_SAVE_ZOOM.toInt()) > TILE_LIMIT) {
            publish(stateHolder.value.copy(detail = "This area is too big to save. Zoom in and try again."))
            return
        }
        val definition = OfflineTilePyramidRegionDefinition(
            STYLE_URL,
            bounds,
            MIN_SAVE_ZOOM,
            MAX_SAVE_ZOOM,
            activity.resources.displayMetrics.density,
        )
        publish(stateHolder.value.copy(saving = true, progress = 0f, detail = null))
        offlineManager.createOfflineRegion(
            definition,
            ByteArray(0),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    saving = offlineRegion
                    offlineRegion.setObserver(saveObserver(offlineRegion))
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    publish(stateHolder.value.copy(saving = false, detail = "Could not save this area. $error"))
                }
            },
        )
    }

    override fun deleteSavedAreas() {
        saving?.setDownloadState(OfflineRegion.STATE_INACTIVE)
        saving = null
        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = offlineRegions.orEmpty()
                if (regions.isEmpty()) {
                    publish(stateHolder.value.copy(saving = false, savedAreas = 0, detail = null))
                    return
                }
                var left = regions.size
                regions.forEach { region ->
                    region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() = done()

                        override fun onError(error: String) = done()

                        private fun done() {
                            left -= 1
                            if (left == 0) {
                                publish(stateHolder.value.copy(saving = false, detail = "Saved maps deleted."))
                                countSavedAreas()
                            }
                        }
                    })
                }
            }

            override fun onError(error: String) {
                publish(stateHolder.value.copy(detail = "Could not delete saved maps. $error"))
            }
        })
    }

    private fun ensureCreated(view: MapView) {
        if (mapCreated) return
        view.onCreate(null)
        mapCreated = true
    }

    private fun createMapView(): MapView {
        val view = MapView(activity)
        // The Map screen scrolls; let the map keep its own drags.
        view.setOnTouchListener { touched, _ ->
            touched.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        view.getMapAsync { loaded ->
            loaded.uiSettings.setRotateGesturesEnabled(false)
            loaded.uiSettings.setTiltGesturesEnabled(false)
            loaded.setMaxZoomPreference(MAX_VIEW_ZOOM)
            loaded.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    publish(stateHolder.value.copy(following = false))
                }
            }
            loaded.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                style.addSource(GeoJsonSource(SOURCE_ID))
                style.addLayer(
                    CircleLayer(DOT_LAYER_ID, SOURCE_ID).withProperties(
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleColor(
                            Expression.switchCase(
                                Expression.get("self"),
                                Expression.color(BLACK),
                                Expression.color(WHITE),
                            ),
                        ),
                        PropertyFactory.circleStrokeWidth(3f),
                        PropertyFactory.circleStrokeColor(
                            Expression.switchCase(
                                Expression.get("self"),
                                Expression.color(WHITE),
                                Expression.color(BLACK),
                            ),
                        ),
                    ),
                )
                style.addLayer(
                    SymbolLayer(LABEL_LAYER_ID, SOURCE_ID).withProperties(
                        PropertyFactory.textField(Expression.get("name")),
                        PropertyFactory.textFont(arrayOf(LABEL_FONT)),
                        PropertyFactory.textSize(13f),
                        PropertyFactory.textColor(WHITE),
                        PropertyFactory.textHaloColor(BLACK),
                        PropertyFactory.textHaloWidth(1.5f),
                        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                        PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(true),
                    ),
                )
                map = loaded
                framed = false
                showRiders(riders)
            }
        }
        return view
    }

    private fun showRiders(places: List<ChannelPlace>) {
        riders = places
        val current = map ?: return
        val source = current.style?.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return
        source.setGeoJson(
            FeatureCollection.fromFeatures(
                places.map { place ->
                    Feature.fromGeometry(Point.fromLngLat(place.longitude, place.latitude)).apply {
                        addStringProperty("name", place.name)
                        addBooleanProperty("self", place.self)
                    }
                },
            ),
        )
        if (stateHolder.value.following) frame(current)
    }

    private fun frame(current: MapLibreMap) {
        if (riders.isEmpty()) return
        val points = riders.map { LatLng(it.latitude, it.longitude) }
        val spread = maxOf(
            points.maxOf { it.latitude } - points.minOf { it.latitude },
            points.maxOf { it.longitude } - points.minOf { it.longitude },
        )
        val update = if (points.size == 1 || spread < 0.0005) {
            CameraUpdateFactory.newLatLngZoom(points.first(), RIDER_ZOOM)
        } else {
            val padding = (64 * activity.resources.displayMetrics.density).toInt()
            CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), padding)
        }
        if (framed) {
            current.animateCamera(update, CAMERA_MILLIS)
        } else {
            current.moveCamera(update)
            framed = true
        }
    }

    private fun saveObserver(region: OfflineRegion) = object : OfflineRegion.OfflineRegionObserver {
        override fun onStatusChanged(status: OfflineRegionStatus) {
            if (saving !== region) return
            if (status.isComplete) {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                saving = null
                val megabytes = status.completedResourceSize / (1024 * 1024)
                publish(
                    stateHolder.value.copy(
                        saving = false,
                        progress = 1f,
                        detail = "Area saved ($megabytes MB). It shows with no internet.",
                    ),
                )
                countSavedAreas()
                return
            }
            val required = status.requiredResourceCount
            val progress = if (required > 0) status.completedResourceCount.toFloat() / required else 0f
            publish(stateHolder.value.copy(saving = true, progress = progress.coerceIn(0f, 1f)))
        }

        override fun onError(error: OfflineRegionError) {
            if (error.reason == OfflineRegionError.REASON_CONNECTION) {
                publish(stateHolder.value.copy(detail = "Saving paused. Waiting for internet."))
            }
        }

        override fun mapboxTileCountLimitExceeded(limit: Long) {
            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            saving = null
            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() = countSavedAreas()

                override fun onError(error: String) = countSavedAreas()
            })
            publish(
                stateHolder.value.copy(
                    saving = false,
                    detail = "This area is too big to save. Zoom in and try again.",
                ),
            )
        }
    }

    private fun countSavedAreas() {
        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                publish(stateHolder.value.copy(savedAreas = offlineRegions?.size ?: 0))
            }

            override fun onError(error: String) = Unit
        })
    }

    private fun publish(next: RiderMapState) {
        stateHolder.value = next
    }

    private companion object {
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
        const val LABEL_FONT = "Noto Sans Regular"
        const val SOURCE_ID = "airvox-riders"
        const val DOT_LAYER_ID = "airvox-rider-dots"
        const val LABEL_LAYER_ID = "airvox-rider-names"
        const val MIN_SAVE_ZOOM = 8.0
        const val MAX_SAVE_ZOOM = 14.0
        const val MAX_VIEW_ZOOM = 18.0
        const val RIDER_ZOOM = 15.0
        const val CAMERA_MILLIS = 600
        const val TILE_LIMIT = 10_000L
        val WHITE = "#F5F5F7".toColorInt()
        val BLACK = "#070708".toColorInt()
    }
}

/** Map tiles the region needs from [minZoom] to [maxZoom]. */
private fun tileCount(bounds: LatLngBounds, minZoom: Int, maxZoom: Int): Long {
    var total = 0L
    for (zoom in minZoom..maxZoom) {
        val tiles = 1 shl zoom
        val west = tileX(bounds.longitudeWest, tiles)
        val east = tileX(bounds.longitudeEast, tiles)
        val north = tileY(bounds.latitudeNorth, tiles)
        val south = tileY(bounds.latitudeSouth, tiles)
        total += (abs(east - west) + 1).toLong() * (abs(south - north) + 1)
    }
    return total
}

private fun tileX(longitude: Double, tiles: Int): Int {
    return floor((longitude + 180.0) / 360.0 * tiles).toInt().coerceIn(0, tiles - 1)
}

private fun tileY(latitude: Double, tiles: Int): Int {
    val radians = latitude.coerceIn(-85.0511, 85.0511) * PI / 180.0
    val y = (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * tiles
    return floor(y).toInt().coerceIn(0, tiles - 1)
}
