package net.kravos.player.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class RadioStation(
    val id: String,
    val name: String,
    val url: String,
    val favicon: String?,
    val homepage: String?,
    val codec: String,
    val bitrate: Int,
    val countryCode: String,
    val tags: String,
    val hls: Boolean,
    val votes: Int = 0,
    val clicks: Int = 0,
) {
    private val codecUp: String get() = codec.uppercase()

    val isHttps: Boolean get() = url.startsWith("https://", ignoreCase = true)

    /** Codecs a Chromecast's default receiver can decode on its own. */
    val castCodecOk: Boolean
        get() = hls || codecUp in setOf("MP3", "AAC", "AAC+", "HE-AAC", "FLAC", "OGG", "OPUS", "VORBIS", "", "UNKNOWN")

    /** HTTPS + supported codec: the Chromecast fetches it directly and keeps playing without the phone. */
    val castReady: Boolean get() = isHttps && castCodecOk

    val goodQuality: Boolean
        get() = codecUp == "FLAC" || bitrate >= 128 || (codecUp.startsWith("AAC") && bitrate >= 64) || (bitrate == 0 && hls)

    val highQuality: Boolean
        get() = codecUp == "FLAC" || bitrate >= 256 || (codecUp.startsWith("AAC") && bitrate >= 128)

    fun qualityLabel(): String = listOfNotNull(
        codec.takeIf { it.isNotBlank() && !it.equals("UNKNOWN", true) }?.uppercase(),
        bitrate.takeIf { it > 0 }?.let { "${it}k" },
        if (hls) "HLS" else null,
    ).joinToString(" ")

    fun castMime(): String = when {
        hls -> "application/x-mpegURL"
        codecUp.startsWith("AAC") || codecUp == "HE-AAC" -> "audio/aac"
        codecUp == "FLAC" -> "audio/flac"
        codecUp in setOf("OGG", "OPUS", "VORBIS") -> "audio/ogg"
        else -> "audio/mpeg"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", url).put("favicon", favicon ?: "")
        .put("homepage", homepage ?: "").put("codec", codec).put("bitrate", bitrate)
        .put("cc", countryCode).put("tags", tags).put("hls", hls)

    companion object {
        fun fromJson(o: JSONObject) = RadioStation(
            id = o.getString("id"),
            name = o.optString("name"),
            url = o.getString("url"),
            favicon = o.optString("favicon").ifBlank { null },
            homepage = o.optString("homepage").ifBlank { null },
            codec = o.optString("codec"),
            bitrate = o.optInt("bitrate"),
            countryCode = o.optString("cc"),
            tags = o.optString("tags"),
            hls = o.optBoolean("hls"),
        )
    }
}

/** Client for the free, community-run radio-browser.info directory. */
class RadioApi(baseHttp: OkHttpClient) {

    private val http = baseHttp.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
        .build()

    private val probeHttp = http.newBuilder().readTimeout(5, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).build()

    @Volatile private var servers: List<String>? = null

    private fun serverList(): List<String> {
        servers?.let { return it }
        val found = runCatching {
            InetAddress.getAllByName("all.api.radio-browser.info")
                .mapNotNull { runCatching { it.canonicalHostName }.getOrNull() }
                .filter { it.endsWith("radio-browser.info") }
                .distinct()
                .shuffled()
        }.getOrDefault(emptyList())
        return (found + FALLBACK_SERVERS).distinct().map { "https://$it" }.also { servers = it }
    }

    /**
     * Station search. [order] is a radio-browser field (clickcount, votes, bitrate, name…).
     * Filtering for HTTPS/quality happens in the app so it doesn't depend on server-side parameters.
     */
    suspend fun search(name: String?, countryCode: String?, order: String = "clickcount", limit: Int = 300): List<RadioStation> =
        withContext(Dispatchers.IO) {
            var last: Exception? = null
            for (server in serverList()) {
                try {
                    val url = "$server/json/stations/search".toHttpUrl().newBuilder().apply {
                        if (!name.isNullOrBlank()) addQueryParameter("name", name.trim())
                        if (!countryCode.isNullOrBlank()) addQueryParameter("countrycode", countryCode)
                        addQueryParameter("order", order)
                        addQueryParameter("reverse", "true")
                        addQueryParameter("hidebroken", "true")
                        addQueryParameter("limit", limit.toString())
                    }.build()
                    http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("radio-browser ${r.code}")
                        return@withContext parse(r.body!!.string())
                    }
                } catch (e: Exception) {
                    last = e
                }
            }
            throw last ?: IOException("No radio-browser server reachable")
        }

    private fun parse(body: String): List<RadioStation> {
        val arr = JSONArray(body)
        val out = ArrayList<RadioStation>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }
            if (url.isBlank() || !url.startsWith("http", ignoreCase = true)) continue
            val name = o.optString("name").trim()
            // The directory has many duplicates of big stations; keep the first (most popular) per name+url.
            if (!seen.add(name.lowercase() + "|" + url.lowercase())) continue
            out += RadioStation(
                id = o.optString("stationuuid"),
                name = name,
                url = url,
                favicon = o.optString("favicon").ifBlank { null },
                homepage = o.optString("homepage").ifBlank { null },
                codec = o.optString("codec"),
                bitrate = o.optInt("bitrate"),
                countryCode = o.optString("countrycode"),
                tags = o.optString("tags"),
                hls = o.optInt("hls") == 1,
                votes = o.optInt("votes"),
                clicks = o.optInt("clickcount"),
            )
        }
        return out
    }

    /** Tells the directory a station was played (keeps its popularity ranking useful). */
    suspend fun click(id: String) = withContext(Dispatchers.IO) {
        if (id.startsWith("custom-")) return@withContext
        runCatching {
            val server = serverList().first()
            http.newCall(Request.Builder().url("$server/json/url/$id").build()).execute().close()
        }
        Unit
    }

    /**
     * A URL the Chromecast can open by itself: the station URL if it's already HTTPS, otherwise the same
     * address over HTTPS if the server answers there. Null means only the phone (or its LAN proxy) can play it.
     */
    suspend fun castableUrl(st: RadioStation): String? = withContext(Dispatchers.IO) {
        if (st.isHttps) return@withContext st.url
        val https = "https://" + st.url.substringAfter("://")
        runCatching {
            probeHttp.newCall(Request.Builder().url(https).build()).execute().use { r ->
                val type = r.header("Content-Type").orEmpty().lowercase()
                if (r.isSuccessful && (type.startsWith("audio/") || type.contains("mpegurl") || type.contains("ogg") || type.contains("aac"))) https else null
            }
        }.getOrNull()
    }

    /** Turns a .pls/.m3u playlist link into the stream URL inside it; other links are returned as-is. */
    suspend fun resolveStreamUrl(url: String): String = withContext(Dispatchers.IO) {
        val lower = url.lowercase().substringBefore('?')
        val looksPlaylist = lower.endsWith(".pls") || (lower.endsWith(".m3u") && !lower.endsWith(".m3u8"))
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val type = r.header("Content-Type").orEmpty().lowercase()
                val isPlaylist = looksPlaylist || type.contains("scpls") || type.contains("x-mpegurl") && !lower.endsWith(".m3u8")
                if (!isPlaylist) return@use url
                val text = r.body?.source()?.let { src ->
                    src.request(64 * 1024)
                    src.buffer.readUtf8(minOf(src.buffer.size, 64L * 1024))
                } ?: return@use url
                text.lineSequence()
                    .map { it.trim().substringAfter('=', it.trim()) }
                    .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
                    ?: url
            }
        }.getOrDefault(url)
    }

    companion object {
        const val USER_AGENT = "KravosPlayer/1.1 (Android)"
        private val FALLBACK_SERVERS = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info", "nl1.api.radio-browser.info")
    }
}
