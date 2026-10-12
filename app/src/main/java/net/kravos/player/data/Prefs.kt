package net.kravos.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.kravos.player.tags.LegacyCharset
import org.json.JSONArray
import org.json.JSONObject

data class MusicRoot(val id: String, val name: String)

/** Small synchronous settings store with observable state. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("kravos_player", Context.MODE_PRIVATE)

    private val _roots = MutableStateFlow(loadRoots())
    val roots: StateFlow<List<MusicRoot>> = _roots.asStateFlow()

    private val _connected = MutableStateFlow(sp.getBoolean(K_CONNECTED, false))
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _account = MutableStateFlow(sp.getString(K_ACCOUNT, null))
    val account: StateFlow<String?> = _account.asStateFlow()

    private val _wifiOnly = MutableStateFlow(sp.getBoolean(K_WIFI_ONLY, true))
    val wifiOnlyDownloads: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _cacheMb = MutableStateFlow(sp.getInt(K_CACHE_MB, 1024))
    val streamCacheMb: StateFlow<Int> = _cacheMb.asStateFlow()

    private val _legacy = MutableStateFlow(
        runCatching { LegacyCharset.valueOf(sp.getString(K_LEGACY, null) ?: "AUTO") }.getOrDefault(LegacyCharset.AUTO),
    )
    val legacyCharset: StateFlow<LegacyCharset> = _legacy.asStateFlow()

    private val _lastScan = MutableStateFlow(sp.getLong(K_LAST_SCAN, 0L))
    val lastScanAt: StateFlow<Long> = _lastScan.asStateFlow()

    fun setConnected(value: Boolean) {
        sp.edit().putBoolean(K_CONNECTED, value).apply()
        _connected.value = value
    }

    fun setAccount(value: String?) {
        sp.edit().putString(K_ACCOUNT, value).apply()
        _account.value = value
    }

    fun setWifiOnly(value: Boolean) {
        sp.edit().putBoolean(K_WIFI_ONLY, value).apply()
        _wifiOnly.value = value
    }

    fun setStreamCacheMb(value: Int) {
        sp.edit().putInt(K_CACHE_MB, value).apply()
        _cacheMb.value = value
    }

    fun setLegacyCharset(value: LegacyCharset) {
        sp.edit().putString(K_LEGACY, value.name).apply()
        _legacy.value = value
    }

    fun setLastScan(value: Long) {
        sp.edit().putLong(K_LAST_SCAN, value).apply()
        _lastScan.value = value
    }

    fun addRoot(root: MusicRoot) {
        if (_roots.value.any { it.id == root.id }) return
        saveRoots(_roots.value + root)
    }

    fun removeRoot(id: String) = saveRoots(_roots.value.filterNot { it.id == id })

    private fun saveRoots(list: List<MusicRoot>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        sp.edit().putString(K_ROOTS, arr.toString()).apply()
        _roots.value = list
    }

    private fun loadRoots(): List<MusicRoot> = runCatching {
        val arr = JSONArray(sp.getString(K_ROOTS, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> MusicRoot(o.getString("id"), o.getString("name")) } }
    }.getOrDefault(emptyList())

    // ---- play queue persistence (written by the playback service)
    fun saveQueue(ids: List<String>, index: Int, positionMs: Long) {
        sp.edit()
            .putString(K_QUEUE, JSONArray(ids).toString())
            .putInt(K_QUEUE_INDEX, index)
            .putLong(K_QUEUE_POS, positionMs)
            .apply()
    }

    fun savePosition(index: Int, positionMs: Long) {
        sp.edit().putInt(K_QUEUE_INDEX, index).putLong(K_QUEUE_POS, positionMs).apply()
    }

    fun loadQueue(): Triple<List<String>, Int, Long> {
        val ids = runCatching {
            val arr = JSONArray(sp.getString(K_QUEUE, "[]"))
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
        return Triple(ids, sp.getInt(K_QUEUE_INDEX, 0), sp.getLong(K_QUEUE_POS, 0L))
    }

    companion object {
        private const val K_ROOTS = "roots"
        private const val K_CONNECTED = "connected"
        private const val K_ACCOUNT = "account"
        private const val K_WIFI_ONLY = "wifi_only"
        private const val K_CACHE_MB = "stream_cache_mb"
        private const val K_LEGACY = "legacy_charset"
        private const val K_LAST_SCAN = "last_scan"
        private const val K_QUEUE = "queue"
        private const val K_QUEUE_INDEX = "queue_index"
        private const val K_QUEUE_POS = "queue_pos"
    }
}
