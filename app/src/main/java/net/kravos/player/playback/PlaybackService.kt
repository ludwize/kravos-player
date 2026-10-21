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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
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
    private var lastWasPodcast = false

    override fun onCreate() {
        super.onCreate()
        proxy = CastProxyServer(this, graph.http, File(filesDir, "art"))

        exo = ExoPlayer.Builder(this)
            // Constant-bitrate seeking: many podcast MP3s have no seek table and would otherwise restart on seek.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    graph.playerDataSourceFactory,
                    DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
                ),
            )
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
            saveProgress()
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveQueue()
        saveProgress()
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

    /** The version of [item] that still carries our extras (Cast hands items back without them). */
    private fun full(item: MediaItem): MediaItem = if (item.mediaMetadata.extras == null) known[item.mediaId] ?: item else item

    private fun resolve(input: MediaItem, forCast: Boolean): MediaItem {
        // Items coming back from the Cast receiver lose our extras; use the version we built earlier.
        val item = if (input.mediaMetadata.extras == null) known[input.mediaId] ?: input else input
        val id = item.mediaId
        val meta = item.mediaMetadata
        val extras = meta.extras
        if (item.isRadio()) {
            val url = extras?.getString(EXTRA_URL) ?: item.localConfiguration?.uri?.toString() ?: return item
            val hls = item.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8 ||
                extras?.getString(EXTRA_CAST_MIME) == "application/x-mpegURL"
            val exoItem = MediaItem.Builder()
                .setMediaId(id)
                .setUri(url)
                .setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else null)
                .setMediaMetadata(meta)
                .build()
            known[id] = exoItem
            if (!forCast) return exoItem
            // HTTPS stream: the Chromecast fetches it itself and keeps going without the phone.
            // HTTP-only stream: relay through the phone (works only while the phone is on the same Wi-Fi).
            val castUrl = extras?.getString(EXTRA_CAST_URL) ?: proxy.radioUrl(id, url) ?: url
            return exoItem.buildUpon()
                .setUri(castUrl)
                .setMimeType(extras?.getString(EXTRA_CAST_MIME) ?: "audio/mpeg")
                .setMediaMetadata(meta.buildUpon().setTitle(extras?.getString(EXTRA_NAME) ?: meta.station).build())
                .build()
        }
        if (item.isPodcast()) {
            val url = extras?.getString(EXTRA_URL) ?: item.localConfiguration?.uri?.toString() ?: return item
            val exoItem = MediaItem.Builder().setMediaId(id).setUri(url).setMediaMetadata(meta).build()
            known[id] = exoItem
            if (!forCast) return exoItem
            // HTTPS file: the Chromecast downloads it itself and keeps going without the phone.
            // Plain HTTP: relay through the phone (with Range, so seeking works).
            val castMime = extras?.getString(EXTRA_CAST_MIME) ?: "audio/mpeg"
            val castUrl = if (url.startsWith("https://", true)) url else proxy.podcastUrl(id, url, castMime) ?: url
            return exoItem.buildUpon().setUri(castUrl).setMimeType(castMime).build()
        }
        val mime = extras?.getString(EXTRA_MIME) ?: item.localConfiguration?.mimeType
        val exoItem = MediaItem.Builder()
            .setMediaId(id)
            .setUri(DriveApi.mediaUrl(id))
            .setMimeType(mime)
            .setMediaMetadata(meta)
            .build()
        known[id] = exoItem
        if (!forCast) return exoItem
        val url = proxy.trackUrl(id, mime) ?: return exoItem
        val art = proxy.artUrl(extras?.getString(EXTRA_ART))
        val title = meta.title ?: extras?.getString(EXTRA_NAME)
        return exoItem.buildUpon()
            .setUri(url)
            .setMimeType(mime ?: "audio/mpeg")
            .setMediaMetadata(meta.buildUpon().setTitle(title).setArtworkUri(art?.let(android.net.Uri::parse)).build())
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
        if (p.currentMediaItem?.isRadio() == true || p.currentMediaItem?.isPodcast() == true) return
        val ids = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
            .filterNot { it.startsWith(RADIO_PREFIX) || it.startsWith(POD_PREFIX) }
        graph.prefs.saveQueue(ids, p.currentMediaItemIndex, p.currentPosition)
    }

    // ------------------------------------------------------------ listener

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) lastCountedId = null
            graph.extras.loop.value = LoopPoints()
            countPlay()
            onPodcastTransition(mediaItem, reason)
            session?.player?.takeIf { mediaItem?.isRadio() != true && mediaItem?.isPodcast() != true }
                ?.let { graph.prefs.savePosition(it.currentMediaItemIndex, 0L) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(podTick)
            if (isPlaying) {
                countPlay()
                if (session?.player?.currentMediaItem?.isPodcast() == true) handler.postDelayed(podTick, POD_SAVE_MS)
            } else {
                saveProgress()
                session?.player?.takeIf { it.currentMediaItem?.isRadio() != true && it.currentMediaItem?.isPodcast() != true }
                    ?.let { graph.prefs.savePosition(it.currentMediaItemIndex, it.currentPosition) }
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val old = oldPosition.mediaItem?.let(::full) ?: return
            if (!old.isPodcast()) return
            val ep = old.toEpisode() ?: return
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                graph.podcasts.markPlayed(ep.key)
            } else if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex || oldPosition.mediaItem?.mediaId != newPosition.mediaItem?.mediaId) {
                graph.podcasts.save(ep, oldPosition.positionMs, ep.durationMs)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val item = session?.player?.currentMediaItem?.let(::full) ?: return
            if (item.isPodcast()) item.toEpisode()?.let { graph.podcasts.markPlayed(it.key) }
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
        val item = p.currentMediaItem ?: return
        if (item.isRadio() || item.isPodcast()) return
        val id = item.mediaId
        if (id == lastCountedId) return
        lastCountedId = id
        val now = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) { runCatching { graph.db.dao().markPlayed(id, now) } }
    }

    // ------------------------------------------------------------ podcasts

    private val podTick = object : Runnable {
        override fun run() {
            saveProgress()
            if (session?.player?.isPlaying == true) handler.postDelayed(this, POD_SAVE_MS)
        }
    }

    /** Remembers where the current podcast episode is; no-op for music and radio. */
    private fun saveProgress() {
        val p = session?.player ?: return
        val item = p.currentMediaItem?.let(::full) ?: return
        if (!item.isPodcast()) return
        val ep = item.toEpisode() ?: return
        val dur = p.duration.takeIf { it > 0 } ?: ep.durationMs
        graph.podcasts.save(ep, p.currentPosition, dur)
    }

    /** Podcasts play at the speed chosen for that show; everything else at normal speed. */
    private fun onPodcastTransition(mediaItem: MediaItem?, reason: Int) {
        val p = session?.player ?: return
        val item = mediaItem?.let(::full)
        val isPod = item?.isPodcast() == true
        if (isPod) {
            val show = item?.mediaMetadata?.extras?.getString(EXTRA_SHOW_ID)
            val speed = graph.podcasts.speedFor(show)
            val cur = p.playbackParameters
            if ((cur.speed != speed || cur.pitch != 1f) && p.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
                p.playbackParameters = PlaybackParameters(speed, 1f)
            }
            // Reached through the queue: pick up where this episode was left.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                val resume = item?.let { graph.podcasts.resumePosition(it.mediaId.removePrefix(POD_PREFIX)) } ?: 0L
                if (resume > 0 && p.currentPosition < 2_000L) p.seekTo(resume)
            }
        } else if (lastWasPodcast && p.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            p.playbackParameters = PlaybackParameters.DEFAULT
        }
        lastWasPodcast = isPod
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
        if (toCast) {
            // Needed only for Drive songs and HTTP-only radio; HTTPS radio goes straight to the Chromecast.
            if (!proxy.start()) Log.w(TAG, "No LAN address for the cast relay")
            // Re-joining a receiver that kept playing on its own (e.g. radio while we were away): adopt it.
            if (target.mediaItemCount > 0 && target.playbackState != Player.STATE_IDLE) {
                prev.pause()
                s.player = target
                return
            }
        }
        val count = prev.mediaItemCount
        var index = prev.currentMediaItemIndex
        val current = prev.currentMediaItem
        val pos = if (current?.isRadio() == true) C.TIME_UNSET else prev.currentPosition
        // Cast → phone happens when the Cast session ends (often because the phone left Wi-Fi):
        // never start blasting from the phone speaker; keep the queue and wait for the user.
        val playWhenReady = toCast && prev.playWhenReady
        var items = (0 until count).map { prev.getMediaItemAt(it) }
        if (toCast && items.size > CAST_WINDOW * 2) {
            val from = max(0, index - CAST_WINDOW)
            val to = min(items.size, index + CAST_WINDOW)
            items = items.subList(from, to)
            index -= from
        }
        val converted = items.map { resolve(known[it.mediaId] ?: it, toCast) }
        if (prev !== exo) {
            // The receiver may be unreachable already; don't let a failing remote call break the hand-over.
            runCatching { prev.stop() }
        } else {
            exo.stop()
            exo.clearMediaItems()
        }
        target.repeatMode = prev.repeatMode
        target.shuffleModeEnabled = prev.shuffleModeEnabled
        if (converted.isNotEmpty()) {
            target.setMediaItems(converted, index.coerceIn(0, converted.size - 1), pos)
            target.playWhenReady = playWhenReady
            if (toCast) target.prepare()
        }
        s.player = target
        if (!toCast) proxy.stop()
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val FADE_MS = 8000L
        private const val FADE_STEPS = 16
        private const val CAST_WINDOW = 50
        private const val POD_SAVE_MS = 10_000L
    }
}
