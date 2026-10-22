package net.kravos.player.ui

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.offline.Download
import com.google.android.gms.auth.api.identity.AuthorizationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import net.kravos.player.App
import net.kravos.player.LoopPoints
import net.kravos.player.SleepTimer
import net.kravos.player.data.Playlist
import net.kravos.player.data.Track
import net.kravos.player.drive.DriveApi
import net.kravos.player.drive.DriveFile
import net.kravos.player.drive.LibraryScanner
import net.kravos.player.playback.POD_PREFIX
import net.kravos.player.playback.toEpisode
import net.kravos.player.playback.toMediaItem
import net.kravos.player.podcast.Episode
import net.kravos.player.podcast.Podcast
import net.kravos.player.radio.RadioStation

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
    data object Radio : Screen
    data object Podcasts : Screen
    data object DriveBrowser : Screen
}

/** Where you are inside the Podcasts tab; kept when you switch to another tab and come back. */
sealed interface PodScreen {
    data object Home : PodScreen
    data object Search : PodScreen
    data class Show(val podcast: Podcast) : PodScreen
}

data class ShowState(
    val podcast: Podcast,
    val episodes: List<Episode> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val offline: Boolean = false,
    val loadedAt: Long = 0L,
)

data class PodListState(
    val loading: Boolean = false,
    val error: String? = null,
    val items: List<Podcast> = emptyList(),
    val loaded: Boolean = false,
)

enum class PodChart(val label: String, val countries: List<String>) {
    SERBIA("Serbia", listOf("rs")),
    WORLD("World", listOf("us", "gb")),
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
        if (current == Screen.Podcasts && podBack()) return true
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

    // ================================================================ radio

    enum class RadioTab(val label: String) { FAVORITES("Favorites"), SERBIA("Serbia"), WORLD("World") }

    data class RadioListState(
        val loading: Boolean = false,
        val error: String? = null,
        val stations: List<RadioStation> = emptyList(),
        val query: String = "",
        val loadedOnce: Boolean = false,
    )

    var radioTab by mutableStateOf(if (graph.prefs.radioFavorites.value.isEmpty()) RadioTab.SERBIA else RadioTab.FAVORITES)
    var radioSortByQuality by mutableStateOf(false)
    private val _radioSerbia = MutableStateFlow(RadioListState())
    val radioSerbia: StateFlow<RadioListState> = _radioSerbia.asStateFlow()
    private val _radioWorld = MutableStateFlow(RadioListState())
    val radioWorld: StateFlow<RadioListState> = _radioWorld.asStateFlow()
    private val stationCache = HashMap<String, RadioStation>()
    private val _radioMessage = MutableStateFlow<String?>(null)
    val radioMessage: StateFlow<String?> = _radioMessage.asStateFlow()

    fun loadRadio(tab: RadioTab, query: String = "", force: Boolean = false) {
        val flow = when (tab) {
            RadioTab.SERBIA -> _radioSerbia
            RadioTab.WORLD -> _radioWorld
            RadioTab.FAVORITES -> return
        }
        val cur = flow.value
        if (!force && cur.loadedOnce && cur.query == query && cur.error == null) return
        flow.value = cur.copy(loading = true, error = null, query = query)
        viewModelScope.launch {
            try {
                val list = graph.radio.search(
                    name = query.ifBlank { null },
                    countryCode = if (tab == RadioTab.SERBIA) "RS" else null,
                    order = if (tab == RadioTab.WORLD && query.isBlank()) "votes" else "clickcount",
                    limit = if (tab == RadioTab.SERBIA) 300 else 400,
                )
                list.forEach { stationCache[it.id] = it }
                if (flow.value.query == query) flow.value = RadioListState(false, null, list, query, true)
            } catch (e: Exception) {
                if (flow.value.query == query) {
                    flow.value = flow.value.copy(loading = false, error = "Can't reach the radio directory (${e.message ?: "network"})", loadedOnce = true)
                }
            }
        }
    }

