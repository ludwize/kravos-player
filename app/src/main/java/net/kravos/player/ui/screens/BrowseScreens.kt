package net.kravos.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import net.kravos.player.data.AlbumInfo
import net.kravos.player.data.ArtistInfo
import net.kravos.player.data.Track
import net.kravos.player.ui.MainViewModel
import net.kravos.player.ui.Screen
import net.kravos.player.ui.SmartKind
import net.kravos.player.ui.components.AddToPlaylistDialog
import net.kravos.player.ui.components.SectionHeader
import net.kravos.player.ui.components.SimpleRow
import net.kravos.player.ui.components.TextInputDialog
import net.kravos.player.ui.components.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(vm: MainViewModel) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Playlists") },
            actions = { IconButton(onClick = { create = true }) { Icon(Icons.Default.Add, "New playlist") } },
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionHeader("Smart") }
            items(SmartKind.entries.toList()) { k ->
                val icon = when (k) {
                    SmartKind.RECENTLY_ADDED -> Icons.Default.NewReleases
                    SmartKind.MOST_PLAYED -> Icons.Default.TrendingUp
                    SmartKind.RECENTLY_PLAYED -> Icons.Default.History
                    SmartKind.DOWNLOADED -> Icons.Default.DownloadDone
                }
                SimpleRow(k.label, null, null, icon) { vm.open(Screen.Smart(k)) }
            }
            item { SectionHeader("Your playlists") }
            if (playlists.isEmpty()) {
                item {
                    Text(
                        "No playlists yet — tap + or use “Add to playlist…” on any song.",
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(playlists, key = { it.id }) { p ->
                SimpleRow(p.name, "${p.trackCount} songs", p.artUri, Icons.AutoMirrored.Filled.PlaylistPlay) {
                    vm.open(Screen.PlaylistScreen(p.id, p.name))
                }
            }
        }
    }
    if (create) {
        TextInputDialog("New playlist", "", "Create", { create = false }) { vm.createPlaylist(it, emptyList()); create = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: MainViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(250)
        debounced = query.trim()
    }
    val like = "%$debounced%"
    val artists by remember(debounced) {
        if (debounced.length < 2) flowOf(emptyList<ArtistInfo>()) else vm.dao.searchArtists(like)
    }.collectAsStateWithLifecycle(emptyList())
    val albums by remember(debounced) {
        if (debounced.length < 2) flowOf(emptyList<AlbumInfo>()) else vm.dao.searchAlbums(like)
    }.collectAsStateWithLifecycle(emptyList())
    val tracks by remember(debounced) {
        if (debounced.length < 2) flowOf(emptyList<Track>()) else vm.dao.searchTracks(like)
    }.collectAsStateWithLifecycle(emptyList())
    val np by vm.player.state.collectAsStateWithLifecycle()
    val dl by vm.downloadStates.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var addTarget by remember { mutableStateOf<List<Track>?>(null) }
    val actions = rememberTrackActions(vm, { addTarget = listOf(it) })

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Search") })
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Artists, albums, songs") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            if (artists.isNotEmpty()) {
                item { SectionHeader("Artists") }
                items(artists.take(8), key = { "a" + it.name }) { a ->
                    SimpleRow(a.name, "${a.trackCount} songs", a.artUri, Icons.Default.Person, roundArt = true) { vm.open(Screen.Artist(a.name)) }
                }
            }
            if (albums.isNotEmpty()) {
                item { SectionHeader("Albums") }
                items(albums.take(12), key = { "b" + it.albumKey }) { a ->
                    SimpleRow(a.album, a.albumArtist, a.artUri, Icons.Default.Album) { vm.open(Screen.Album(a.albumKey, a.album)) }
                }
            }
            if (tracks.isNotEmpty()) {
                item { SectionHeader("Songs") }
                itemsIndexed(tracks, key = { _, t -> "t" + t.id }) { i, t ->
                    TrackRow(t, i, np.mediaId == t.id, dl[t.id], actions) { vm.play(tracks, i) }
                }
            }
        }
    }
    addTarget?.let { target ->
        AddToPlaylistDialog(playlists, { addTarget = null }, { vm.addToPlaylist(it, target); addTarget = null }, { vm.createPlaylist(it, target); addTarget = null })
    }
}
