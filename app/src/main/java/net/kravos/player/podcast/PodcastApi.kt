package net.kravos.player.podcast

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class FeedResult(val podcast: Podcast, val episodes: List<Episode>, val fromCache: Boolean)

/**
 * Podcast discovery through Apple's public (keyless) directory, and plain RSS for the episodes themselves.
 * Feeds are cached on disk so a show opens instantly and also works offline.
 */
class PodcastApi(baseHttp: OkHttpClient, private val dir: File) {

    private val http = baseHttp.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
        .build()

    // ------------------------------------------------------------ directory

    suspend fun search(term: String, limit: Int = 40): List<Podcast> = withContext(Dispatchers.IO) {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("media", "podcast")
            .addQueryParameter("entity", "podcast")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("term", term.trim())
            .build()
        parseDirectory(getString(url.toString()))
    }

    /** Apple's "top podcasts" chart for a country, resolved to feed URLs. */
    suspend fun top(country: String, limit: Int = 30): List<Podcast> = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (host in listOf("rss.marketingtools.apple.com", "rss.applemarketingtools.com")) {
            try {
                val chart = getString("https://$host/api/v2/$country/podcasts/top/$limit/podcasts.json")
                val ids = JSONObject(chart).getJSONObject("feed").getJSONArray("results").let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
                }
                if (ids.isEmpty()) return@withContext emptyList()
                val byId = HashMap<String, Podcast>()
                ids.chunked(50).forEach { chunk ->
                    val body = getString("https://itunes.apple.com/lookup?entity=podcast&id=${chunk.joinToString(",")}")
                    val arr = JSONObject(body).optJSONArray("results") ?: return@forEach
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val p = directoryEntry(o) ?: continue
                        byId[o.optLong("collectionId").toString()] = p
                    }
                }
                return@withContext ids.mapNotNull { byId[it] }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Podcast chart unavailable")
    }

    private fun parseDirectory(body: String): List<Podcast> {
        val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val seen = HashSet<String>()
        return (0 until arr.length()).mapNotNull { directoryEntry(arr.getJSONObject(it)) }.filter { seen.add(it.id) }
    }

    private fun directoryEntry(o: JSONObject): Podcast? {
        val feed = o.optString("feedUrl").trim()
        if (feed.isBlank()) return null
        return Podcast(
            id = Podcast.idFor(feed),
            title = o.optString("collectionName").ifBlank { o.optString("trackName") },
            author = o.optString("artistName"),
            feedUrl = feed,
            art = o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") }.ifBlank { null },
        )
    }

    private fun getString(url: String): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body!!.string()
        }
    }

    // ------------------------------------------------------------ feeds

    private fun cacheFile(id: String) = File(dir, "$id.xml")

    /** Episodes already on disk (no network), or null if this show was never fetched. */
    suspend fun cached(p: Podcast): FeedResult? = withContext(Dispatchers.IO) {
        val f = cacheFile(p.id)
        if (!f.isFile) return@withContext null
        runCatching { f.inputStream().use { parseFeed(it, p) }.copy(fromCache = true) }.getOrNull()
    }

    /** Downloads and parses the feed; if the network fails, falls back to the cached copy. */
    suspend fun load(p: Podcast): FeedResult = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = cacheFile(p.id)
        val tmp = File(dir, "${p.id}.tmp")
        try {
            val req = Request.Builder().url(p.feedUrl)
                .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val src = r.body!!.source()
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var total = 0L
                    val input = src.inputStream()
                    while (total < MAX_FEED_BYTES) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                }
            }
            val result = tmp.inputStream().use { parseFeed(it, p) }
            if (result.episodes.isEmpty()) throw IOException("No episodes in this feed")
            target.delete()
            tmp.renameTo(target)
            result
        } catch (e: Exception) {
            tmp.delete()
            val old = runCatching { target.inputStream().use { parseFeed(it, p) } }.getOrNull()
            if (old != null && old.episodes.isNotEmpty()) old.copy(fromCache = true) else throw e
        }
    }

    /** Subscribes by RSS address: fetch it to learn the show's name and artwork. */
    suspend fun loadByUrl(url: String): FeedResult {
        val clean = url.trim()
        val seed = Podcast(Podcast.idFor(clean), clean.substringAfter("://").substringBefore('/'), "", clean, null)
        return load(seed)
    }

    // ------------------------------------------------------------ RSS parsing

    internal fun parseFeed(input: InputStream, seed: Podcast): FeedResult {
        val xpp = Xml.newPullParser()
        runCatching { xpp.setFeature(Xml.FEATURE_RELAXED, true) }
        xpp.setInput(input, null)
        var title = ""
        var author = ""
        var art: String? = null
        var summary = ""
        var inImage = false
        var item: ItemBuilder? = null
        val episodes = ArrayList<Episode>()
        val dates = DateParser()

        fun finish(it: ItemBuilder) {
            val url = it.url ?: return
            val type = it.type?.lowercase()
            if (type != null && type.isNotBlank() &&
                !(type.startsWith("audio/") || type.startsWith("video/") || type == "application/octet-stream" || type == "application/ogg")
            ) return
            val id = seed.id
            episodes += Episode(
                key = sha1(id + "|" + it.guid.ifBlank { url }).take(16),
                feedId = id,
                showTitle = title.ifBlank { seed.title },
                title = it.title.ifBlank { "Episode" },
                url = url,
                mime = it.type,
                durationMs = parseDuration(it.duration),
                publishedAt = dates.parse(it.pubDate),
                art = it.art ?: art ?: seed.art,
                // Rich show notes (content:encoded, with links) win over the plain description.
                description = it.html.ifBlank { it.description }.take(MAX_NOTES),
            )
        }

        try {
            var event = xpp.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val name = xpp.name
                    val it = item
                    when {
                        name == "item" -> item = ItemBuilder()
                        name == "image" && it == null -> inImage = true
                        name == "enclosure" && it != null -> {
                            it.url = xpp.attr("url")?.trim()?.takeIf { u -> u.startsWith("http", true) }
                            it.type = xpp.attr("type")
                        }
                        name == "itunes:image" -> {
                            val href = xpp.attr("href")?.trim()?.takeIf { u -> u.isNotBlank() }
                            if (it != null) it.art = href else if (art == null) art = href
                        }
                        name == "url" && inImage -> if (art == null) art = xpp.readText().trim().ifBlank { null }
                        name == "title" && !inImage -> {
                            val t = xpp.readText().trim()
                            if (it != null) { if (it.title.isBlank()) it.title = t } else if (title.isBlank()) title = t
                        }
                        name == "itunes:title" && it != null -> {
                            val t = xpp.readText().trim()
                            if (it.title.isBlank()) it.title = t
                        }
                        name == "itunes:author" && it == null -> if (author.isBlank()) author = xpp.readText().trim()
                        name == "author" && it == null -> if (author.isBlank()) author = xpp.readText().trim()
                        name == "description" || name == "itunes:summary" -> {
                            val t = xpp.readText().trim()
                            if (it != null) { if (it.description.isBlank()) it.description = t } else if (summary.isBlank()) summary = t
                        }
                        name == "content:encoded" && it != null -> if (it.html.isBlank()) it.html = xpp.readText().trim()
                        name == "pubDate" && it != null -> it.pubDate = xpp.readText().trim()
                        name == "itunes:duration" && it != null -> it.duration = xpp.readText().trim()
                        name == "guid" && it != null -> it.guid = xpp.readText().trim()
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    when (xpp.name) {
                        "item" -> {
                            item?.let(::finish)
                            item = null
                            if (episodes.size >= MAX_EPISODES) break
                        }
                        "image" -> inImage = false
                    }
                }
                event = xpp.next()
            }
        } catch (e: Exception) {
            // A truncated or slightly malformed feed still gives us the episodes read so far.
            if (episodes.isEmpty()) throw e
        }

        val sorted = episodes.sortedByDescending { it.publishedAt }
        val show = seed.copy(
            title = title.ifBlank { seed.title },
            author = author.ifBlank { seed.author },
            art = art ?: seed.art,
            description = summary.ifBlank { seed.description },
        )
        return FeedResult(show, sorted, fromCache = false)
    }

    private class ItemBuilder {
        var title = ""
        var url: String? = null
        var type: String? = null
        var guid = ""
        var pubDate = ""
        var duration = ""
        var art: String? = null
        var description = ""
        var html = ""
    }

    private fun XmlPullParser.attr(name: String): String? {
        for (i in 0 until attributeCount) if (getAttributeName(i) == name) return getAttributeValue(i)
        return null
    }

    /** Text of the current element, ignoring any child elements. */
    private fun XmlPullParser.readText(): String {
        val sb = StringBuilder()
        val d = depth
        while (true) {
            val e = next()
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.END_TAG && depth == d) break
            if (e == XmlPullParser.TEXT) sb.append(text)
        }
        return sb.toString()
    }

    private class DateParser {
        private val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss zzz",
            "dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm Z",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
        )
        private var lastGood = 0

        fun parse(s: String): Long {
            val text = s.trim()
            if (text.isEmpty()) return 0L
            for (k in patterns.indices) {
                val i = (lastGood + k) % patterns.size
                val fmt = SimpleDateFormat(patterns[i], Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = true }
                val d = runCatching { fmt.parse(text) }.getOrNull() ?: continue
                lastGood = i
                return d.time
            }
            return 0L
        }
    }

    companion object {
        const val USER_AGENT = "KravosPlayer/1.2 (Android)"
        private const val MAX_FEED_BYTES = 12L * 1024 * 1024
        private const val MAX_EPISODES = 500
        private const val MAX_NOTES = 12_000

        /** itunes:duration is "3600", "59:12" or "1:02:03". */
        fun parseDuration(s: String): Long {
            val t = s.trim()
            if (t.isEmpty()) return 0L
            val parts = t.split(':')
            return try {
                when (parts.size) {
                    1 -> (parts[0].toDouble() * 1000).toLong()
                    2 -> (parts[0].toLong() * 60 + parts[1].toDouble().toLong()) * 1000
                    3 -> (parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble().toLong()) * 1000
                    else -> 0L
                }
            } catch (e: NumberFormatException) {
                0L
            }
        }
    }
}
