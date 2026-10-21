package net.kravos.player.podcast

import org.json.JSONObject
import java.security.MessageDigest

data class Podcast(
    val id: String,
    val title: String,
    val author: String,
    val feedUrl: String,
    val art: String?,
    val description: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("author", author).put("feed", feedUrl)
        .put("art", art ?: "").put("desc", description.take(600))

    companion object {
        fun idFor(feedUrl: String): String = sha1(feedUrl.trim().lowercase()).take(16)

        fun fromJson(o: JSONObject) = Podcast(
            id = o.getString("id"),
            title = o.optString("title"),
            author = o.optString("author"),
            feedUrl = o.getString("feed"),
            art = o.optString("art").ifBlank { null },
            description = o.optString("desc"),
        )
    }
}

data class Episode(
    val key: String,
    val feedId: String,
    val showTitle: String,
    val title: String,
    val url: String,
    val mime: String?,
    val durationMs: Long,
    val publishedAt: Long,
    val art: String?,
    val description: String = "",
) {
    /** What a Chromecast's default receiver understands for this enclosure. */
    fun castMime(): String {
        val m = (mime ?: "").lowercase().substringBefore(';').trim()
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when {
            m == "audio/mpeg" || m == "audio/mp3" -> "audio/mpeg"
            m == "audio/x-m4a" || m == "audio/m4a" || m == "audio/mp4" || m == "video/mp4" || m == "audio/x-mp4" -> "audio/mp4"
            m == "audio/aac" || m == "audio/x-aac" -> "audio/aac"
            m == "audio/ogg" || m == "application/ogg" || m == "audio/opus" -> "audio/ogg"
            m == "audio/flac" || m == "audio/x-flac" -> "audio/flac"
            m == "audio/wav" || m == "audio/x-wav" -> "audio/wav"
            ext == "m4a" || ext == "mp4" -> "audio/mp4"
            ext == "aac" -> "audio/aac"
            ext == "ogg" || ext == "opus" -> "audio/ogg"
            ext == "flac" -> "audio/flac"
            else -> "audio/mpeg"
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("feedId", feedId).put("show", showTitle).put("title", title)
        .put("url", url).put("mime", mime ?: "").put("dur", durationMs).put("pub", publishedAt)
        .put("art", art ?: "")

    companion object {
        fun fromJson(o: JSONObject) = Episode(
            key = o.getString("key"),
            feedId = o.optString("feedId"),
            showTitle = o.optString("show"),
            title = o.optString("title"),
            url = o.getString("url"),
            mime = o.optString("mime").ifBlank { null },
            durationMs = o.optLong("dur"),
            publishedAt = o.optLong("pub"),
            art = o.optString("art").ifBlank { null },
        )
    }
}

fun sha1(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
