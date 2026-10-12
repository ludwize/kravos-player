package net.kravos.player.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import net.kravos.player.data.Track
import net.kravos.player.drive.DriveApi

const val EXTRA_MIME = "kp.mime"
const val EXTRA_ART = "kp.art"
const val EXTRA_FORMAT = "kp.fmt"
const val EXTRA_DURATION = "kp.dur"

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
