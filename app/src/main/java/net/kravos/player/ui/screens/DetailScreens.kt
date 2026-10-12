package net.kravos.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import net.kravos.player.data.Track
import net.kravos.player.ui.MainViewModel
import net.kravos.player.ui.Screen
import net.kravos.player.ui.components.AddToPlaylistDialog
import net.kravos.player.ui.components.AlbumCard
import net.kravos.player.ui.components.ArtImage
import net.kravos.player.ui.components.EmptyState
import net.kravos.player.ui.components.SimpleRow
import net.kravos.player.ui.components.TextInputDialog
import net.kravos.player.ui.components.fmtDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailTopBar(vm: MainViewModel, title: String, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = actions,
    )
}

/** Overflow menu for a whole collection: play next, queue, playlist, download. */
@Composable
fun CollectionMenu(vm: MainViewModel, tracks: List<Track>, extra: (@Composable (close: () -> Unit) -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    var addTo by remember { mutableStateOf(false) }
    val dl by vm.downloadStates.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val allDownloaded = tracks.isNotEmpty() && tracks.all { dl[it.id] == Download.STATE_COMPLETED }
    IconButton(onClick = { if (allDownloaded) vm.removeDownload(tracks) else vm.download(tracks) }, enabled = tracks.isNotEmpty()) {
        Icon(if (allDownloaded) Icons.Default.DownloadDone else Icons.Default.Download, if (allDownloaded) "Remove downloads" else "Download")
    }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Play next") }, leadingIcon = { Icon(Icons.Default.SkipNext, null) }, onClick = { open = false; vm.playNext(tracks) })
            DropdownMenuItem(text = { Text("Add to queue") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }, onClick = { open = false; vm.enqueue(tracks) })
            DropdownMenuItem(text = { Text("Add to playlist…") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) }, onClick = { open = false; addTo = true })
            if (dl.keys.any { k -> tracks.any { it.id == k } }) {
                DropdownMenuItem(text = { Text("Remove downloads") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { open = false; vm.removeDownload(tracks) })
            }
            extra?.invoke { open = false }
        }
    }
    if (addTo) {
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { addTo = false },
            onPick = { vm.addToPlaylist(it, tracks); addTo = false },
            onCreate = { vm.createPlaylist(it, tracks); addTo = false },
        )
    }
}

@Composable
fun AlbumScreen(vm: MainViewModel, s: Screen.Album) {
    val tracks by remember(s.key) { vm.dao.albumTracks(s.key) }.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(vm, s.title) { CollectionMenu(vm, tracks) }
        val first = tracks.firstOrNull()
        TrackList(vm, tracks, showArt = false, showGoAlbum = false, header = {
            Column {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ArtImage(tracks.firstNotNullOfOrNull { it.artUri }, size = 132.dp, corner = 8.dp, fallback = Icons.Default.Album)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (first != null) {
                            Text(
                                first.albumArtist,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { vm.open(Screen.Artist(first.albumArtist)) },
                            )
                            val total = tracks.sumOf { it.durationMs }
                            Text(
                                listOfNotNull(
                                    first.year.takeIf { it > 0 }?.toString(),
                                    "${tracks.size} songs",
                                    fmtDuration(total).ifEmpty { null },
                                    first.genre.ifEmpty { null },
                                ).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                PlayShuffleRow(vm, tracks)
            }
        })
    }
}

@Composable
fun ArtistScreen(vm: MainViewModel, s: Screen.Artist) {
    val albums by remember(s.name) { vm.dao.artistAlbums(s.name) }.collectAsStateWithLifecycle(emptyList())
    val tracks by remember(s.name) { vm.dao.artistTracks(s.name) }.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(vm, s.name) { CollectionMenu(vm, tracks) }
        LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), contentPadding = PaddingValues(10.dp), modifier = Modifier.fillMaxSize()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(
                        "${albums.size} album${if (albums.size == 1) "" else "s"} • ${tracks.size} songs",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    PlayShuffleRow(vm, tracks)
                }
            }
            items(albums, key = { it.albumKey }) { a -> AlbumCard(a) { vm.open(Screen.Album(a.albumKey, a.album)) } }
        }
    }
}

