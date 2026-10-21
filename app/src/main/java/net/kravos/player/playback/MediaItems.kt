package net.kravos.player.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import net.kravos.player.data.Track
import net.kravos.player.drive.DriveApi
import net.kravos.player.drive.DriveFile
import net.kravos.player.podcast.Episode
import net.kravos.player.radio.RadioStation
import net.kravos.player.tags.FileNameInfo

const val EXTRA_MIME = "kp.mime"
const val EXTRA_ART = "kp.art"
const val EXTRA_FORMAT = "kp.fmt"
const val EXTRA_DURATION = "kp.dur"
const val EXTRA_RADIO = "kp.radio"
const val EXTRA_URL = "kp.url"
const val EXTRA_CAST_URL = "kp.casturl"
const val EXTRA_CAST_MIME = "kp.castmime"
const val EXTRA_NAME = "kp.name"
const val EXTRA_STATION_ID = "kp.station"
const val RADIO_PREFIX = "radio:"
const val POD_PREFIX = "pod:"
const val EXTRA_PODCAST = "kp.podcast"
const val EXTRA_SHOW_ID = "kp.showid"
const val EXTRA_SHOW_NAME = "kp.showname"
const val EXTRA_EP_MIME = "kp.epmime"
const val EXTRA_PUBLISHED = "kp.pub"

fun MediaItem.isRadio(): Boolean =
    mediaId.startsWith(RADIO_PREFIX) || mediaMetadata.extras?.getBoolean(EXTRA_RADIO) == true

fun MediaItem.isPodcast(): Boolean =
    mediaId.startsWith(POD_PREFIX) || mediaMetadata.extras?.getBoolean(EXTRA_PODCAST) == true

/** A podcast episode, streamed straight from the publisher's server. */
fun Episode.toMediaItem(): MediaItem {
    val extras = Bundle().apply {
        putBoolean(EXTRA_PODCAST, true)
        putString(EXTRA_URL, url)
        putString(EXTRA_NAME, title)
        putString(EXTRA_ART, art)
        putString(EXTRA_SHOW_ID, feedId)
        putString(EXTRA_SHOW_NAME, showTitle)
        putString(EXTRA_EP_MIME, mime)
        putString(EXTRA_CAST_MIME, castMime())
        putLong(EXTRA_DURATION, durationMs)
        putLong(EXTRA_PUBLISHED, publishedAt)
    }
    val meta = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(showTitle)
        .setArtworkUri(art?.let(Uri::parse))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setExtras(extras)
        .build()
    return MediaItem.Builder()
        .setMediaId(POD_PREFIX + key)
        .setUri(url)
        .setMediaMetadata(meta)
        .build()
}

/** Rebuilds the episode a queue item was made from (for saving where the listener stopped). */
fun MediaItem.toEpisode(): Episode? {
    val x = mediaMetadata.extras ?: return null
    if (!mediaId.startsWith(POD_PREFIX)) return null
    val url = x.getString(EXTRA_URL) ?: return null
    return Episode(
        key = mediaId.removePrefix(POD_PREFIX),
        feedId = x.getString(EXTRA_SHOW_ID).orEmpty(),
        showTitle = x.getString(EXTRA_SHOW_NAME).orEmpty(),
        title = x.getString(EXTRA_NAME).orEmpty(),
        url = url,
        mime = x.getString(EXTRA_EP_MIME),
        durationMs = x.getLong(EXTRA_DURATION),
        publishedAt = x.getLong(EXTRA_PUBLISHED),
        art = x.getString(EXTRA_ART),
    )
}

/**
 * A live station. Title stays empty on purpose: ExoPlayer fills it from the stream's ICY "now playing"
 * metadata, and MediaItem fields would otherwise win over it.
 */
fun RadioStation.toMediaItem(castUrl: String?): MediaItem {
    val extras = Bundle().apply {
        putBoolean(EXTRA_RADIO, true)
        putString(EXTRA_URL, url)
        putString(EXTRA_CAST_URL, castUrl)
        putString(EXTRA_CAST_MIME, castMime())
        putString(EXTRA_NAME, name)
        putString(EXTRA_STATION_ID, id)
        putString(EXTRA_ART, favicon)
        putString(EXTRA_FORMAT, qualityLabel())
    }
    val meta = MediaMetadata.Builder()
        .setArtist(name)
        .setStation(name)
        .setArtworkUri(favicon?.let(Uri::parse))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .setExtras(extras)
        .build()
    return MediaItem.Builder()
        .setMediaId(RADIO_PREFIX + id)
        .setUri(url)
        .setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else null)
        .setMediaMetadata(meta)
        .build()
}

/** A Drive file that isn't (or isn't yet) in the library: tags are read by the player while it plays. */
fun DriveFile.toMediaItem(): MediaItem {
    val fn = FileNameInfo.parse(name)
    val mime = DriveApi.mimeForExtension(extension, mimeType)
    val extras = Bundle().apply {
        putString(EXTRA_MIME, mime)
        putString(EXTRA_NAME, fn.title)
        putString(EXTRA_FORMAT, extension.uppercase())
    }
    val meta = MediaMetadata.Builder()
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(extras)
        .build()
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(DriveApi.mediaUrl(id))
        .setMimeType(mime)
        .setMediaMetadata(meta)
        .build()
}

/** Best display title for queue rows etc. */
fun MediaItem.displayTitle(): String =
    mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }
        ?: mediaMetadata.extras?.getString(EXTRA_NAME)
        ?: mediaMetadata.station?.toString()
        ?: ""

fun Track.formatLabel(): String = when {
    format == "FLAC" && sampleRate > 0 -> {
        val khz = if (sampleRate % 1000 == 0) "${sampleRate / 1000}" else "%.1f".format(sampleRate / 1000.0)
        if (bitsPerSample > 0) "FLAC $bitsPerSample/$khz" else "FLAC $khz kHz"
    }
    bitrateKbps > 0 -> "$format ${bitrateKbps}k"
    else -> format
}

fun Track.toMediaItem(): MediaItem {
    val extras = Bundle().apply {
        putString(EXTRA_MIME, mimeType)
        putString(EXTRA_ART, artUri)
        putString(EXTRA_FORMAT, formatLabel())
        putLong(EXTRA_DURATION, durationMs)
        putString(EXTRA_NAME, title)
    }
    val meta = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setAlbumArtist(albumArtist)
        .setGenre(genre.ifEmpty { null })
        .setTrackNumber(trackNo.takeIf { it > 0 })
        .setDiscNumber(discNo.takeIf { it > 0 })
        .setRecordingYear(year.takeIf { it > 0 })
        .setArtworkUri(artUri?.let(Uri::parse))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(extras)
        .build()
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(DriveApi.mediaUrl(id))
        .setMimeType(mimeType)
        .setMediaMetadata(meta)
        .build()
}
