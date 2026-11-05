package com.airvox.location

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.airvox.intercom.AndroidIntercomSession

/**
 * This phone's position. A satellite fix is preferred. Wi-Fi and cell fixes
 * show the pin immediately, including indoors, where a satellite lock can take
 * a long time. The channel carries the position to the other phones.
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
    private var satelliteFix = false
    private var fixAtElapsed = 0L

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
            if (!accept(location)) return
            publish(allowed = true, gpsOn = true, detail = FIX_DETAIL)
        }

        override fun onProviderEnabled(provider: String) {
            startUpdates()
        }

        override fun onProviderDisabled(provider: String) {
            if (locationEnabled()) return
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
        val providers = enabledProviders()
        if (providers.isEmpty()) {
            listening = false
            publish(
                allowed = true,
                gpsOn = false,
                detail = "Turn location on. GPS finds this phone with no mobile data.",
            )
            return
        }
        if (listening) {
            seedFromLastKnown()
            publish(allowed = true, gpsOn = true, detail = if (latitude == null) WAITING_DETAIL else FIX_DETAIL)
            return
        }
        var started = false
        for (provider in providers) {
            try {
                locationManager.requestLocationUpdates(
                    provider,
                    3_000L,
                    5f,
                    listener,
                    Looper.getMainLooper(),
                )
                started = true
            } catch (_: SecurityException) {
                listening = false
                publish(allowed = false, gpsOn = locationEnabled(), detail = "Allow location to use GPS.")
                return
            } catch (_: IllegalArgumentException) {
                // This provider is missing on this phone. The others can still run.
            }
        }
        if (!started) {
            listening = false
            publish(allowed = true, gpsOn = false, detail = "This phone has no GPS.")
            return
        }
        listening = true
        seedFromLastKnown()
        publish(allowed = true, gpsOn = true, detail = if (latitude == null) WAITING_DETAIL else FIX_DETAIL)
    }

    private fun seedFromLastKnown() {
        val known = candidateProviders().mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }
        val now = System.currentTimeMillis()
        val recentSatellite = known
            .filter { it.provider == LocationManager.GPS_PROVIDER && now - it.time < 120_000L }
            .maxByOrNull { it.time }
        val best = recentSatellite ?: known.maxByOrNull { it.time } ?: return
        accept(best)
    }

    /**
     * A satellite fix replaces a Wi-Fi or cell fix. A later Wi-Fi fix does not
     * pull the pin off a fresh satellite reading.
     */
    private fun accept(location: Location): Boolean {
        val satellite = location.provider == LocationManager.GPS_PROVIDER
        val freshSatellite = satelliteFix && SystemClock.elapsedRealtime() - fixAtElapsed < 20_000L
        if (freshSatellite && !satellite) return false
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return false
        satelliteFix = satellite
        fixAtElapsed = SystemClock.elapsedRealtime()
        remember(location.latitude, location.longitude)
        session.sharePlace(location.latitude, location.longitude)
        return true
    }

    private fun hasFineLocation(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun gpsEnabled(): Boolean = locationEnabled()

    private fun locationEnabled(): Boolean = enabledProviders().isNotEmpty()

    private fun enabledProviders(): List<String> {
        return candidateProviders().filter { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }
    }

    private fun candidateProviders(): List<String> {
        val names = mutableListOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            names.add(LocationManager.FUSED_PROVIDER)
        }
        return names
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
        const val FIX_DETAIL = "GPS is on. No internet needed."
        const val WAITING_DETAIL = "Waiting for a location fix."
    }
}
