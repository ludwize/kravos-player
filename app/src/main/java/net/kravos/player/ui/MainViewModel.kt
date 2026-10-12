package net.kravos.player.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.offline.Download
import com.google.android.gms.auth.api.identity.AuthorizationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kravos.player.App
import net.kravos.player.LoopPoints
import net.kravos.player.SleepTimer
import net.kravos.player.data.Playlist
import net.kravos.player.data.Track
import net.kravos.player.drive.DriveFile
import net.kravos.player.drive.LibraryScanner

sealed interface Screen {
    data object Library : Screen
    data object Playlists : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Album(val key: String, val title: String) : Screen
    data class Artist(val name: String) : Screen
    data class Genre(val name: String) : Screen
    data class Folder(val id: String, val name: String) : Screen
    data class PlaylistScreen(val id: Long, val name: String) : Screen
    data class Smart(val kind: SmartKind) : Screen
    data object FolderPicker : Screen
}

enum class SmartKind(val label: String) {
    RECENTLY_ADDED("Recently added"),
    MOST_PLAYED("Most played"),
    RECENTLY_PLAYED("Recently played"),
    DOWNLOADED("Downloaded"),
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val graph = (app as App).graph
    val dao = graph.db.dao()
    val player = graph.player

    // ---- navigation
    val stack = mutableStateListOf<Screen>(Screen.Library)
    var showPlayer by mutableStateOf(false)
    var libraryTab by mutableStateOf(0)
    val current: Screen get() = stack.last()
    val root: Screen get() = stack.first()

    fun open(s: Screen) {
        showPlayer = false
        stack.add(s)
    }

    fun back(): Boolean {
        if (showPlayer) {
            showPlayer = false
            return true
        }
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        return false
    }

    fun selectRoot(s: Screen) {
        showPlayer = false
        stack.clear()
        stack.add(s)
    }

    // ---- library data
    private fun <T> Flow<List<T>>.state(): StateFlow<List<T>> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val songs = dao.songs().state()
    val albums = dao.albums().state()
    val artists = dao.artists().state()
    val genres = dao.genres().state()
    val rootFolders = dao.rootFolders().state()
    val playlists = dao.playlists().state()
    val trackCount = dao.trackCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val downloadStates = graph.downloads.states
    val downloaded: StateFlow<List<Track>> = combine(dao.songs(), graph.downloads.states) { s, st ->
        s.filter { st[it.id] == Download.STATE_COMPLETED }
    }.state()

    fun smartTracks(kind: SmartKind): Flow<List<Track>> = when (kind) {
        SmartKind.RECENTLY_ADDED -> dao.recentlyAdded()
        SmartKind.MOST_PLAYED -> dao.mostPlayed()
        SmartKind.RECENTLY_PLAYED -> dao.recentlyPlayed()
        SmartKind.DOWNLOADED -> downloaded
    }

    // ---- playback
    fun play(tracks: List<Track>, index: Int = 0) {
        player.play(tracks, index)
    }

    fun shuffle(tracks: List<Track>) = player.play(tracks, shuffle = true)
    fun playNext(tracks: List<Track>) = player.playNext(tracks)
    fun enqueue(tracks: List<Track>) = player.enqueue(tracks)

    fun setSleep(minutes: Int?, endOfTrack: Boolean = false) {
        graph.extras.sleep.value = when {
            endOfTrack -> SleepTimer(endOfTrack = true)
            minutes == null || minutes <= 0 -> SleepTimer()
            else -> SleepTimer(endsAt = System.currentTimeMillis() + minutes * 60_000L)
        }
    }

    fun setLoopA(ms: Long) {
        val lp = graph.extras.loop.value
        graph.extras.loop.value = LoopPoints(ms, if (lp.b > ms) lp.b else -1)
    }

    fun setLoopB(ms: Long) {
        val lp = graph.extras.loop.value
        if (lp.a in 0 until ms) graph.extras.loop.value = lp.copy(b = ms)
    }

