package net.kravos.player.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.kravos.player.data.Track
import kotlin.random.Random

data class NowPlaying(
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** Uri, URL string or embedded picture bytes — anything Coil can load. */
    val art: Any? = null,
    val format: String = "",
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0L,
    val index: Int = 0,
    val queueSize: Int = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val pitch: Float = 1f,
    val casting: Boolean = false,
    val error: String? = null,
    val isRadio: Boolean = false,
    val isLive: Boolean = false,
    val stationId: String? = null,
    val stationName: String = "",
) {
    val hasItem: Boolean get() = mediaId != null
}

/** UI-side handle to the playback service (MediaController), exposed as flows. */
class PlayerConnection(private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null
    var controller: MediaController? = null
        private set

    private val pending = ArrayList<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = _queue.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) refreshQueue(player)
            refresh(player)
        }
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: run {
                future = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            refreshQueue(c)
            refresh(c)
            val todo = ArrayList(pending)
            pending.clear()
            todo.forEach { it(c) }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else {
            pending += block
            connect()
        }
    }

    private fun refresh(p: Player) {
        val item = p.currentMediaItem
        val im = item?.mediaMetadata
        val cm = p.mediaMetadata // item metadata merged with tags/ICY read from the stream
        val extras = im?.extras
        val radio = item?.isRadio() == true
        val station = extras?.getString(EXTRA_NAME) ?: im?.station?.toString() ?: ""
        val dur = p.duration.takeIf { it > 0 } ?: extras?.getLong(EXTRA_DURATION) ?: 0L
        val title = (cm.title ?: im?.title)?.toString()?.takeIf { it.isNotBlank() }
            ?: extras?.getString(EXTRA_NAME) ?: ""
        val artist = if (radio) {
            if (title == station) "Live radio" else station
        } else {
            (cm.artist ?: im?.artist)?.toString() ?: ""
        }
        val art: Any? = extras?.getString(EXTRA_ART)
            ?: im?.artworkUri
            ?: cm.artworkUri
            ?: cm.artworkData
        _state.value = NowPlaying(
            mediaId = item?.mediaId,
            title = title,
            artist = artist,
            album = if (radio) "" else (cm.albumTitle ?: im?.albumTitle)?.toString() ?: "",
            art = art,
            format = extras?.getString(EXTRA_FORMAT) ?: "",
            isPlaying = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            durationMs = if (radio) 0L else dur,
            index = p.currentMediaItemIndex,
            queueSize = p.mediaItemCount,
            shuffle = p.shuffleModeEnabled,
            repeat = p.repeatMode,
            speed = p.playbackParameters.speed,
            pitch = p.playbackParameters.pitch,
            casting = p.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE,
            error = p.playerError?.let(::describe),
            isRadio = radio,
            isLive = radio || p.isCurrentMediaItemLive,
            stationId = extras?.getString(EXTRA_STATION_ID),
            stationName = station,
        )
    }

    private fun refreshQueue(p: Player) {
        _queue.value = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
    }

    private fun describe(e: PlaybackException): String {
        val cause = generateSequence(e.cause) { it.cause }.firstOrNull { it is net.kravos.player.drive.NotAuthorizedException }
        return if (cause != null) "Google Drive needs to be reconnected (Settings)" else e.errorCodeName
    }

    // ---- commands

    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        withController { c ->
            val items = tracks.map { it.toMediaItem() }
            c.shuffleModeEnabled = shuffle
            val start = if (shuffle) Random.nextInt(items.size) else startIndex.coerceIn(0, items.size - 1)
            c.setMediaItems(items, start, 0L)
            c.prepare()
            c.play()
        }
    }

    /** Plays ready-made items (radio stations, Drive files outside the library). */
    fun playItems(items: List<MediaItem>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (items.isEmpty()) return
        withController { c ->
            c.shuffleModeEnabled = shuffle
            val start = if (shuffle) Random.nextInt(items.size) else startIndex.coerceIn(0, items.size - 1)
            c.setMediaItems(items, start, if (items[start].isRadio()) androidx.media3.common.C.TIME_UNSET else 0L)
            c.prepare()
            c.play()
        }
    }

    fun playNext(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        withController { c ->
            val items = tracks.map { it.toMediaItem() }
            if (c.mediaItemCount == 0) {
                c.setMediaItems(items)
                c.prepare()
            } else {
                c.addMediaItems(c.currentMediaItemIndex + 1, items)
            }
        }
    }

    fun enqueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        withController { c ->
            val items = tracks.map { it.toMediaItem() }
            if (c.mediaItemCount == 0) {
                c.setMediaItems(items)
                c.prepare()
            } else {
                c.addMediaItems(items)
            }
        }
    }

    fun togglePlay() = withController { c ->
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skipTo(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekToDefaultPosition(index)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun removeAt(index: Int) = withController { c -> if (index in 0 until c.mediaItemCount) c.removeMediaItem(index) }
    fun move(from: Int, to: Int) = withController { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    fun clearQueue() = withController { c -> c.stop(); c.clearMediaItems() }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Speed changes tempo without changing pitch; pitch is set separately (in semitones). */
    fun setSpeedPitch(speed: Float, semitones: Int) = withController { c ->
        val pitch = Math.pow(2.0, semitones / 12.0).toFloat()
        c.playbackParameters = PlaybackParameters(speed, pitch)
    }

    fun position(): Long = controller?.currentPosition ?: 0L
    fun bufferedPosition(): Long = controller?.bufferedPosition ?: 0L
}
