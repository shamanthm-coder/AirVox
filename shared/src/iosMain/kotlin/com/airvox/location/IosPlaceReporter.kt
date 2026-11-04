package com.airvox.location

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject

/**
 * GPS fixes from CoreLocation. GPS does not need mobile data or Wi-Fi.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPlaceReporter : PlaceReporter {
    private val readingHolder = mutableStateOf(PlaceReading())
    override val reading: State<PlaceReading> = readingHolder

    private val manager = CLLocationManager()
    private var listening = false
    private var latitude: Double? = null
    private var longitude: Double? = null

    // CoreLocation keeps a weak reference, so the delegate lives here.
    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            refresh()
        }

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
            location.coordinate.useContents {
                this@IosPlaceReporter.latitude = latitude
                this@IosPlaceReporter.longitude = longitude
            }
            publish(allowed = true, gpsOn = true, detail = "GPS is on. No internet needed.")
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            if (latitude == null) {
                publish(allowed = true, gpsOn = true, detail = "Waiting for GPS.")
            }
        }
    }

    init {
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 5.0
        refresh()
    }

    override fun requestPermission() {
        if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) {
            manager.requestWhenInUseAuthorization()
        } else {
            openLocationSettings()
        }
    }

    override fun openLocationSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    override fun refresh() {
        val status = manager.authorizationStatus
        val allowed = status == kCLAuthorizationStatusAuthorizedWhenInUse ||
            status == kCLAuthorizationStatusAuthorizedAlways
        if (!allowed) {
            listening = false
            manager.stopUpdatingLocation()
            publish(
                allowed = false,
                gpsOn = CLLocationManager.locationServicesEnabled(),
                detail = "Allow location to see this phone and the others on the channel.",
            )
            return
        }
        if (!listening) {
            manager.startUpdatingLocation()
            listening = true
        }
        publish(
            allowed = true,
            gpsOn = true,
            detail = if (latitude == null) "Waiting for GPS." else "GPS is on. No internet needed.",
        )
    }

    private fun publish(allowed: Boolean, gpsOn: Boolean, detail: String?) {
        readingHolder.value = PlaceReading(
            allowed = allowed,
            gpsOn = gpsOn,
            listening = listening,
            detail = detail,
            latitude = latitude,
            longitude = longitude,
        )
    }
}
