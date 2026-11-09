package com.ota.desktop.weather

import com.example.otaapplication.ui.updatedAgo
import com.example.otaapplication.weather.Place
import com.example.otaapplication.weather.Weather
import com.example.otaapplication.weather.WeatherNow
import com.ota.desktop.db.Kv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the Home card shows: the forecast (possibly cached) and an optional note under it. */
data class WeatherView(val weather: WeatherNow?, val note: String?)

/**
 * Weather for the desktop Home card. Local to this laptop and never synced. The place is the city
 * picked in Sync & Cloud or, until one is picked, the approximate place of this internet
 * connection (free, keyless IP lookup, cached for 12 hours). Offline it keeps showing the last
 * forecast with a quiet note.
 */
class DesktopWeather(private val kv: Kv, private val http: (String) -> String = { Weather.get(it, 6000) }) {
    private val _view = MutableStateFlow<WeatherView?>(null)
    val view: StateFlow<WeatherView?> = _view.asStateFlow()
    private val lock = Mutex()

    /** The fixed city, or null to use the internet location. Reads the local database. */
    fun city(): Place? = kv[CITY]?.let { runCatching { Place.fromJson(JSONObject(it)) }.getOrNull() }

    suspend fun chooseCity(place: Place?) {
        withContext(Dispatchers.IO) { kv[CITY] = place?.toJson()?.toString() }
        refresh(force = true)
    }

    suspend fun refresh(force: Boolean = false): WeatherView = lock.withLock {
        withContext(Dispatchers.IO) { load(force) }.also { _view.value = it }
    }

    private fun load(force: Boolean): WeatherView {
        val now = System.currentTimeMillis()
        val cached = kv[NOW]?.let { runCatching { WeatherNow.fromJson(JSONObject(it)) }.getOrNull() }
        val city = city()
        if (!force && cached != null && cached.isFresh(now) && (city == null || cached.isFor(city))) return WeatherView(cached, null)
        val place = city ?: runCatching { ipPlace(now) }.getOrNull()
        if (place == null) return offline(cached?.takeIf { city == null })
        return runCatching { Weather.parseForecast(http(Weather.forecastUrl(place)), place, now) }
            .onSuccess { kv[NOW] = it.toJson().toString() }
            .map { WeatherView(it, null) }
            .getOrElse { offline(cached?.takeIf { c -> c.isFor(place) }) }
    }

    private fun offline(cached: WeatherNow?): WeatherView =
        if (cached != null) WeatherView(cached, "Offline · ${updatedAgo(cached.fetchedAt)}")
        else WeatherView(null, "Weather isn't available right now. Click to choose your city.")

    private fun ipPlace(now: Long): Place {
        kv[IP]?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { o ->
            if (now - o.optLong("at") in 0 until IP_TTL_MS) Place.fromJson(o)?.let { return it }
        }
        val place = IP_SERVICES.firstNotNullOfOrNull { url -> runCatching { parseIpLocation(http(url)) }.getOrNull() }
            ?: error("No internet location")
        kv[IP] = place.toJson().put("at", now).toString()
        return place
    }

    companion object {
        private const val CITY = "weather.city"
        private const val NOW = "weather.now"
        private const val IP = "weather.ip"
        private const val IP_TTL_MS = 12 * 3_600_000L
        private val IP_SERVICES = listOf("https://get.geojs.io/v1/ip/geo.json", "https://ipwho.is/")

        /** Reads geojs.io (coordinates as strings) and ipwho.is (numbers) answers alike. */
        fun parseIpLocation(body: String): Place? {
            val o = JSONObject(body)
            if (o.has("success") && !o.optBoolean("success")) return null
            val lat = o.opt("latitude")?.toString()?.toDoubleOrNull() ?: return null
            val lon = o.opt("longitude")?.toString()?.toDoubleOrNull() ?: return null
            val name = o.optString("city").trim().ifEmpty { o.optString("region").trim() }
            return if (name.isEmpty()) null else Place(name, lat, lon)
        }
    }
}
