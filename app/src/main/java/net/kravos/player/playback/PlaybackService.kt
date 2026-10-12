package net.kravos.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.android.gms.cast.framework.CastContext
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kravos.player.App
import net.kravos.player.LoopPoints
import net.kravos.player.SleepTimer
import net.kravos.player.cast.CastProxyServer
import net.kravos.player.drive.DriveApi
import net.kravos.player.ui.MainActivity
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val graph get() = (application as App).graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var exo: ExoPlayer
    private var castPlayer: CastPlayer? = null
    private var session: MediaSession? = null
    private lateinit var proxy: CastProxyServer

    /** Exo-ready versions of every item we've seen, so we can switch back from Cast without losing metadata. */
    private val known = HashMap<String, MediaItem>()
    private var lastCountedId: String? = null

    override fun onCreate() {
        super.onCreate()
        proxy = CastProxyServer(this, graph.http, File(filesDir, "art"))

        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(graph.playbackDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bitmapLoader = CacheBitmapLoader(
            DataSourceBitmapLoader(
                MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
                DefaultDataSource.Factory(this, OkHttpDataSource.Factory(graph.http)),
            ),
        )
        session = MediaSession.Builder(this, exo)
            .setCallback(Callback())
            .setSessionActivity(openApp)
            .setBitmapLoader(bitmapLoader)
            .build()

        initCast()
        restoreQueue()
        scope.launch { graph.extras.loop.collect { onLoopChanged(it) } }
        scope.launch { graph.extras.sleep.collect { onSleepChanged(it) } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            saveQueue()
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveQueue()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        session?.release()
        session = null
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        exo.release()
        proxy.stop()
        super.onDestroy()
    }

    // ------------------------------------------------------------ media items

    private fun resolve(item: MediaItem, forCast: Boolean): MediaItem {
        val id = item.mediaId
        val meta = item.mediaMetadata
        val mime = meta.extras?.getString(EXTRA_MIME) ?: item.localConfiguration?.mimeType
        val exoItem = known[id]?.takeIf { meta.extras == null } ?: MediaItem.Builder()
            .setMediaId(id)
            .setUri(DriveApi.mediaUrl(id))
            .setMimeType(mime)
            .setMediaMetadata(meta)
            .build()
        known[id] = exoItem
        if (!forCast) return exoItem
        val url = proxy.trackUrl(id, mime) ?: return exoItem
        val art = proxy.artUrl(exoItem.mediaMetadata.extras?.getString(EXTRA_ART))
        return exoItem.buildUpon()
            .setUri(url)
            .setMimeType(mime ?: "audio/mpeg")
            .setMediaMetadata(
                exoItem.mediaMetadata.buildUpon()
                    .setArtworkUri(art?.let(android.net.Uri::parse))
                    .build(),
            )
            .build()
    }

    private inner class Callback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val forCast = mediaSession.player !== exo
            val resolved: MutableList<MediaItem> = mediaItems.map { resolve(it, forCast) }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    private fun restoreQueue() {
        val (ids, index, pos) = graph.prefs.loadQueue()
        if (ids.isEmpty()) return
        scope.launch {
            val tracks = withContext(Dispatchers.IO) {
                ids.chunked(500).flatMap { graph.db.dao().tracksByIds(it) }
            }.associateBy { it.id }
            val items = ids.mapNotNull { tracks[it] }.map { resolve(it.toMediaItem(), false) }
            if (items.isEmpty() || exo.mediaItemCount > 0) return@launch
            val currentId = ids.getOrNull(index)
            val start = items.indexOfFirst { it.mediaId == currentId }.coerceAtLeast(0)
            exo.setMediaItems(items, start, pos)
            lastCountedId = items[start].mediaId
        }
    }

    private fun saveQueue() {
        val p = session?.player ?: return
        val ids = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        graph.prefs.saveQueue(ids, p.currentMediaItemIndex, p.currentPosition)
    }

    // ------------------------------------------------------------ listener

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) lastCountedId = null
            graph.extras.loop.value = LoopPoints()
            countPlay()
            session?.player?.let { graph.prefs.savePosition(it.currentMediaItemIndex, 0L) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) countPlay() else session?.player?.let { graph.prefs.savePosition(it.currentMediaItemIndex, it.currentPosition) }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveQueue()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                if (graph.extras.sleep.value.endOfTrack) graph.extras.sleep.value = SleepTimer()
            }
        }
    }

    private fun countPlay() {
        val p = session?.player ?: return
        if (!p.isPlaying) return
        val id = p.currentMediaItem?.mediaId ?: return
        if (id == lastCountedId) return
        lastCountedId = id
        val now = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) { runCatching { graph.db.dao().markPlayed(id, now) } }
    }

    // ------------------------------------------------------------ A-B loop

    private val loopTick = object : Runnable {
        override fun run() {
            val lp = graph.extras.loop.value
            val p = session?.player ?: return
            if (!lp.active) return
            if (p.currentPosition >= lp.b) p.seekTo(lp.a)
            handler.postDelayed(this, 20)
        }
    }

    private fun onLoopChanged(lp: LoopPoints) {
        handler.removeCallbacks(loopTick)
        if (lp.active) {
            val p = session?.player
            if (p != null && (p.currentPosition < lp.a || p.currentPosition > lp.b)) p.seekTo(lp.a)
            handler.post(loopTick)
        }
    }

    // ------------------------------------------------------------ sleep timer

    private var fadeStep = 0

    private val sleepTick = Runnable {
        fadeStep = 0
        handler.post(fadeTick)
    }

    private val fadeTick = object : Runnable {
        override fun run() {
            val p = session?.player ?: return
            fadeStep++
            if (fadeStep >= FADE_STEPS) {
                p.pause()
                p.volume = 1f
                graph.extras.sleep.value = SleepTimer()
                return
            }
            p.volume = 1f - fadeStep.toFloat() / FADE_STEPS
            handler.postDelayed(this, FADE_MS / FADE_STEPS)
        }
    }

    private fun onSleepChanged(t: SleepTimer) {
        handler.removeCallbacks(sleepTick)
        handler.removeCallbacks(fadeTick)
        session?.player?.volume = 1f
        exo.pauseAtEndOfMediaItems = t.endOfTrack
        if (t.endsAt > 0) {
            val delay = max(0L, t.endsAt - System.currentTimeMillis() - FADE_MS)
            handler.postDelayed(sleepTick, delay)
        }
    }

    // ------------------------------------------------------------ Cast

    private fun initCast() {
        val ctx = try {
            @Suppress("DEPRECATION")
            CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            Log.i(TAG, "Cast unavailable: ${e.message}")
            null
        } ?: return
        val cp = CastPlayer(ctx)
        cp.addListener(listener)
        cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = switchTo(cp)
            override fun onCastSessionUnavailable() = switchTo(exo)
        })
        castPlayer = cp
        if (cp.isCastSessionAvailable) switchTo(cp)
    }

    private fun switchTo(target: Player) {
        val s = session ?: return
        val prev = s.player
        if (prev === target) return
        val toCast = target !== exo
        if (toCast && !proxy.start()) {
            Log.w(TAG, "No LAN address; staying on the phone")
            return
        }
        val count = prev.mediaItemCount
        var index = prev.currentMediaItemIndex
        val pos = prev.currentPosition
        val playWhenReady = prev.playWhenReady
        var items = (0 until count).map { prev.getMediaItemAt(it) }
        if (toCast && items.size > CAST_WINDOW * 2) {
            val from = max(0, index - CAST_WINDOW)
            val to = min(items.size, index + CAST_WINDOW)
            items = items.subList(from, to)
            index -= from
        }
        val converted = items.map { resolve(known[it.mediaId] ?: it, toCast) }
        prev.stop()
        if (prev === exo) exo.clearMediaItems()
        target.repeatMode = prev.repeatMode
        target.shuffleModeEnabled = prev.shuffleModeEnabled
        if (converted.isNotEmpty()) {
            target.setMediaItems(converted, index.coerceIn(0, converted.size - 1), pos)
            target.playWhenReady = playWhenReady
            target.prepare()
        }
        s.player = target
        if (!toCast) proxy.stop()
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val FADE_MS = 8000L
        private const val FADE_STEPS = 16
        private const val CAST_WINDOW = 50
    }
}
