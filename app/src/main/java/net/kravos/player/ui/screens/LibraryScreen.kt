package net.kravos.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.kravos.player.data.Track
import net.kravos.player.ui.MainViewModel
import net.kravos.player.ui.Screen
import net.kravos.player.ui.components.AddToPlaylistDialog
import net.kravos.player.ui.components.AlbumCard
import net.kravos.player.ui.components.EmptyState
import net.kravos.player.ui.components.SimpleRow
import net.kravos.player.ui.components.TrackActions
import net.kravos.player.ui.components.TrackRow

private val TABS = listOf("Artists", "Albums", "Songs", "Genres", "Folders")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: MainViewModel) {
    val connected by vm.graph.prefs.connected.collectAsStateWithLifecycle()
    val roots by vm.graph.prefs.roots.collectAsStateWithLifecycle()
    val scan by vm.graph.scanner.state.collectAsStateWithLifecycle()
    val count by vm.trackCount.collectAsStateWithLifecycle()
    val pager = rememberPagerState(initialPage = vm.libraryTab) { TABS.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { vm.libraryTab = pager.currentPage }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Kravos Player") })
        if (scan.running) {
            val progress = if (scan.total > 0) scan.done.toFloat() / scan.total else 0f
            if (scan.total > 0) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                if (scan.total > 0) "${scan.phase} ${scan.done}/${scan.total}" else scan.phase,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (!connected || roots.isEmpty()) {
            if (count == 0) {
                EmptyState(
                    if (!connected) "Connect your Google Drive to start." else "Pick the Drive folder(s) with your music.",
                ) {
                    Button(onClick = { vm.open(if (!connected) Screen.Settings else Screen.FolderPicker) }) {
                        Text(if (!connected) "Connect Google Drive" else "Choose folders")
                    }
                }
                return@Column
            }
        }
        ScrollableTabRow(selectedTabIndex = pager.currentPage, edgePadding = 8.dp) {
            TABS.forEachIndexed { i, t ->
                Tab(selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(t) })
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> ArtistsTab(vm)
                1 -> AlbumsTab(vm)
                2 -> SongsTab(vm)
                3 -> GenresTab(vm)
                else -> FoldersTab(vm)
            }
        }
    }
}

@Composable
private fun ArtistsTab(vm: MainViewModel) {
    val artists by vm.artists.collectAsStateWithLifecycle()
    if (artists.isEmpty()) return EmptyState("No artists yet")
    LazyColumn(Modifier.fillMaxSize()) {
        items(artists, key = { it.name }) { a ->
            SimpleRow(
                title = a.name,
                subtitle = "${a.albumCount} album${if (a.albumCount == 1) "" else "s"} • ${a.trackCount} songs",
                art = a.artUri,
                fallback = Icons.Default.Person,
                roundArt = true,
            ) { vm.open(Screen.Artist(a.name)) }
        }
    }
}

@Composable
private fun AlbumsTab(vm: MainViewModel) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    if (albums.isEmpty()) return EmptyState("No albums yet")
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.albumKey }) { a -> AlbumCard(a) { vm.open(Screen.Album(a.albumKey, a.album)) } }
    }
}

@Composable
private fun SongsTab(vm: MainViewModel) {
    val songs by vm.songs.collectAsStateWithLifecycle()
    if (songs.isEmpty()) return EmptyState("No songs yet")
    TrackList(vm, songs, header = { PlayShuffleRow(vm, songs) })
}

@Composable
private fun GenresTab(vm: MainViewModel) {
    val genres by vm.genres.collectAsStateWithLifecycle()
    if (genres.isEmpty()) return EmptyState("No genres yet")
    LazyColumn(Modifier.fillMaxSize()) {
        items(genres, key = { it.name }) { g ->
            SimpleRow(g.name.ifEmpty { "Unknown genre" }, "${g.trackCount} songs", g.artUri, Icons.Default.Category) {
                vm.open(Screen.Genre(g.name))
            }
        }
    }
}

