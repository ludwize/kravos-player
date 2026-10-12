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
import java.util.concurrent.Executors

/** Offline copies of tracks, kept in a separate never-evicted cache. */
@OptIn(UnstableApi::class)
class Downloads(
    private val context: Context,
    val manager: DownloadManager,
    private val scope: CoroutineScope,
    private val cacheBytes: () -> Long,
) {
    /** trackId -> [Download] state constant. */
    private val _states = MutableStateFlow<Map<String, Int>>(emptyMap())
    val states: StateFlow<Map<String, Int>> = _states.asStateFlow()

    private val _bytes = MutableStateFlow(0L)
    val bytes: StateFlow<Long> = _bytes.asStateFlow()

    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = reload()
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                _states.value = _states.value + (download.request.id to download.state)
                if (download.state == Download.STATE_COMPLETED) _bytes.value = cacheBytes()
            }
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                _states.value = _states.value - download.request.id
                _bytes.value = cacheBytes()
            }
        })
        reload()
    }

    private fun reload() {
        scope.launch {
            val map = withContext(Dispatchers.IO) {
                val m = HashMap<String, Int>()
                runCatching {
                    manager.downloadIndex.getDownloads().use { c ->
                        while (c.moveToNext()) m[c.download.request.id] = c.download.state
                    }
                }
                m
            }
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
