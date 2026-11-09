package com.example.otaapplication.weather

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Weather for the Home card from Open-Meteo (free, no key). Pure Kotlin so the phone and the
 * desktop share it; each app keeps its own cache and decides where the place comes from.
 */
data class Place(val name: String, val latitude: Double, val longitude: Double) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("lat", latitude).put("lon", longitude)

    companion object {
        fun fromJson(o: JSONObject?): Place? = o?.let {
            runCatching { Place(it.getString("name"), it.getDouble("lat"), it.getDouble("lon")) }.getOrNull()
        }
    }
}

enum class WeatherKind(val label: String) {
    CLEAR("Clear"), PARTLY_CLOUDY("Partly cloudy"), CLOUDY("Cloudy"), FOG("Foggy"),
    DRIZZLE("Drizzle"), RAIN("Rain"), STORM("Thunderstorm"), SNOW("Snow");

    companion object {
        /** WMO weather interpretation codes, as Open-Meteo returns them. */
        fun of(code: Int): WeatherKind = when (code) {
            0 -> CLEAR
            1, 2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            51, 53, 55, 56, 57 -> DRIZZLE
            61, 63, 65, 66, 67, 80, 81, 82 -> RAIN
            71, 73, 75, 77, 85, 86 -> SNOW
            95, 96, 99 -> STORM
            else -> CLOUDY
        }
    }
}

data class WeatherNow(
    val place: String,
    val temperature: Double,
    val code: Int,
    val isDay: Boolean,
    val high: Double?,
    val low: Double?,
    val latitude: Double,
    val longitude: Double,
    val fetchedAt: Long
) {
    val kind: WeatherKind get() = WeatherKind.of(code)
    val label: String get() = if (!isDay && kind == WeatherKind.CLEAR) "Clear night" else kind.label

    fun isFresh(now: Long = System.currentTimeMillis()): Boolean = now - fetchedAt in 0 until Weather.REFRESH_MS

    /** True when this forecast was fetched within ~10 km of [place]. */
    fun isFor(place: Place): Boolean = abs(place.latitude - latitude) < 0.1 && abs(place.longitude - longitude) < 0.1

    fun toJson(): JSONObject = JSONObject()
        .put("place", place).put("t", temperature).put("code", code).put("day", isDay)
        .put("hi", high ?: JSONObject.NULL).put("lo", low ?: JSONObject.NULL)
        .put("lat", latitude).put("lon", longitude).put("at", fetchedAt)

    companion object {
        fun fromJson(o: JSONObject?): WeatherNow? = o?.let {
            runCatching {
                WeatherNow(
                    it.getString("place"), it.getDouble("t"), it.getInt("code"), it.getBoolean("day"),
                    it.optDouble("hi").takeUnless(Double::isNaN), it.optDouble("lo").takeUnless(Double::isNaN),
                    it.getDouble("lat"), it.getDouble("lon"), it.getLong("at")
                )
            }.getOrNull()
        }
    }
}

object Weather {
    /** A forecast is refreshed at most every 45 minutes. */
    const val REFRESH_MS = 45 * 60 * 1000L
    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    private const val GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"

    fun degrees(value: Double): String = "${value.roundToInt()}°"

    /** Current conditions and today's high/low at [place]. Blocking; call off the main thread. */
    fun fetch(place: Place, now: Long = System.currentTimeMillis()): WeatherNow = parseForecast(get(forecastUrl(place)), place, now)

    fun forecastUrl(place: Place): String =
        "$FORECAST?latitude=${coord(place.latitude)}&longitude=${coord(place.longitude)}" +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"

    fun parseForecast(body: String, place: Place, now: Long): WeatherNow {
        val o = JSONObject(body)
        val current = o.getJSONObject("current")
        val daily = o.optJSONObject("daily")
        return WeatherNow(
            place = place.name,
            temperature = current.getDouble("temperature_2m"),
            code = current.optInt("weather_code", 3),
            isDay = current.optInt("is_day", 1) == 1,
            high = daily?.optJSONArray("temperature_2m_max")?.optDouble(0)?.takeUnless(Double::isNaN),
            low = daily?.optJSONArray("temperature_2m_min")?.optDouble(0)?.takeUnless(Double::isNaN),
            latitude = place.latitude,
            longitude = place.longitude,
            fetchedAt = now
        )
    }

    /** Towns matching [query], best first, named like "Surat, Gujarat". Blocking. */
    fun searchCity(query: String, count: Int = 6): List<Place> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val url = "$GEOCODE?name=${URLEncoder.encode(q, "UTF-8")}&count=$count&language=en&format=json"
        return parseCities(get(url))
    }

    fun parseCities(body: String): List<Place> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val r = results.optJSONObject(i) ?: return@mapNotNull null
            val name = r.optString("name").ifBlank { return@mapNotNull null }
            val region = r.optString("admin1").takeIf { it.isNotBlank() && !it.equals(name, true) }
                ?: r.optString("country").takeIf { it.isNotBlank() }
            Place(listOfNotNull(name, region).joinToString(", "), r.getDouble("latitude"), r.getDouble("longitude"))
        }
    }

    private fun coord(v: Double) = String.format(Locale.ROOT, "%.3f", v)

    fun get(url: String, timeoutMs: Int = 8000): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