@Composable
fun GenreScreen(vm: MainViewModel, s: Screen.Genre) {
    val tracks by remember(s.name) { vm.dao.genreTracks(s.name) }.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(vm, s.name.ifEmpty { "Unknown genre" }) { CollectionMenu(vm, tracks) }
        TrackList(vm, tracks, header = { PlayShuffleRow(vm, tracks) })
    }
}

@Composable
fun FolderScreen(vm: MainViewModel, s: Screen.Folder) {
    val folders by remember(s.id) { vm.dao.childFolders(s.id) }.collectAsStateWithLifecycle(emptyList())
    val tracks by remember(s.id) { vm.dao.folderTracks(s.id) }.collectAsStateWithLifecycle(emptyList())
    val np by vm.player.state.collectAsStateWithLifecycle()
    val dl by vm.downloadStates.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var addTarget by remember { mutableStateOf<List<Track>?>(null) }
    val actions = rememberTrackActions(vm, { addTarget = listOf(it) })
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(vm, s.name) { if (tracks.isNotEmpty()) CollectionMenu(vm, tracks) }
        LazyColumn(Modifier.fillMaxSize()) {
            if (tracks.isNotEmpty()) item { PlayShuffleRow(vm, tracks) }
            items(folders, key = { "f" + it.id }) { f ->
                SimpleRow(f.name, null, f.coverImageId?.let { net.kravos.player.drive.DriveApi.mediaUrl(it) }, Icons.Default.Folder) {
                    vm.open(Screen.Folder(f.id, f.name))
                }
            }
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                net.kravos.player.ui.components.TrackRow(t, i, np.mediaId == t.id, dl[t.id], actions, showArt = true) { vm.play(tracks, i) }
            }
            if (folders.isEmpty() && tracks.isEmpty()) item { EmptyState("Empty folder") }
        }
    }
    addTarget?.let { target ->
        AddToPlaylistDialog(playlists, { addTarget = null }, { vm.addToPlaylist(it, target); addTarget = null }, { vm.createPlaylist(it, target); addTarget = null })
    }
}

@Composable
fun PlaylistDetailScreen(vm: MainViewModel, s: Screen.PlaylistScreen) {
    val tracks by remember(s.id) { vm.dao.playlistTracks(s.id) }.collectAsStateWithLifecycle(emptyList())
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(vm, s.name) {
            CollectionMenu(vm, tracks) { close ->
                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { close(); rename = true })
                DropdownMenuItem(text = { Text("Delete playlist") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { close(); delete = true })
            }
        }
        if (tracks.isEmpty()) {
            EmptyState("Empty playlist. Use “Add to playlist…” from any song, album or the queue.")
        } else {
            TrackList(vm, tracks, playlistId = s.id, header = { PlayShuffleRow(vm, tracks) })
        }
    }
    if (rename) TextInputDialog("Rename playlist", s.name, "Rename", { rename = false }) { vm.renamePlaylist(s.id, it); rename = false }
    if (delete) {
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text("Delete “${s.name}”?") },
            text = { Text("Songs stay in your library and on Drive.") },
            confirmButton = { TextButton(onClick = { delete = false; vm.deletePlaylist(s.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SmartScreen(vm: MainViewModel, s: Screen.Smart) {
    val tracks by remember(s.kind) { vm.smartTracks(s.kind) }.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(vm, s.kind.label) { CollectionMenu(vm, tracks) }
        if (tracks.isEmpty()) EmptyState("Nothing here yet") else TrackList(vm, tracks, header = { PlayShuffleRow(vm, tracks) })
    }
}

@Composable
fun SectionSpacer() = Spacer(Modifier.height(8.dp).fillMaxWidth())
