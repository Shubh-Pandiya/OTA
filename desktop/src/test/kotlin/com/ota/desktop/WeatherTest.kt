package com.ota.desktop

import com.example.otaapplication.weather.Place
import com.example.otaapplication.weather.Weather
import com.example.otaapplication.weather.WeatherKind
import com.example.otaapplication.weather.WeatherNow
import com.ota.desktop.db.Kv
import com.ota.desktop.weather.DesktopWeather
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherTest {
    private val surat = Place("Surat, Gujarat", 21.17, 72.83)

    @Test
    fun parsesOpenMeteoForecast() {
        val body = """{"latitude":21.17,"current":{"time":"2026-11-10T13:00","temperature_2m":31.6,"weather_code":2,"is_day":1},
            "daily":{"time":["2026-11-10"],"temperature_2m_max":[34.2],"temperature_2m_min":[22.8]}}"""
        val w = Weather.parseForecast(body, surat, 1000L)
        assertEquals("Surat, Gujarat", w.place)
        assertEquals(31.6, w.temperature)
        assertEquals(WeatherKind.PARTLY_CLOUDY, w.kind)
        assertEquals(34.2, w.high)
        assertEquals(22.8, w.low)
        assertEquals("32°", Weather.degrees(w.temperature))
        assertEquals(w, WeatherNow.fromJson(JSONObject(w.toJson().toString())))
        assertEquals("Clear night", w.copy(code = 0, isDay = false).label)
        assertTrue(w.isFor(Place("Surat", 21.2, 72.8)))
        assertTrue(!w.isFor(Place("Mumbai", 19.07, 72.88)))
    }

    @Test
    fun parsesCitySearchWithRegion() {
        val body = """{"results":[{"name":"Surat","latitude":21.19,"longitude":72.83,"country":"India","admin1":"Gujarat"},
            {"name":"Delhi","latitude":28.65,"longitude":77.23,"country":"India","admin1":"Delhi"}]}"""
        val cities = Weather.parseCities(body)
        assertEquals(listOf("Surat, Gujarat", "Delhi, India"), cities.map { it.name })
        assertEquals(emptyList(), Weather.parseCities("""{"generationtime_ms":0.2}"""))
    }

    @Test
    fun parsesBothIpServices() {
        val geojs = DesktopWeather.parseIpLocation("""{"city":"Surat","region":"Gujarat","latitude":"21.1959","longitude":"72.8302"}""")
        assertEquals(Place("Surat", 21.1959, 72.8302), geojs)
        val ipwho = DesktopWeather.parseIpLocation("""{"success":true,"city":"Mumbai","latitude":19.07,"longitude":72.88}""")
        assertEquals("Mumbai", ipwho?.name)
        assertNull(DesktopWeather.parseIpLocation("""{"success":false,"message":"reserved range"}"""))
    }

    @Test
    fun keepsTheLastForecastWhenOffline() = runBlocking {
        val kv = Kv(TestDb.fresh())
        val weather = DesktopWeather(kv) { throw java.io.IOException("offline") }
        // Nothing cached and no way to find a place: a quiet note, not an error.
        val none = weather.refresh()
        assertNull(none.weather)
        assertNotNull(none.note)

        val old = WeatherNow("Surat, Gujarat", 29.0, 0, true, 33.0, 21.0, surat.latitude, surat.longitude, System.currentTimeMillis() - 2 * Weather.REFRESH_MS)
        kv["weather.now"] = old.toJson().toString()
        kv["weather.city"] = surat.toJson().toString()
        val stale = weather.refresh()
        assertEquals(old, stale.weather)
        assertTrue(stale.note!!.startsWith("Offline"))

        val fresh = old.copy(fetchedAt = System.currentTimeMillis())
        kv["weather.now"] = fresh.toJson().toString()
        assertEquals(fresh, weather.refresh().weather)
        assertNull(weather.refresh().note)
        assertEquals(surat, weather.city())
    }
}