    fun filterStations(list: List<RadioStation>): List<RadioStation> {
        val castOnly = graph.prefs.radioCastOnly.value
        val goodOnly = graph.prefs.radioGoodOnly.value
        val filtered = list.filter { (!castOnly || it.castReady) && (!goodOnly || it.goodQuality) }
        return if (radioSortByQuality) filtered.sortedWith(compareByDescending<RadioStation> { it.highQuality }.thenByDescending { it.bitrate }) else filtered
    }

    /** Plays [st]; the rest of [list] becomes the queue so next/previous switch stations. */
    fun playStation(st: RadioStation, list: List<RadioStation>) {
        stationCache[st.id] = st
        viewModelScope.launch {
            val castUrl = runCatching { graph.radio.castableUrl(st) }.getOrNull()
            val window = list.ifEmpty { listOf(st) }
            val idx = window.indexOfFirst { it.id == st.id }.coerceAtLeast(0)
            val from = (idx - 50).coerceAtLeast(0)
            val slice = window.subList(from, minOf(window.size, idx + 50))
            val items = slice.map { s -> s.toMediaItem(if (s.id == st.id) castUrl else s.url.takeIf { s.isHttps }) }
            player.playItems(items, idx - from)
            if (castUrl == null) {
                _radioMessage.value = "“${st.name}” has no HTTPS stream: casting works only while the phone is on the same Wi-Fi."
            }
            graph.radio.click(st.id)
        }
    }

    fun clearRadioMessage() {
        _radioMessage.value = null
    }

    fun toggleFavorite(st: RadioStation) {
        stationCache[st.id] = st
        graph.prefs.toggleFavorite(st)
    }

    fun toggleFavoriteById(id: String?) {
        id ?: return
        val st = graph.prefs.radioFavorites.value.firstOrNull { it.id == id } ?: stationCache[id] ?: return
        graph.prefs.toggleFavorite(st)
    }