@Composable
private fun FoldersTab(vm: MainViewModel) {
    val folders by vm.rootFolders.collectAsStateWithLifecycle()
    if (folders.isEmpty()) return EmptyState("No folders scanned yet")
    LazyColumn(Modifier.fillMaxSize()) {
        items(folders, key = { it.id }) { f ->
            SimpleRow(f.name, null, null, Icons.Default.Folder) { vm.open(Screen.Folder(f.id, f.name)) }
        }
    }
}

@Composable
fun PlayShuffleRow(vm: MainViewModel, tracks: List<Track>, extra: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = { vm.play(tracks) }, enabled = tracks.isNotEmpty()) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(6.dp))
            Text("Play")
        }
        FilledTonalButton(onClick = { vm.shuffle(tracks) }, enabled = tracks.isNotEmpty()) {
            Icon(Icons.Default.Shuffle, null)
            Spacer(Modifier.width(6.dp))
            Text("Shuffle")
        }
        Spacer(Modifier.weight(1f))
        extra?.invoke()
    }
}

/** Standard actions for a track row, wired to the view model. */
@Composable
fun rememberTrackActions(
    vm: MainViewModel,
    onAddToPlaylist: (Track) -> Unit,
    playlistId: Long? = null,
    showGoAlbum: Boolean = true,
    showGoArtist: Boolean = true,
): TrackActions = remember(vm, playlistId, showGoAlbum, showGoArtist) {
    TrackActions(
        onPlayNext = { vm.playNext(listOf(it)) },
        onEnqueue = { vm.enqueue(listOf(it)) },
        onAddToPlaylist = onAddToPlaylist,
        onDownload = { vm.download(listOf(it)) },
        onRemoveDownload = { vm.removeDownload(listOf(it)) },
        onGoAlbum = if (showGoAlbum) ({ t: Track -> vm.open(Screen.Album(t.albumKey, t.album)) }) else null,
        onGoArtist = if (showGoArtist) ({ t: Track -> vm.open(Screen.Artist(t.albumArtist)) }) else null,
        onRemoveFromList = playlistId?.let { id -> { i: Int -> vm.editPlaylist(id) { l -> if (i in l.indices) l.removeAt(i) } } },
        onMoveUp = playlistId?.let { id -> { i: Int -> vm.editPlaylist(id) { l -> if (i in 1 until l.size) l.add(i - 1, l.removeAt(i)) } } },
        onMoveDown = playlistId?.let { id -> { i: Int -> vm.editPlaylist(id) { l -> if (i in 0 until l.size - 1) l.add(i + 1, l.removeAt(i)) } } },
    )
}

/** A full-height track list with menus, add-to-playlist dialog and now-playing highlight. */
@Composable
fun TrackList(
    vm: MainViewModel,
    tracks: List<Track>,
    showArt: Boolean = true,
    playlistId: Long? = null,
    showGoAlbum: Boolean = true,
    showGoArtist: Boolean = true,
    header: (@Composable () -> Unit)? = null,
) {
    val np by vm.player.state.collectAsStateWithLifecycle()
    val dl by vm.downloadStates.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var addTarget by remember { mutableStateOf<List<Track>?>(null) }
    val actions = rememberTrackActions(vm, { addTarget = listOf(it) }, playlistId, showGoAlbum, showGoArtist)

    LazyColumn(Modifier.fillMaxSize()) {
        if (header != null) item(key = "__header") { header() }
        itemsIndexed(tracks, key = { i, t -> if (playlistId != null) "$i-${t.id}" else t.id }) { i, t ->
            TrackRow(
                track = t,
                index = i,
                playing = np.mediaId == t.id,
                downloadState = dl[t.id],
                actions = actions,
                showArt = showArt,
            ) { vm.play(tracks, i) }
        }
    }
    addTarget?.let { target ->
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { addTarget = null },
            onPick = { vm.addToPlaylist(it, target); addTarget = null },
            onCreate = { vm.createPlaylist(it, target); addTarget = null },
        )
    }
}
