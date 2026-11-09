package com.ota.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.otaapplication.weather.Place
import com.example.otaapplication.weather.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the Home weather comes from: this connection's approximate location, or a fixed city
 * found with Open-Meteo's free city search. Stored on this laptop only.
 */
@Composable
fun WeatherPlaceChooser(onChosen: () -> Unit = {}) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var city by remember { mutableStateOf<Place?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Place>()) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { city = withContext(Dispatchers.IO) { app.weather.city() }; loaded = true }
    LaunchedEffect(query) {
        failed = false
        if (query.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        searching = true
        runCatching { withContext(Dispatchers.IO) { Weather.searchCity(query) } }
            .onSuccess { results = it }.onFailure { results = emptyList(); failed = true }
        searching = false
    }
    fun choose(place: Place?) {
        city = place
        query = ""
        scope.launch { app.weather.chooseCity(place) }
        onChosen()
    }

    Column {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { choose(null) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = loaded && city == null, onClick = { choose(null) }, colors = RadioButtonDefaults.colors(selectedColor = Brand.Saffron))
            Column {
                Text("This connection's location", style = MaterialTheme.typography.bodyLarge)
                Text("Approximate, from the internet address. Office VPNs can show another city.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        city?.let { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = true, onClick = {}, colors = RadioButtonDefaults.colors(selectedColor = Brand.Saffron))
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = Brand.Saffron, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(c.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Brand.Line)
        Text(if (city == null) "Or a fixed city" else "Change the city", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Field(query, { query = it }, "Search city, e.g. Surat", Modifier.fillMaxWidth(), trailing = { Icon(Icons.Default.Search, contentDescription = null) })
        when {
            searching -> Text("Searching…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            failed -> Text("Couldn't search. Check the internet connection.", style = MaterialTheme.typography.bodySmall, color = Brand.Red, modifier = Modifier.padding(8.dp))
            query.trim().length >= 2 && results.isEmpty() -> Text("No towns found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        }
        results.forEach { p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { choose(p) }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = Brand.Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(p.name, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun WeatherPlaceDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
        title = { Text("Weather place") },
        text = { Column(Modifier.width(420.dp)) { WeatherPlaceChooser(onChosen = onDismiss) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