    fun nudgeLoop(a: Long, b: Long) {
        if (a >= 0 && (b < 0 || b > a)) graph.extras.loop.value = LoopPoints(a, b)
    }

    fun clearLoop() {
        graph.extras.loop.value = LoopPoints()
    }

    // ---- downloads
    fun download(tracks: List<Track>) = graph.downloads.download(tracks)
    fun removeDownload(tracks: List<Track>) = graph.downloads.remove(tracks.map { it.id })

    fun toggleDownloadById(id: String) {
        viewModelScope.launch {
            val t = dao.track(id) ?: return@launch
            if (graph.downloads.isDownloaded(id)) removeDownload(listOf(t)) else download(listOf(t))
        }
    }

    // ---- playlists
    fun addToPlaylist(id: Long, tracks: List<Track>) {
        viewModelScope.launch { dao.appendToPlaylist(id, tracks.map { it.id }) }
    }

    fun createPlaylist(name: String, tracks: List<Track>) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = dao.insertPlaylist(Playlist(name = name, createdAt = now, updatedAt = now))
            if (tracks.isNotEmpty()) dao.appendToPlaylist(id, tracks.map { it.id })
        }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch { dao.renamePlaylist(id, name, System.currentTimeMillis()) }
        val i = stack.indexOfLast { it is Screen.PlaylistScreen && it.id == id }
        if (i >= 0) stack[i] = Screen.PlaylistScreen(id, name)
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch { dao.deletePlaylist(id) }
        back()
    }

    fun editPlaylist(id: Long, edit: (MutableList<String>) -> Unit) {
        viewModelScope.launch {
            val ids = dao.playlistTrackIds(id).toMutableList()
            edit(ids)
            dao.setPlaylistTracks(id, ids)
        }
    }

    fun saveQueueAsPlaylist(name: String) {
        val ids = player.queue.value.map { it.mediaId }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = dao.insertPlaylist(Playlist(name = name, createdAt = now, updatedAt = now))
            dao.setPlaylistTracks(id, ids)
        }
    }

    // ---- account / folders
    fun onAuthorized(result: AuthorizationResult) {
        graph.auth.onAuthorized(result)
        viewModelScope.launch {
            runCatching { graph.drive.about() }.getOrNull()?.let(graph.prefs::setAccount)
            if (graph.prefs.roots.value.isEmpty()) open(Screen.FolderPicker) else graph.scanner.start()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.auth.disconnect(graph.plainHttp) }
        }
    }

    // Folder picker state
    data class PickerState(
        val path: List<Pair<String, String>> = emptyList(), // (id, name); empty = top level
        val items: List<DriveFile> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _picker = MutableStateFlow(PickerState())
    val picker: StateFlow<PickerState> = _picker.asStateFlow()

    fun pickerOpen(path: List<Pair<String, String>>) {
        _picker.value = PickerState(path = path, loading = path.isNotEmpty())
        if (path.isEmpty()) return
        val (id, _) = path.last()
        viewModelScope.launch {
            try {
                val items = if (id == SHARED) graph.drive.sharedFolders() else graph.drive.listChildren(id, foldersOnly = true)
                if (_picker.value.path == path) _picker.value = PickerState(path, items, false)
            } catch (e: Exception) {
                if (_picker.value.path == path) _picker.value = PickerState(path, emptyList(), false, LibraryScanner.describe(e))
            }
        }
    }

    fun pickerBack(): Boolean {
        val p = _picker.value.path
        if (p.isEmpty()) return false
        pickerOpen(p.dropLast(1))
        return true
    }

    fun addRoot(id: String, name: String) {
        graph.prefs.addRoot(net.kravos.player.data.MusicRoot(id, name))
        graph.scanner.start()
    }

    fun removeRoot(id: String) {
        graph.prefs.removeRoot(id)
        graph.scanner.start()
    }

    fun clearStreamCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val c = graph.streamCache
            runCatching { c.keys.toList().forEach { c.removeResource(it) } }
        }
    }

    companion object {
        const val SHARED = "__shared__"
    }
}
