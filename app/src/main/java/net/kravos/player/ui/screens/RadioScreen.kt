package net.kravos.player.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import net.kravos.player.radio.RadioStation
import net.kravos.player.ui.MainViewModel
import net.kravos.player.ui.MainViewModel.RadioTab
import net.kravos.player.ui.components.ArtImage
import net.kravos.player.ui.components.EmptyState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(vm: MainViewModel) {
    val prefs = vm.graph.prefs
    val favs by prefs.radioFavorites.collectAsStateWithLifecycle()
    val castOnly by prefs.radioCastOnly.collectAsStateWithLifecycle()
    val goodOnly by prefs.radioGoodOnly.collectAsStateWithLifecycle()
    val serbia by vm.radioSerbia.collectAsStateWithLifecycle()
    val world by vm.radioWorld.collectAsStateWithLifecycle()
    val np by vm.player.state.collectAsStateWithLifecycle()
    val message by vm.radioMessage.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val tab = vm.radioTab

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.clearRadioMessage()
        }
    }
    LaunchedEffect(tab, query) {
        if (tab == RadioTab.FAVORITES) return@LaunchedEffect
        if (query.isNotBlank()) delay(450)
        vm.loadRadio(tab, query.trim())
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Radio") },
            actions = {
                if (tab != RadioTab.FAVORITES) {
                    IconButton(onClick = { vm.loadRadio(tab, query.trim(), force = true) }) { Icon(Icons.Default.Refresh, "Reload") }
                }
                IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add station by URL") }
            },
        )
        TabRow(selectedTabIndex = tab.ordinal) {
            RadioTab.entries.forEach { t ->
                Tab(selected = tab == t, onClick = { vm.radioTab = t }, text = { Text(t.label) })
            }
        }

        val state = if (tab == RadioTab.SERBIA) serbia else world
        if (tab != RadioTab.FAVORITES) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(if (tab == RadioTab.SERBIA) "Search Serbian stations" else "Search stations worldwide") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = castOnly,
                    onClick = { prefs.setRadioCastOnly(!castOnly) },
                    label = { Text("Cast on its own") },
                    leadingIcon = { Icon(Icons.Default.Cast, null, Modifier.size(18.dp)) },
                )
                FilterChip(selected = goodOnly, onClick = { prefs.setRadioGoodOnly(!goodOnly) }, label = { Text("Good quality") })
                FilterChip(
                    selected = vm.radioSortByQuality,
                    onClick = { vm.radioSortByQuality = !vm.radioSortByQuality },
                    label = { Text("Best quality first") },
                )
            }
        }

        val shown: List<RadioStation> = if (tab == RadioTab.FAVORITES) favs else {
            // Recomputed when filters change because castOnly/goodOnly/sort are read above.
            remember(state.stations, castOnly, goodOnly, vm.radioSortByQuality) { vm.filterStations(state.stations) }
        }

        when {
            tab == RadioTab.FAVORITES && favs.isEmpty() -> EmptyState("No favorite stations yet. Tap ☆ on any station, or + to add one by its stream URL.")
            tab != RadioTab.FAVORITES && state.loading && state.stations.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            tab != RadioTab.FAVORITES && state.error != null && state.stations.isEmpty() -> EmptyState(state.error ?: "") {
                Button(onClick = { vm.loadRadio(tab, query.trim(), force = true) }) { Text("Retry") }
            }
            shown.isEmpty() -> EmptyState(
                if (state.stations.isNotEmpty()) "All ${state.stations.size} stations are hidden by the filters above." else "No stations found",
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(shown, key = { _, s -> s.id }) { i, s ->
                    StationRow(
                        st = s,
                        playing = np.stationId == s.id,
                        favorite = favs.any { it.id == s.id },
                        onPlay = { vm.playStation(s, shown) },
                        onFavorite = { vm.toggleFavorite(s) },
                        onMoveUp = if (tab == RadioTab.FAVORITES && i > 0) ({ prefs.moveFavorite(s.id, -1) }) else null,
                        onMoveDown = if (tab == RadioTab.FAVORITES && i < shown.size - 1) ({ prefs.moveFavorite(s.id, 1) }) else null,
                    )
                }
                if (tab != RadioTab.FAVORITES && state.stations.size > shown.size) {
                    item {
                        Text(
                            "${state.stations.size - shown.size} more stations hidden by filters",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    if (adding) AddStationDialog(onDismiss = { adding = false }) { name, url ->
        adding = false
        vm.addCustomStation(name, url)
    }
}

@Composable
private fun StationRow(
    st: RadioStation,
    playing: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Play station" }
            .clickable(onClick = onPlay)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(st.favicon, size = 48.dp, fallback = Icons.Default.Radio)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                st.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (st.highQuality) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                        Text("HQ", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                if (st.castReady) {
                    Icon(Icons.Default.Cast, "Casts on its own", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    listOf(st.qualityLabel(), st.countryCode, st.tags.split(',').take(2).joinToString(", "))
                        .filter { it.isNotBlank() }.joinToString(" • "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        onMoveUp?.let { IconButton(onClick = it) { Icon(Icons.Default.ArrowUpward, "Move up") } }
        onMoveDown?.let { IconButton(onClick = it) { Icon(Icons.Default.ArrowDownward, "Move down") } }
        IconButton(onClick = onFavorite) {
            Icon(
                if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                if (favorite) "Remove from favorites" else "Add to favorites",
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddStationDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add station") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Stream URL (MP3/AAC/HLS, .pls, .m3u)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "HTTPS links can play on a Chromecast by themselves. HTTP-only ones cast through the phone, so they stop when the phone leaves Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(enabled = url.isNotBlank(), onClick = { onAdd(name, url) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
