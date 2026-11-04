package com.airvox.location

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.airvox.intercom.AndroidIntercomSession

/**
 * GPS fixes only. The channel carries them to the other phones, so this still
 * works when mobile data and Wi-Fi internet are off.
 */
class AndroidPlaceReporter(
    private val activity: ComponentActivity,
    private val session: AndroidIntercomSession,
) : PlaceReporter {
    private val readingHolder = mutableStateOf(PlaceReading())
    override val reading: State<PlaceReading> = readingHolder

    private val locationManager = activity.getSystemService(LocationManager::class.java)
    private var listening = false
    private var latitude: Double? = null
    private var longitude: Double? = null

    // Android stops showing its dialog after the rider refuses; send them to app settings instead.
    private var blocked = false

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startUpdates()
        } else {
            listening = false
            blocked = !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            publish(
                allowed = false,
                gpsOn = gpsEnabled(),
                detail = if (blocked) BLOCKED_DETAIL else "Allow location so this phone can share where it is on the channel.",
            )
        }
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            remember(location.latitude, location.longitude)
            session.sharePlace(location.latitude, location.longitude)
            publish(allowed = true, gpsOn = true, detail = "GPS is on. No internet needed.")
        }

        override fun onProviderEnabled(provider: String) {
            startUpdates()
        }

        override fun onProviderDisabled(provider: String) {
            listening = false
            publish(
                allowed = hasFineLocation(),
                gpsOn = false,
                detail = "Turn location on. GPS finds this phone with no mobile data.",
            )
        }
    }

    init {
        refresh()
    }

    override fun requestPermission() {
        if (hasFineLocation()) {
            startUpdates()
            return
        }
        if (blocked) {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
            )
            return
        }
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    override fun openLocationSettings() {
        activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    override fun refresh() {
        if (!hasFineLocation()) {
            listening = false
            publish(
                allowed = false,
                gpsOn = gpsEnabled(),
                detail = if (blocked) BLOCKED_DETAIL else "Allow location to see this phone and the others on the channel.",
            )
            return
        }
        startUpdates()
    }

    fun dispose() {
        listening = false
        runCatching { locationManager.removeUpdates(listener) }
    }

    private fun startUpdates() {
        if (!hasFineLocation()) return
        if (!gpsEnabled()) {
            listening = false
            publish(
                allowed = true,
                gpsOn = false,
                detail = "Turn location on. GPS finds this phone with no mobile data.",
            )
            return
        }
        if (listening) {
            publish(allowed = true, gpsOn = true, detail = "GPS is on. No internet needed.")
            return
        }
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                3_000L,
                5f,
                listener,
                Looper.getMainLooper(),
            )
            listening = true
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { known ->
                remember(known.latitude, known.longitude)
                session.sharePlace(known.latitude, known.longitude)
            }
            publish(allowed = true, gpsOn = true, detail = "GPS is on. No internet needed.")
        } catch (_: SecurityException) {
            listening = false
            publish(allowed = false, gpsOn = gpsEnabled(), detail = "Allow location to use GPS.")
        } catch (_: IllegalArgumentException) {
            listening = false
            publish(allowed = true, gpsOn = false, detail = "This phone has no GPS.")
        }
    }

    private fun hasFineLocation(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun gpsEnabled(): Boolean {
        return runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)
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

    private fun remember(lat: Double, lng: Double) {
        latitude = lat
        longitude = lng
    }

    private companion object {
        const val BLOCKED_DETAIL = "Location is blocked. Tap Allow location, then Permissions, Location, Allow."
    }
}
