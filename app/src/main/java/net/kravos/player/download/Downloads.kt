package net.kravos.player.download

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kravos.player.App
import net.kravos.player.R
import net.kravos.player.data.Track
import net.kravos.player.drive.DriveApi
import net.kravos.player.playback.POD_PREFIX
import net.kravos.player.podcast.Episode
import org.json.JSONObject
import java.util.concurrent.Executors

/** Offline copies of tracks, kept in a separate never-evicted cache. */
@OptIn(UnstableApi::class)
class Downloads(
    private val context: Context,
    val manager: DownloadManager,
    private val scope: CoroutineScope,
    private val cacheBytes: () -> Long,
    /** Episode URLs that have (or are getting) an offline copy; the player reads those from the offline cache. */
    private val offlineUris: MutableSet<String>,
) {
    /** trackId -> [Download] state constant. */
    private val _states = MutableStateFlow<Map<String, Int>>(emptyMap())
    val states: StateFlow<Map<String, Int>> = _states.asStateFlow()

    /** Downloaded podcast episodes, by download id ("pod:<key>"), rebuilt from the download index. */
    private val _episodes = MutableStateFlow<Map<String, Episode>>(emptyMap())
    val podcastEpisodes: StateFlow<Map<String, Episode>> = _episodes.asStateFlow()

    private val _bytes = MutableStateFlow(0L)
    val bytes: StateFlow<Long> = _bytes.asStateFlow()

    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = reload()
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                _states.value = _states.value + (download.request.id to download.state)
                track(download)
                if (download.state == Download.STATE_COMPLETED) _bytes.value = cacheBytes()
            }
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                _states.value = _states.value - download.request.id
                if (download.request.id.startsWith(POD_PREFIX)) {
                    offlineUris.remove(download.request.uri.toString())
                    _episodes.value = _episodes.value - download.request.id
                }
                _bytes.value = cacheBytes()
            }
        })
        reload()
    }

    private fun episodeOf(d: Download): Episode? =
        runCatching { Episode.fromJson(JSONObject(String(d.request.data))) }.getOrNull()

    private fun track(d: Download) {
        if (!d.request.id.startsWith(POD_PREFIX)) return
        offlineUris.add(d.request.uri.toString())
        if (!_episodes.value.containsKey(d.request.id)) episodeOf(d)?.let { _episodes.value = _episodes.value + (d.request.id to it) }
    }

    private fun reload() {
        scope.launch {
            val all = withContext(Dispatchers.IO) {
                val list = ArrayList<Download>()
                runCatching {
                    manager.downloadIndex.getDownloads().use { c ->
                        while (c.moveToNext()) list += c.download
                    }
                }
                list
            }
            val map = HashMap<String, Int>()
            all.forEach { map[it.request.id] = it.state; track(it) }
            _states.value = map
            _bytes.value = withContext(Dispatchers.IO) { cacheBytes() }
        }
    }

    fun download(tracks: List<Track>) {
        tracks.forEach { t ->
            val req = DownloadRequest.Builder(t.id, Uri.parse(DriveApi.mediaUrl(t.id)))
                .setMimeType(t.mimeType)
                .setData(t.title.toByteArray())
                .build()
            DownloadService.sendAddDownload(context, KravosDownloadService::class.java, req, false)
        }
    }

    /** Keeps a podcast episode for offline listening (same never-evicted store as songs). */
    fun downloadEpisode(ep: Episode) {
        val req = DownloadRequest.Builder(POD_PREFIX + ep.key, Uri.parse(ep.url))
            .setData(ep.toJson().toString().toByteArray())
            .build()
        offlineUris.add(ep.url)
        DownloadService.sendAddDownload(context, KravosDownloadService::class.java, req, false)
    }

    fun remove(ids: Collection<String>) {
        ids.forEach { DownloadService.sendRemoveDownload(context, KravosDownloadService::class.java, it, false) }
    }

    fun removeAll() {
        DownloadService.sendRemoveAllDownloads(context, KravosDownloadService::class.java, false)
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        val req = Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
        DownloadService.sendSetRequirements(context, KravosDownloadService::class.java, req, false)
    }

    fun isDownloaded(id: String) = _states.value[id] == Download.STATE_COMPLETED

    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 42

        fun requirements(wifiOnly: Boolean) =
            Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

        fun newManager(app: App): DownloadManager {
            val g = app.graph
            return DownloadManager(app, g.databaseProvider, g.downloadCache, g.httpDataSourceFactory, Executors.newFixedThreadPool(3)).apply {
                maxParallelDownloads = 2
                requirements = requirements(g.prefs.wifiOnlyDownloads.value)
            }
        }
    }
}

@OptIn(UnstableApi::class)
class KravosDownloadService : DownloadService(
    Downloads.NOTIFICATION_ID,
    DownloadService.DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    Downloads.CHANNEL_ID,
    R.string.download_channel,
    0,
) {
    private val graph get() = (application as App).graph
    private val helper by lazy { DownloadNotificationHelper(this, Downloads.CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = graph.downloads.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        helper.buildProgressNotification(this, R.drawable.ic_notification, null, null, downloads, notMetRequirements)
}