    fun addCustomStation(name: String, url: String) {
        viewModelScope.launch {
            val clean = url.trim()
            if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) {
                _radioMessage.value = "The address must start with http:// or https://"
                return@launch
            }
            val resolved = graph.radio.resolveStreamUrl(clean)
            val lower = resolved.lowercase()
            val st = RadioStation(
                id = "custom-" + Integer.toHexString(resolved.hashCode()),
                name = name.trim().ifEmpty { resolved.substringAfter("://").substringBefore('/') },
                url = resolved,
                favicon = null,
                homepage = null,
                codec = when {
                    lower.contains("aac") -> "AAC"
                    lower.contains(".ogg") || lower.contains("opus") -> "OGG"
                    lower.contains("flac") -> "FLAC"
                    else -> ""
                },
                bitrate = 0,
                countryCode = "",
                tags = "",
                hls = lower.substringBefore('?').endsWith(".m3u8"),
            )
            if (!graph.prefs.isFavorite(st.id)) graph.prefs.toggleFavorite(st)
            radioTab = RadioTab.FAVORITES
            _radioMessage.value = "Added “${st.name}” to favorites"
        }
    }

    // ================================================================ Drive browser

    data class BrowserState(
        val path: List<Pair<String, String>> = emptyList(), // (id, name); empty = top level
        val folders: List<DriveFile> = emptyList(),
        val files: List<DriveFile> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val searchQuery: String? = null,
        val busy: String? = null,
    )

    private val _browser = MutableStateFlow(BrowserState())
    val browser: StateFlow<BrowserState> = _browser.asStateFlow()

    fun browserOpen(path: List<Pair<String, String>>) {
        _browser.value = BrowserState(path = path, loading = path.isNotEmpty())
        if (path.isEmpty()) return
        val id = path.last().first
        viewModelScope.launch {
            try {
                val items = if (id == SHARED) graph.drive.sharedFolders() else graph.drive.listChildren(id)
                if (_browser.value.path == path && _browser.value.searchQuery == null) {
                    _browser.value = BrowserState(
                        path = path,
                        folders = items.filter { it.isFolder },
                        files = items.filter { it.extension in DriveApi.AUDIO_EXTENSIONS },
                    )
                }
            } catch (e: Exception) {
                if (_browser.value.path == path) _browser.value = BrowserState(path, error = LibraryScanner.describe(e))
            }
        }
    }

    fun browserBack(): Boolean {
        val b = _browser.value
        if (b.searchQuery != null) {
            browserOpen(b.path)
            return true
        }
        if (b.path.isEmpty()) return false
        browserOpen(b.path.dropLast(1))
        return true
    }

    fun browserSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val path = _browser.value.path
        _browser.value = BrowserState(path = path, loading = true, searchQuery = q)
        viewModelScope.launch {
            try {
                val esc = q.replace("\\", "\\\\").replace("'", "\\'")
                val items = graph.drive.query(
                    "name contains '$esc' and trashed = false and (mimeType = '${DriveApi.FOLDER_MIME}' or mimeType contains 'audio/')",
                )
                if (_browser.value.searchQuery == q) {
                    _browser.value = BrowserState(
                        path = path,
                        folders = items.filter { it.isFolder },
                        files = items.filter { it.extension in DriveApi.AUDIO_EXTENSIONS },
                        searchQuery = q,
                    )
                }
            } catch (e: Exception) {
                if (_browser.value.searchQuery == q) _browser.value = BrowserState(path, error = LibraryScanner.describe(e), searchQuery = q)
            }
        }
    }

    /** Library metadata when we have it, otherwise a bare Drive item whose tags the player reads. */
    private suspend fun itemsFor(files: List<DriveFile>): List<androidx.media3.common.MediaItem> {
        val known = withContext(Dispatchers.IO) { files.map { it.id }.chunked(500).flatMap { dao.tracksByIds(it) } }.associateBy { it.id }
        return files.map { f -> known[f.id]?.toMediaItem() ?: f.toMediaItem() }
    }

    fun playDriveFiles(files: List<DriveFile>, start: Int) {
        viewModelScope.launch { player.playItems(itemsFor(files), start) }
    }

    /** Plays a Drive folder including all subfolders, in folder order. */
    fun playDriveFolder(id: String, name: String, shuffle: Boolean) {
        val before = _browser.value
        _browser.value = before.copy(busy = "Collecting songs in “$name”…")
        viewModelScope.launch {
            try {
                val files = ArrayList<DriveFile>()
                val queue = ArrayDeque(listOf(id))
                val seen = HashSet<String>()
                while (queue.isNotEmpty() && files.size < 3000) {
                    val f = queue.removeFirst()
                    if (!seen.add(f)) continue
                    val children = graph.drive.listChildren(f)
                    files += children.filter { it.extension in DriveApi.AUDIO_EXTENSIONS }.sortedBy { it.name.lowercase() }
                    children.filter { it.isFolder }.sortedBy { it.name.lowercase() }.forEach { queue.add(it.id) }
                    _browser.value = _browser.value.copy(busy = "Collecting songs in “$name”… ${files.size}")
                }
                if (files.isEmpty()) {
                    _browser.value = _browser.value.copy(busy = null, error = "No MP3/FLAC files in “$name”")
                } else {
                    player.playItems(itemsFor(files), 0, shuffle)
                    _browser.value = _browser.value.copy(busy = null)
                }
            } catch (e: Exception) {
                _browser.value = _browser.value.copy(busy = null, error = LibraryScanner.describe(e))
            }
        }
    }

    // ================================================================ library folders (scanned)

    /** All tracks in a scanned folder and its subfolders, album by album. */
    fun playLibraryFolder(id: String, shuffle: Boolean) {
        viewModelScope.launch {
            val tracks = withContext(Dispatchers.IO) {
                val all = dao.allFoldersOnce()
                val children = all.groupBy { it.parentId }
                val order = ArrayList<String>()
                val queue = ArrayDeque(listOf(id))
                while (queue.isNotEmpty()) {
                    val f = queue.removeFirst()
                    order += f
                    children[f]?.sortedBy { it.name.lowercase() }?.forEach { queue.add(it.id) }
                }
                val rank = order.withIndex().associate { it.value to it.index }
                order.chunked(500).flatMap { dao.tracksInFolders(it) }
                    .sortedWith(compareBy<Track>({ rank[it.folderId] ?: 0 }, { it.discNo }, { it.trackNo }, { it.fileName.lowercase() }))
            }
            if (tracks.isNotEmpty()) player.play(tracks, 0, shuffle)
        }
    }

    // ================================================================ podcasts

    val podcasts = graph.podcasts

    val podStack = mutableStateListOf<PodScreen>().apply {
        add(PodScreen.Home)
        graph.podcasts.lastShow()?.let { add(PodScreen.Show(it)) }
    }
    val podCurrent: PodScreen get() = podStack.last()

    private val podListStates = HashMap<String, LazyListState>()

    /** One scroll position per Podcasts sub-screen, so coming back shows the same spot. */
    fun podListState(key: String): LazyListState = podListStates.getOrPut(key) { LazyListState() }

    fun podOpenShow(p: Podcast) {
        podStack.add(PodScreen.Show(p))
        graph.podcasts.setLastShow(p)
    }

    fun podOpenSearch() {
        if (podCurrent != PodScreen.Search) podStack.add(PodScreen.Search)
        graph.podcasts.setLastShow(null)
    }

    fun podBack(): Boolean {
        if (podStack.size <= 1) return false
        podStack.removeAt(podStack.lastIndex)
        graph.podcasts.setLastShow((podStack.last() as? PodScreen.Show)?.podcast)
        return true
    }

    fun podHome() {
        while (podStack.size > 1) podStack.removeAt(podStack.lastIndex)
        graph.podcasts.setLastShow(null)
    }

    private val _shows = MutableStateFlow<Map<String, ShowState>>(emptyMap())
    val shows: StateFlow<Map<String, ShowState>> = _shows.asStateFlow()
    private val _podMessage = MutableStateFlow<String?>(null)
    val podMessage: StateFlow<String?> = _podMessage.asStateFlow()
    private var lastSubsRefresh = 0L

    fun clearPodMessage() {
        _podMessage.value = null
    }

    private fun setShow(s: ShowState) = _shows.update { it + (s.podcast.id to s) }

    private suspend fun fetchShow(p: Podcast) {
        val before = _shows.value[p.id]
        setShow((before ?: ShowState(p)).copy(loading = true, error = null))
        // Show what we saved last time straight away; the network fills in anything newer.
        if (before == null || before.episodes.isEmpty()) {
            graph.podcastApi.cached(p)?.let { c -> setShow(ShowState(c.podcast, c.episodes, loading = true)) }
        }
        try {
            val r = graph.podcastApi.load(p)
            graph.podcasts.refreshSubscribed(r.podcast)
            setShow(ShowState(r.podcast, r.episodes, false, null, r.fromCache, System.currentTimeMillis()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val now = _shows.value[p.id] ?: ShowState(p)
            setShow(now.copy(loading = false, error = "Can't load this feed (${e.message ?: "network"})"))
        }
    }

    fun loadShow(p: Podcast, force: Boolean = false) {
        val cur = _shows.value[p.id]
        val fresh = cur != null && cur.error == null && cur.episodes.isNotEmpty() &&
            System.currentTimeMillis() - cur.loadedAt < FEED_FRESH_MS
        if (!force && (fresh || cur?.loading == true)) return
        viewModelScope.launch { fetchShow(p) }
    }

    /** Refreshes every subscribed show (a few at a time) so "Latest episodes" is current. */
    fun refreshSubscriptions(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSubsRefresh < FEED_FRESH_MS) return
        lastSubsRefresh = now
        val subs = graph.podcasts.subs.value
        val gate = Semaphore(4)
        subs.forEach { p -> viewModelScope.launch { gate.withPermit { fetchShow(p) } } }
    }

    // ---- directory: search and charts

    var podQuery by mutableStateOf("")
    var podChart by mutableStateOf(PodChart.SERBIA)
    private val _podSearch = MutableStateFlow(PodListState())
    val podSearchState: StateFlow<PodListState> = _podSearch.asStateFlow()
    private val _podCharts = MutableStateFlow<Map<PodChart, PodListState>>(emptyMap())
    val podCharts: StateFlow<Map<PodChart, PodListState>> = _podCharts.asStateFlow()
    private var podSearchJob: Job? = null

    fun podSearch(query: String) {
        val q = query.trim()
        podSearchJob?.cancel()
        if (q.length < 2) {
            _podSearch.value = PodListState()
            return
        }
        _podSearch.value = _podSearch.value.copy(loading = true, error = null)
        podSearchJob = viewModelScope.launch {
            try {
                _podSearch.value = PodListState(false, null, graph.podcastApi.search(q), true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _podSearch.value = PodListState(false, "Search failed (${e.message ?: "network"})", emptyList(), true)
            }
        }
    }

    fun loadChart(chart: PodChart, force: Boolean = false) {
        val cur = _podCharts.value[chart]
        if (!force && cur != null && (cur.loading || (cur.loaded && cur.error == null))) return
        _podCharts.update { it + (chart to PodListState(loading = true)) }
        viewModelScope.launch {
            var error: String? = null
            var list: List<Podcast> = emptyList()
            for (country in chart.countries) {
                try {
                    list = graph.podcastApi.top(country)
                    error = null
                    if (list.isNotEmpty()) break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = "Can't load the chart (${e.message ?: "network"})"
                }
            }
            _podCharts.update { it + (chart to PodListState(false, error, list, true)) }
        }
    }

    fun podAddByUrl(url: String) {
        val clean = url.trim()
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) {
            _podMessage.value = "The address must start with http:// or https://"
            return
        }
        viewModelScope.launch {
            try {
                val r = graph.podcastApi.loadByUrl(clean)
                if (!graph.podcasts.isSubscribed(r.podcast.id)) graph.podcasts.toggleSubscription(r.podcast)
                setShow(ShowState(r.podcast, r.episodes, loadedAt = System.currentTimeMillis()))
                podOpenShow(r.podcast)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _podMessage.value = "Can't read that feed (${e.message ?: "network"})"
            }
        }
    }

    // ---- playing

    fun playEpisode(ep: Episode) {
        if (player.state.value.mediaId == POD_PREFIX + ep.key) {
            player.togglePlay()
            return
        }
        player.playItems(listOf(ep.toMediaItem()), 0, false, graph.podcasts.resumePosition(ep.key))
    }

    fun playEpisodeNext(ep: Episode) = player.playNextItems(listOf(ep.toMediaItem()))
    fun enqueueEpisode(ep: Episode) = player.enqueueItems(listOf(ep.toMediaItem()))

    // ---- offline episodes

    val podcastDownloads: StateFlow<Map<String, Episode>> = graph.downloads.podcastEpisodes

    fun downloadEpisode(ep: Episode) = graph.downloads.downloadEpisode(ep)
    fun removeEpisodeDownload(key: String) = graph.downloads.remove(listOf(POD_PREFIX + key))

    /** Download button on the player for the episode that's playing. */
    fun toggleCurrentEpisodeDownload() {
        val ep = player.controller?.currentMediaItem?.toEpisode() ?: return
        val state = graph.downloads.states.value[POD_PREFIX + ep.key]
        if (state == null || state == Download.STATE_FAILED) downloadEpisode(ep) else removeEpisodeDownload(ep.key)
    }

    fun setEpisodePlayed(key: String, played: Boolean) {
        if (played) graph.podcasts.markPlayed(key) else graph.podcasts.markUnplayed(key)
    }

    /** Speed applies to podcasts only; the service resets it to 1× for music and radio. */
    fun setPodcastSpeed(showId: String?, speed: Float, forAll: Boolean) {
        if (forAll) graph.podcasts.setSpeedForAll(speed) else showId?.let { graph.podcasts.setSpeed(it, speed) }
        player.setSpeedPitch(speed, 0)
    }

    companion object {
        const val SHARED = "__shared__"
        private const val FEED_FRESH_MS = 10 * 60_000L
    }
}
