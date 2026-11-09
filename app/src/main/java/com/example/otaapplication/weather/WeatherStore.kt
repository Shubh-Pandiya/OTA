package com.example.otaapplication.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

/** What the Home weather card shows: the forecast (possibly cached) and an optional note instead of "Updated …". */
data class WeatherView(val weather: WeatherNow?, val note: String?)

/**
 * The phone side of the Home weather. It is kept only on this phone and never synced.
 * - Place: the phone's coarse location when allowed and chosen, otherwise the city set in settings.
 * - Forecast: Open-Meteo, cached in preferences and refreshed at most every [Weather.REFRESH_MS].
 * - Offline: the last forecast is kept and shown with how old it is.
 */
class WeatherStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("OTA_Weather", Context.MODE_PRIVATE)

    /** True when the user wants the phone's location (default); false once they pick a fixed city. */
    var useLocation: Boolean
        get() = prefs.getBoolean(KEY_USE_LOCATION, true)
        set(value) { prefs.edit().putBoolean(KEY_USE_LOCATION, value).apply() }

    var city: Place?
        get() = Place.fromJson(prefs.getString(KEY_CITY, null)?.let(::JSONObject))
        set(value) { prefs.edit().putString(KEY_CITY, value?.toJson()?.toString()).apply() }

    /** Whether location is the user's choice and they haven't been asked for the permission yet. */
    val shouldAskForLocation: Boolean
        get() = useLocation && !hasLocationPermission() && !prefs.getBoolean(KEY_ASKED, false)

    fun markLocationAsked() = prefs.edit().putBoolean(KEY_ASKED, true).apply()

    fun cached(): WeatherNow? = WeatherNow.fromJson(prefs.getString(KEY_NOW, null)?.let(::JSONObject))

    fun cachedView(): WeatherView = view(cached(), null)

    fun hasLocationPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Picks the fixed city and forgets the cached forecast, so the next refresh fetches for it. */
    fun chooseCity(place: Place) {
        city = place
        useLocation = false
        prefs.edit().remove(KEY_NOW).apply()
    }

    fun chooseLocation() {
        useLocation = true
        prefs.edit().remove(KEY_NOW).apply()
    }

    /** A short line for settings, e.g. "Phone location" or "Surat, Gujarat". */
    fun sourceLabel(): String = when {
        useLocation && hasLocationPermission() -> "Phone location" + (cached()?.place?.let { " · $it" } ?: "")
        city != null -> city!!.name + if (useLocation) " (location not allowed)" else ""
        useLocation -> "Phone location (not allowed yet)"
        else -> "Not set"
    }

    /** Fresh-enough forecast for the current place, from the cache when possible. Never throws. */
    suspend fun refresh(force: Boolean = false): WeatherView = withContext(Dispatchers.IO) {
        val cached = cached()
        val place = runCatching { currentPlace() }.getOrNull()
            ?: return@withContext view(cached, if (cached == null) NO_PLACE else null)
        if (!force && cached != null && cached.isFresh() && cached.isFor(place) && cached.place == place.name) {
            return@withContext view(cached, null)
        }
        runCatching { Weather.fetch(place) }
            .onSuccess { prefs.edit().putString(KEY_NOW, it.toJson().toString()).apply() }
            .fold(
                onSuccess = { view(it, null) },
                onFailure = {
                    // Offline or the service is down: keep the last forecast and say how old it is.
                    if (cached != null) view(cached, "Offline · ${com.example.otaapplication.ui.updatedAgo(cached.fetchedAt)}")
                    else view(null, "Weather unavailable offline")
                }
            )
    }

    private fun view(weather: WeatherNow?, note: String?) = WeatherView(weather, note ?: if (weather == null) NO_PLACE.takeIf { !useLocation && city == null } else null)

    private suspend fun currentPlace(): Place? {
        if (useLocation && hasLocationPermission()) {
            deviceLocation()?.let { return placeFor(it) }
        }
        return city
    }

    @SuppressLint("MissingPermission")
    private suspend fun deviceLocation(): Location? {
        val lm = app.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) || it == LocationManager.PASSIVE_PROVIDER }
        // A recent last-known fix is plenty for city-level weather and costs no battery.
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time < 6 * 60 * 60_000L }
        if (recent != null) return recent
        val provider = providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return null
        return withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val cancel = android.os.CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                lm.getCurrentLocation(provider, cancel, app.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
            }
        } ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    /** Names the location (reusing the last name when the phone hasn't moved ~10 km). */
    private suspend fun placeFor(loc: Location): Place {
        val last = Place.fromJson(prefs.getString(KEY_DEVICE_PLACE, null)?.let(::JSONObject))
        if (last != null && kotlin.math.abs(last.latitude - loc.latitude) < 0.1 && kotlin.math.abs(last.longitude - loc.longitude) < 0.1) return last
        val name = geocode(loc) ?: String.format(Locale.ROOT, "%.2f°, %.2f°", loc.latitude, loc.longitude)
        return Place(name, loc.latitude, loc.longitude).also {
            prefs.edit().putString(KEY_DEVICE_PLACE, it.toJson().toString()).apply()
        }
    }

    private suspend fun geocode(loc: Location): String? {
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(6_000) {
            suspendCancellableCoroutine { cont ->
                Geocoder(app, Locale.ENGLISH).getFromLocation(loc.latitude, loc.longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        val a = addresses.firstOrNull()
                        if (cont.isActive) cont.resume(a?.locality ?: a?.subAdminArea ?: a?.adminArea)
                    }
                    override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                })
            }
        }
    }

    companion object {
        const val NO_PLACE = "Tap to set your city for the weather"
        private const val KEY_NOW = "now"
        private const val KEY_CITY = "city"
        private const val KEY_USE_LOCATION = "use_location"
        private const val KEY_ASKED = "location_asked"
        private const val KEY_DEVICE_PLACE = "device_place"
    }
}
