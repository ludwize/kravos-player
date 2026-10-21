package net.kravos.player.podcast

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One episode that was started but not finished. */
data class Progress(val key: String, val posMs: Long, val durMs: Long, val updatedAt: Long, val episode: Episode) {
    val fraction: Float get() = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
}

data class ListenState(val played: Set<String> = emptySet(), val inProgress: Map<String, Progress> = emptyMap())

/**
 * Subscriptions, where each episode was left, per-show speed. Plain SharedPreferences: this is a few
 * kilobytes, and the playback service (same process) writes to it every few seconds.
 */
class PodcastStore(context: Context) {
    private val sp = context.getSharedPreferences("kravos_podcasts", Context.MODE_PRIVATE)

    private val _subs = MutableStateFlow(loadSubs())
    val subs: StateFlow<List<Podcast>> = _subs.asStateFlow()

    private val _listen = MutableStateFlow(loadListen())
    val listen: StateFlow<ListenState> = _listen.asStateFlow()

    private val _speeds = MutableStateFlow(loadSpeeds())
    val speeds: StateFlow<Map<String, Float>> = _speeds.asStateFlow()

    private val _defaultSpeed = MutableStateFlow(sp.getFloat(K_DEFAULT_SPEED, 1f))
    val defaultSpeed: StateFlow<Float> = _defaultSpeed.asStateFlow()

    // ---- subscriptions

    fun isSubscribed(id: String) = _subs.value.any { it.id == id }

    @Synchronized
    fun toggleSubscription(p: Podcast) {
        val list = _subs.value
        val next = if (list.any { it.id == p.id }) list.filterNot { it.id == p.id } else list + p
        saveSubs(next)
    }

    /** Keeps the stored title/artwork of a subscribed show fresh after a feed refresh. */
    @Synchronized
    fun refreshSubscribed(p: Podcast) {
        val list = _subs.value
        val i = list.indexOfFirst { it.id == p.id }
        if (i < 0 || list[i] == p) return
        saveSubs(list.toMutableList().also { it[i] = p })
    }

    private fun saveSubs(list: List<Podcast>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(K_SUBS, arr.toString()).apply()
        _subs.value = list
    }

    private fun loadSubs(): List<Podcast> = runCatching {
        val arr = JSONArray(sp.getString(K_SUBS, "[]"))
        (0 until arr.length()).map { Podcast.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    // ---- last place in the Podcasts tab

    fun lastShow(): Podcast? = runCatching { Podcast.fromJson(JSONObject(sp.getString(K_LAST_SHOW, "") ?: "")) }.getOrNull()

    fun setLastShow(p: Podcast?) {
        sp.edit().putString(K_LAST_SHOW, p?.toJson()?.toString() ?: "").apply()
    }

    // ---- where I stopped

    fun progressOf(key: String): Progress? = _listen.value.inProgress[key]
    fun isPlayed(key: String) = key in _listen.value.played

    /** Position to start from: where it was left, a couple of seconds back; 0 if unheard or finished. */
    fun resumePosition(key: String): Long {
        val p = progressOf(key) ?: return 0L
        return (p.posMs - 2_000L).coerceAtLeast(0L)
    }

    /** Called every few seconds while an episode plays, and when it is paused or left. */
    @Synchronized
    fun save(ep: Episode, posMs: Long, durMs: Long) {
        val dur = if (durMs > 0) durMs else ep.durationMs
        if (dur > 0 && posMs >= dur - FINISHED_MARGIN_MS) {
            markPlayed(ep.key)
            return
        }
        if (posMs < MIN_SAVE_MS) return
        val cur = _listen.value
        val map = LinkedHashMap(cur.inProgress)
        map.remove(ep.key)
        map[ep.key] = Progress(ep.key, posMs, dur, System.currentTimeMillis(), ep)
        while (map.size > MAX_IN_PROGRESS) map.remove(map.keys.first())
        // Listening again to something marked as played turns it back into "in progress".
        val played = if (ep.key in cur.played) cur.played - ep.key else cur.played
        persist(ListenState(played, map), playedChanged = played !== cur.played)
    }

    @Synchronized
    fun markPlayed(key: String) {
        val cur = _listen.value
        val played = LinkedHashSet(cur.played).also { it.add(key) }
        while (played.size > MAX_PLAYED) played.remove(played.first())
        persist(ListenState(played, cur.inProgress - key), playedChanged = true)
    }

    @Synchronized
    fun markUnplayed(key: String) {
        val cur = _listen.value
        persist(ListenState(cur.played - key, cur.inProgress - key), playedChanged = true)
    }

    private fun persist(s: ListenState, playedChanged: Boolean) {
        _listen.value = s
        val ed = sp.edit()
        val prog = JSONArray()
        s.inProgress.values.forEach { p ->
            prog.put(JSONObject().put("pos", p.posMs).put("dur", p.durMs).put("at", p.updatedAt).put("ep", p.episode.toJson()))
        }
        ed.putString(K_PROGRESS, prog.toString())
        if (playedChanged) ed.putString(K_PLAYED, JSONArray(s.played.toList()).toString())
        ed.apply()
    }

    private fun loadListen(): ListenState {
        val played = runCatching {
            val arr = JSONArray(sp.getString(K_PLAYED, "[]"))
            LinkedHashSet((0 until arr.length()).map { arr.getString(it) })
        }.getOrDefault(LinkedHashSet())
        val prog = LinkedHashMap<String, Progress>()
        runCatching {
            val arr = JSONArray(sp.getString(K_PROGRESS, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ep = Episode.fromJson(o.getJSONObject("ep"))
                prog[ep.key] = Progress(ep.key, o.optLong("pos"), o.optLong("dur"), o.optLong("at"), ep)
            }
        }
        return ListenState(played, prog)
    }

    // ---- speed (podcasts only; music and radio always play at 1×)

    fun speedFor(showId: String?): Float = (showId?.let { _speeds.value[it] } ?: _defaultSpeed.value).coerceIn(0.5f, 3f)

    fun setSpeed(showId: String, speed: Float) {
        val next = _speeds.value + (showId to speed)
        _speeds.value = next
        sp.edit().putString(K_SPEEDS, JSONObject(next.mapValues { it.value.toDouble() }).toString()).apply()
    }

    /** One speed for every show: forget the per-show overrides. */
    fun setSpeedForAll(speed: Float) {
        _speeds.value = emptyMap()
        _defaultSpeed.value = speed
        sp.edit().putString(K_SPEEDS, "{}").putFloat(K_DEFAULT_SPEED, speed).apply()
    }

    private fun loadSpeeds(): Map<String, Float> = runCatching {
        val o = JSONObject(sp.getString(K_SPEEDS, "{}"))
        o.keys().asSequence().associateWith { o.getDouble(it).toFloat() }
    }.getOrDefault(emptyMap())

    companion object {
        private const val K_SUBS = "subs"
        private const val K_PLAYED = "played"
        private const val K_PROGRESS = "progress"
        private const val K_SPEEDS = "speeds"
        private const val K_DEFAULT_SPEED = "default_speed"
        private const val K_LAST_SHOW = "last_show"
        private const val FINISHED_MARGIN_MS = 15_000L
        private const val MIN_SAVE_MS = 3_000L
        private const val MAX_IN_PROGRESS = 60
        private const val MAX_PLAYED = 5000
    }
}
