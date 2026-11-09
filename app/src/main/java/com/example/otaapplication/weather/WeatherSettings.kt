package com.example.otaapplication.weather

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import com.example.otaapplication.ui.icons.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Home weather: the latest view plus a way to open the place picker. */
class HomeWeatherState(val store: WeatherStore, initial: WeatherView) {
    var view by mutableStateOf(initial)
        internal set
    var showSettings by mutableStateOf(false)
    internal var tick by mutableIntStateOf(0)
}

/**
 * Loads the Home weather while [active] (Home on screen and the app in front), refreshing from the
 * cache or network every [Weather.REFRESH_MS]. Asks for coarse location once; a refusal falls back
 * to the city set in the picker.
 */
@Composable
fun rememberHomeWeather(active: Boolean): HomeWeatherState {
    val context = LocalContext.current
    val store = remember { WeatherStore(context) }
    val state = remember { HomeWeatherState(store, store.cachedView()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        state.tick++
        if (!granted && store.city == null) state.showSettings = true
    }
    LaunchedEffect(active) {
        if (active && store.shouldAskForLocation) {
            store.markLocationAsked()
            permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
    LaunchedEffect(active, state.tick) {
        if (!active) return@LaunchedEffect
        while (true) {
            state.view = store.refresh()
            delay(Weather.REFRESH_MS)
        }
    }
    if (state.showSettings) {
        WeatherPlaceDialog(
            store = store,
            onDismiss = { state.showSettings = false },
            onChanged = { state.tick++ },
            requestLocation = { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
        )
    }
    return state
}

/** Sync Settings block: where the Home weather comes from. */
@Composable
fun WeatherSettingsSection() {
    val context = LocalContext.current
    val store = remember { WeatherStore(context) }
    var label by remember { mutableStateOf(store.sourceLabel()) }
    var open by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { label = store.sourceLabel() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Weather place", style = MaterialTheme.typography.bodyLarge)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = { open = true }) { Text("Change") }
        }
        Text(
            "Used only for the weather on Home. Your location stays on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (open) {
        WeatherPlaceDialog(
            store = store,
            onDismiss = { open = false },
            onChanged = { label = store.sourceLabel() },
            requestLocation = { permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
        )
    }
}

/** Choose between the phone's location and a fixed city (searched with Open-Meteo geocoding). */
@Composable
fun WeatherPlaceDialog(store: WeatherStore, onDismiss: () -> Unit, onChanged: () -> Unit, requestLocation: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); error = null; return@LaunchedEffect }
        delay(350)
        searching = true
        val found = withContext(Dispatchers.IO) { runCatching { Weather.searchCity(q) } }
        searching = false
        results = found.getOrDefault(emptyList())
        error = when {
            found.isFailure -> "Can't search right now. Check the internet connection."
            results.isEmpty() -> "No place called “$q”."
            else -> null
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
        title = { Text("Weather place") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        store.chooseLocation()
                        if (!store.hasLocationPermission()) requestLocation()
                        onChanged(); onDismiss()
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = store.useLocation, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Use phone location", style = MaterialTheme.typography.bodyLarge)
                        Text("Approximate, city level", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
                Text(
                    "Or a fixed city" + (store.city?.takeIf { !store.useLocation }?.let { " (now: ${it.name})" } ?: ""),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search city, e.g. Surat") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(results, key = { "${it.name}|${it.latitude}|${it.longitude}" }) { place ->
                        Row(
                            Modifier.fillMaxWidth().clickable { store.chooseCity(place); onChanged(); onDismiss() }.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(place.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
