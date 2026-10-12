package net.kravos.player.tags

import java.nio.charset.Charset
import kotlin.math.max
import kotlin.math.min

/**
 * Random-access byte source (a Drive file read with HTTP Range requests, or a local array in tests).
 */
interface RangeSource {
    val size: Long

    /** Reads up to [length] bytes starting at [offset]. Returns fewer bytes only at end of file. */
    suspend fun read(offset: Long, length: Int): ByteArray
}

class ByteArraySource(private val bytes: ByteArray) : RangeSource {
    override val size: Long get() = bytes.size.toLong()
    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (offset >= bytes.size || length <= 0) return ByteArray(0)
        val end = min(bytes.size.toLong(), offset + length).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }
}

/**
 * Wraps a [RangeSource] and serves small reads from a read-ahead window so that walking through
 * tag frames does not turn into one network request per frame.
 */
class BufferedRangeSource(private val inner: RangeSource, private val window: Int = 64 * 1024) : RangeSource {
    override val size: Long get() = inner.size
    private var bufStart = 0L
    private var buf = ByteArray(0)
    var requests = 0
        private set

    override suspend fun read(offset: Long, length: Int): ByteArray {
        if (length <= 0 || offset >= size) return ByteArray(0)
        val wanted = min(length.toLong(), size - offset).toInt()
        if (offset >= bufStart && offset + wanted <= bufStart + buf.size) {
            val from = (offset - bufStart).toInt()
            return buf.copyOfRange(from, from + wanted)
        }
        if (wanted > window) {
            requests++
            return inner.read(offset, wanted)
        }
        val fetch = min(max(window, wanted).toLong(), size - offset).toInt()
        requests++
        buf = inner.read(offset, fetch)
        bufStart = offset
        return buf.copyOfRange(0, min(wanted, buf.size))
    }
}

data class PictureRef(
    val offset: Long,
    val length: Int,
    val mime: String,
    val type: Int,
    /** ID3v2.4 per-frame unsynchronisation was applied to the picture bytes. */
    val unsync: Boolean = false,
    /** For [unsync] frames: decoded header bytes to drop before the image data. */
    val headerSkip: Int = 0,
)

data class AudioTags(
    var title: String? = null,
    var artist: String? = null,
    var album: String? = null,
    var albumArtist: String? = null,
    var genre: String? = null,
    var year: Int? = null,
    var track: Int? = null,
    var disc: Int? = null,
    var durationMs: Long = 0,
    var sampleRate: Int = 0,
    var bitsPerSample: Int = 0,
    var channels: Int = 0,
    var bitrateKbps: Int = 0,
    var format: String = "",
    var picture: PictureRef? = null,
)

enum class LegacyCharset { AUTO, WINDOWS_1250, LATIN_1 }

/**
 * Minimal, dependency-free tag reader for MP3 (ID3v2.2/2.3/2.4 + ID3v1, Xing/VBRI duration) and FLAC
 * (STREAMINFO, VORBIS_COMMENT, PICTURE). Reads only the bytes it needs.
 */
class TagReader(private val legacy: LegacyCharset = LegacyCharset.AUTO) {

    suspend fun read(src: RangeSource, extension: String): AudioTags {
        val tags = AudioTags()
        when (extension.lowercase()) {
            "flac" -> readFlac(src, tags)
            "mp3" -> readMp3(src, tags)
            else -> {
                // Other formats: only ID3v2 if someone put it there; otherwise rely on file names.
                tags.format = extension.uppercase()
                runCatching { parseId3v2(src, 0, tags) }
            }
        }
        tags.title = tags.title?.trim()?.ifEmpty { null }
        tags.artist = tags.artist?.trim()?.ifEmpty { null }
        tags.album = tags.album?.trim()?.ifEmpty { null }
        tags.albumArtist = tags.albumArtist?.trim()?.ifEmpty { null }
        tags.genre = tags.genre?.trim()?.ifEmpty { null }
        return tags
    }

    suspend fun readPicture(src: RangeSource, ref: PictureRef): ByteArray {
        val raw = src.read(ref.offset, ref.length)
        if (!ref.unsync) return raw
        val decoded = deUnsync(raw)
        return if (ref.headerSkip in 1 until decoded.size) decoded.copyOfRange(ref.headerSkip, decoded.size) else decoded
    }

    // ---------------------------------------------------------------- MP3

    private suspend fun readMp3(src: RangeSource, tags: AudioTags) {
        tags.format = "MP3"
        var audioStart = 0L
        // Some files carry more than one ID3v2 tag back to back; take the first, skip the rest.
        var guard = 0
        while (guard++ < 3) {
            val end = parseId3v2(src, audioStart, tags.takeIf { guard == 1 } ?: AudioTags())
            if (end == audioStart) break
            audioStart = end
        }
        var hasV1 = false
        if (src.size >= 128) {
            val v1 = src.read(src.size - 128, 128)
            if (v1.size == 128 && v1[0] == 'T'.code.toByte() && v1[1] == 'A'.code.toByte() && v1[2] == 'G'.code.toByte()) {
                hasV1 = true
                if (tags.title == null && tags.artist == null) parseId3v1(v1, tags)
            }
        }
        val audioEnd = src.size - if (hasV1) 128 else 0
        readMpegInfo(src, audioStart, audioEnd, tags)
    }

    private fun parseId3v1(b: ByteArray, tags: AudioTags) {
        fun field(from: Int, len: Int): String? {
            var end = from
            while (end < from + len && b[end].toInt() != 0) end++
            return legacyDecode(b, from, end).trim().ifEmpty { null }
        }
        tags.title = field(3, 30)
        tags.artist = field(33, 30)
        tags.album = field(63, 30)
        tags.year = field(93, 4)?.let(::parseYear)
        if (b[125].toInt() == 0 && b[126].toInt() != 0) tags.track = b[126].toInt() and 0xFF
        val g = b[127].toInt() and 0xFF
        if (g < ID3_GENRES.size) tags.genre = ID3_GENRES[g]
    }

    /** Returns the offset just after the tag, or [at] if there is no ID3v2 tag there. */
    internal suspend fun parseId3v2(src: RangeSource, at: Long, tags: AudioTags): Long {
        val h = src.read(at, 10)
        if (h.size < 10 || h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return at
        val major = h[3].toInt() and 0xFF
        val flags = h[5].toInt() and 0xFF
        val size = syncsafe(h, 6)
        val footer = if (major == 4 && (flags and 0x10) != 0) 10 else 0
        val end = at + 10 + size + footer
        if (major !in 2..4 || size <= 0) return end
        var pos = at + 10
        val bodyEnd = at + 10 + size
        if ((flags and 0x80) != 0 && major < 4) {
            // Whole-tag unsynchronisation (ID3v2.2/2.3): read everything and undo it in memory.
            val body = deUnsync(src.read(pos, size))
            val mem = ByteArraySource(body)
            var start = 0L
            if (major == 3 && (flags and 0x40) != 0 && body.size >= 4) start = 4L + be32(body, 0)
            parseFrames(mem, start, body.size.toLong(), major, tags, pictureAllowed = false)
            return end
        }
        if ((flags and 0x40) != 0 && major >= 3) {
            val eh = src.read(pos, 4)
            if (eh.size < 4) return end
            pos += if (major == 4) syncsafe(eh, 0).toLong() else 4L + be32(eh, 0)
        }
        parseFrames(src, pos, bodyEnd, major, tags, pictureAllowed = true)
        return end
    }

    private suspend fun parseFrames(
        src: RangeSource,
        start: Long,
        end: Long,
        major: Int,
        tags: AudioTags,
        pictureAllowed: Boolean,
    ) {
        val hdrLen = if (major == 2) 6 else 10
        var pos = start
        var count = 0
        var pictureFound: PictureRef? = null
        while (pos + hdrLen <= end && count++ < 512) {
            val fh = src.read(pos, hdrLen)
            if (fh.size < hdrLen || fh[0].toInt() == 0) break
            val idLen = if (major == 2) 3 else 4
            val id = String(fh, 0, idLen, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val fsize: Int
            var fflags = 0
            if (major == 2) {
                fsize = be24(fh, 3)
            } else {
                fsize = if (major == 4 && (fh[4].toInt() and 0x80) == 0 && (fh[5].toInt() and 0x80) == 0 &&
                    (fh[6].toInt() and 0x80) == 0 && (fh[7].toInt() and 0x80) == 0
                ) syncsafe(fh, 4) else be32(fh, 4)
                fflags = ((fh[8].toInt() and 0xFF) shl 8) or (fh[9].toInt() and 0xFF)
            }
            var dataPos = pos + hdrLen
            if (fsize <= 0 || dataPos + fsize > end) break
            val next = dataPos + fsize
            var dataLen = fsize
            var frameUnsync = false
            var skip = false
            if (major == 3) {
                if ((fflags and 0x00C0) != 0) skip = true // compressed or encrypted
                if ((fflags and 0x0020) != 0) { dataPos += 1; dataLen -= 1 }
            } else if (major == 4) {
                if ((fflags and 0x000C) != 0) skip = true
                if ((fflags and 0x0040) != 0) { dataPos += 1; dataLen -= 1 }
                if ((fflags and 0x0001) != 0) { dataPos += 4; dataLen -= 4 }
                frameUnsync = (fflags and 0x0002) != 0
            }
            if (!skip && dataLen > 0) {
                val key = normalizeFrameId(id)
                when {
                    key == "APIC" -> if (pictureAllowed) {
                        val ref = parseApic(src, dataPos, dataLen, major, frameUnsync)
                        if (ref != null && (pictureFound == null || (pictureFound.type != 3 && ref.type == 3))) pictureFound = ref
                    } else if (pictureFound == null) {
                        pictureFound = PictureRef(-1, 0, "", 0)
                    }
                    key in WANTED_TEXT -> {
                        var data = src.read(dataPos, min(dataLen, 8192))
                        if (frameUnsync) data = deUnsync(data)
                        if (data.isNotEmpty()) applyText(key, decodeTextFrame(data), tags)
                    }
                }
            }
            pos = next
        }
        if (pictureFound != null && pictureFound.offset >= 0) tags.picture = pictureFound
    }

    private fun normalizeFrameId(id: String): String = when (id) {
        "TT2" -> "TIT2"; "TP1" -> "TPE1"; "TAL" -> "TALB"; "TP2" -> "TPE2"; "TCO" -> "TCON"
        "TRK" -> "TRCK"; "TPA" -> "TPOS"; "TYE" -> "TYER"; "TLE" -> "TLEN"; "PIC" -> "APIC"
        "TOR" -> "TORY"; "TDA" -> "TDAT"
        else -> id
    }

    private fun applyText(key: String, value: String?, tags: AudioTags) {
        if (value.isNullOrBlank()) return
        when (key) {
            "TIT2" -> if (tags.title == null) tags.title = value
            "TPE1" -> if (tags.artist == null) tags.artist = value
            "TALB" -> if (tags.album == null) tags.album = value
            "TPE2" -> if (tags.albumArtist == null) tags.albumArtist = value
            "TCON" -> if (tags.genre == null) tags.genre = parseGenre(value)
            "TRCK" -> if (tags.track == null) tags.track = parseLeadingInt(value)
            "TPOS" -> if (tags.disc == null) tags.disc = parseLeadingInt(value)
            "TYER", "TDRC" -> if (tags.year == null) tags.year = parseYear(value)
            "TORY", "TDOR" -> if (tags.year == null) tags.year = parseYear(value)
            "TLEN" -> if (tags.durationMs == 0L) tags.durationMs = value.trim().toLongOrNull() ?: 0L
        }
    }

    private suspend fun parseApic(src: RangeSource, dataPos: Long, dataLen: Int, major: Int, unsync: Boolean): PictureRef? {
        // Header part (encoding, mime, type, description) is small; read enough to find where the image starts.
        var head = src.read(dataPos, min(dataLen, 1024))
        if (unsync) head = deUnsync(head)
        if (head.size < 4) return null
        val enc = head[0].toInt() and 0xFF
        var p = 1
        val mime: String
        if (major == 2) {
            val fmt = String(head, 1, 3, Charsets.ISO_8859_1).uppercase()
            mime = if (fmt == "PNG") "image/png" else "image/jpeg"
            p = 4
        } else {
            val mEnd = indexOfZero(head, p)
            if (mEnd < 0) return null
            mime = String(head, p, mEnd - p, Charsets.ISO_8859_1).lowercase().let {
                when {
                    it.isEmpty() || it == "jpg" -> "image/jpeg"
                    it == "png" -> "image/png"
                    !it.contains('/') -> "image/$it"
                    else -> it
                }
            }
            p = mEnd + 1
        }
        if (p >= head.size) return null
        val picType = head[p].toInt() and 0xFF
        p++
        // description, terminated by 0x00 (or 0x00 0x00 for UTF-16)
        val descEnd = if (enc == 1 || enc == 2) indexOfDoubleZero(head, p) else indexOfZero(head, p)
        if (descEnd < 0) return null
        p = descEnd + if (enc == 1 || enc == 2) 2 else 1
        if (unsync) {
            // Offsets inside an unsynchronised frame don't map 1:1: keep the whole frame, decode, then skip the header.
            return PictureRef(dataPos, dataLen, mime, picType, unsync = true, headerSkip = p)
        }
        val len = dataLen - p
        if (len <= 0) return null
        return PictureRef(dataPos + p, len, mime, picType)
    }

    private fun decodeTextFrame(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val enc = data[0].toInt() and 0xFF
        val s = decodeText(data, 1, data.size, enc)
        return s.split('\u0000').map { it.trim() }.firstOrNull { it.isNotEmpty() }
    }

    private fun decodeText(b: ByteArray, from: Int, to: Int, enc: Int): String {
        if (from >= to) return ""
        return when (enc) {
            0 -> legacyDecode(b, from, to)
            1 -> {
                val len = to - from
                if (len >= 2 && (b[from].toInt() and 0xFF) == 0xFF && (b[from + 1].toInt() and 0xFF) == 0xFE)
                    String(b, from + 2, len - 2, Charsets.UTF_16LE)
                else if (len >= 2 && (b[from].toInt() and 0xFF) == 0xFE && (b[from + 1].toInt() and 0xFF) == 0xFF)
                    String(b, from + 2, len - 2, Charsets.UTF_16BE)
                else String(b, from, len, Charsets.UTF_16LE)
            }
            2 -> String(b, from, to - from, Charsets.UTF_16BE)
            else -> String(b, from, to - from, Charsets.UTF_8)
        }
    }

    /** ISO-8859-1 by spec, but old rips from the region are very often really Windows-1250. */
    private fun legacyDecode(b: ByteArray, from: Int, to: Int): String {
        val cs: Charset = when (legacy) {
            LegacyCharset.LATIN_1 -> Charsets.ISO_8859_1
            LegacyCharset.WINDOWS_1250 -> WIN1250
            LegacyCharset.AUTO -> {
                var cp1250 = false
                for (i in from until to) {
                    val v = b[i].toInt() and 0xFF
                    if (v in 0x80..0x9F || v in CP1250_HINTS) { cp1250 = true; break }
                }
                if (cp1250) WIN1250 else Charsets.ISO_8859_1
            }
        }
        return String(b, from, to - from, cs)
    }

    // MPEG audio frame header + Xing/Info/VBRI → duration and bitrate.
    private suspend fun readMpegInfo(src: RangeSource, audioStart: Long, audioEnd: Long, tags: AudioTags) {
        val scan = src.read(audioStart, 16 * 1024)
        var i = 0
        while (i + 4 <= scan.size) {
            val h = parseMpegHeader(scan, i)
            if (h != null) {
                // Confirm with the following frame when it's inside the buffer, to avoid false syncs.
                val nxt = i + h.frameLen
                val ok = nxt + 4 > scan.size || parseMpegHeader(scan, nxt) != null
                if (ok) {
                    applyMpeg(src, audioStart + i, audioEnd, h, tags)
                    return
                }
            }
            i++
        }
    }

    private class MpegHeader(
        val version: Int, // 1, 2, 25
        val layer: Int,
        val bitrateKbps: Int,
        val sampleRate: Int,
        val mono: Boolean,
        val frameLen: Int,
        val samplesPerFrame: Int,
    )

    private fun parseMpegHeader(b: ByteArray, i: Int): MpegHeader? {
        if (i + 4 > b.size) return null
        val b0 = b[i].toInt() and 0xFF
        val b1 = b[i + 1].toInt() and 0xFF
        val b2 = b[i + 2].toInt() and 0xFF
        val b3 = b[i + 3].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
        val ver = when ((b1 shr 3) and 3) { 0 -> 25; 2 -> 2; 3 -> 1; else -> return null }
        val layer = when ((b1 shr 1) and 3) { 1 -> 3; 2 -> 2; 3 -> 1; else -> return null }
        val brIdx = (b2 shr 4) and 0xF
        val srIdx = (b2 shr 2) and 3
        if (brIdx == 0 || brIdx == 15 || srIdx == 3) return null
        val pad = (b2 shr 1) and 1
        val mono = ((b3 shr 6) and 3) == 3
        val br = if (ver == 1) when (layer) {
            1 -> BR_V1_L1[brIdx]; 2 -> BR_V1_L2[brIdx]; else -> BR_V1_L3[brIdx]
        } else if (layer == 1) BR_V2_L1[brIdx] else BR_V2_L23[brIdx]
        val sr = when (ver) { 1 -> SR_V1[srIdx]; 2 -> SR_V1[srIdx] / 2; else -> SR_V1[srIdx] / 4 }
        val spf = when (layer) { 1 -> 384; 2 -> 1152; else -> if (ver == 1) 1152 else 576 }
        val frameLen = if (layer == 1) (12 * br * 1000 / sr + pad) * 4 else (spf / 8 * br * 1000 / sr + pad)
        if (frameLen < 21) return null
        return MpegHeader(ver, layer, br, sr, mono, frameLen, spf)
    }

    private suspend fun applyMpeg(src: RangeSource, frameStart: Long, audioEnd: Long, h: MpegHeader, tags: AudioTags) {
        tags.sampleRate = h.sampleRate
        tags.channels = if (h.mono) 1 else 2
        val sideInfo = if (h.version == 1) (if (h.mono) 17 else 32) else (if (h.mono) 9 else 17)
        val fr = src.read(frameStart, 4 + 32 + 40)
        val audioBytes = (audioEnd - frameStart).coerceAtLeast(0)
        var frames = -1L
        var bytes = -1L
        val xo = 4 + sideInfo
        if (fr.size >= xo + 16) {
            val tag = String(fr, xo, 4, Charsets.ISO_8859_1)
            if (tag == "Xing" || tag == "Info") {
                val flags = be32(fr, xo + 4)
                var p = xo + 8
                if ((flags and 1) != 0 && fr.size >= p + 4) { frames = be32(fr, p).toLong() and 0xFFFFFFFFL; p += 4 }
                if ((flags and 2) != 0 && fr.size >= p + 4) { bytes = be32(fr, p).toLong() and 0xFFFFFFFFL }
            }
        }
        if (frames < 0 && fr.size >= 36 + 18) {
            if (String(fr, 36, 4, Charsets.ISO_8859_1) == "VBRI") {
                bytes = be32(fr, 36 + 10).toLong() and 0xFFFFFFFFL
                frames = be32(fr, 36 + 14).toLong() and 0xFFFFFFFFL
            }
        }
        if (frames > 0) {
            val ms = frames * h.samplesPerFrame * 1000L / h.sampleRate
            if (tags.durationMs <= 0) tags.durationMs = ms
            val b = if (bytes > 0) bytes else audioBytes
            if (ms > 0) tags.bitrateKbps = (b * 8 / ms).toInt()
        } else {
            tags.bitrateKbps = h.bitrateKbps
            if (tags.durationMs <= 0 && h.bitrateKbps > 0) tags.durationMs = audioBytes * 8 / h.bitrateKbps
        }
    }

    // ---------------------------------------------------------------- FLAC

    private suspend fun readFlac(src: RangeSource, tags: AudioTags) {
        tags.format = "FLAC"
        var pos = parseId3v2(src, 0, AudioTags())
        val magic = src.read(pos, 4)
        if (magic.size < 4 || String(magic, Charsets.ISO_8859_1) != "fLaC") return
        pos += 4
        var blocks = 0
        var picture: PictureRef? = null
        while (blocks++ < 128) {
            val bh = src.read(pos, 4)
            if (bh.size < 4) break
            val last = (bh[0].toInt() and 0x80) != 0
            val type = bh[0].toInt() and 0x7F
            val len = be24(bh, 1)
            val dataPos = pos + 4
            when (type) {
                0 -> {
                    val si = src.read(dataPos, 34)
                    if (si.size >= 18) {
                        val b10 = si[10].toInt() and 0xFF
                        val b11 = si[11].toInt() and 0xFF
                        val b12 = si[12].toInt() and 0xFF
                        val b13 = si[13].toInt() and 0xFF
                        val sr = (b10 shl 12) or (b11 shl 4) or (b12 shr 4)
                        val ch = ((b12 shr 1) and 7) + 1
                        val bps = (((b12 and 1) shl 4) or (b13 shr 4)) + 1
                        val total = ((b13 and 0xF).toLong() shl 32) or (be32(si, 14).toLong() and 0xFFFFFFFFL)
                        tags.sampleRate = sr
                        tags.channels = ch
                        tags.bitsPerSample = bps
                        if (sr > 0 && total > 0) tags.durationMs = total * 1000L / sr
                    }
                }
                4 -> parseVorbisComment(src.read(dataPos, min(len, 1 shl 20)), tags)
                6 -> {
                    val ref = parseFlacPicture(src, dataPos, len)
                    if (ref != null && (picture == null || (picture.type != 3 && ref.type == 3))) picture = ref
                }
            }
            pos = dataPos + len
            if (last) break
        }
        tags.picture = picture
        if (tags.durationMs > 0) tags.bitrateKbps = (src.size * 8 / tags.durationMs).toInt()
    }

    private suspend fun parseFlacPicture(src: RangeSource, dataPos: Long, len: Int): PictureRef? {
        val head = src.read(dataPos, min(len, 2048))
        if (head.size < 32) return null
        var p = 0
        val type = be32(head, p); p += 4
        val mimeLen = be32(head, p); p += 4
        if (mimeLen < 0 || p + mimeLen > head.size) return null
        val mime = String(head, p, mimeLen, Charsets.ISO_8859_1).ifEmpty { "image/jpeg" }; p += mimeLen
        if (p + 4 > head.size) return null
        val descLen = be32(head, p); p += 4
        if (descLen < 0 || p + descLen + 20 > head.size) return null
        p += descLen
        p += 16 // width, height, depth, colors
        val dataLen = be32(head, p); p += 4
        if (dataLen <= 0 || p + dataLen > len) return null
        return PictureRef(dataPos + p, dataLen, mime, type)
    }

    private fun parseVorbisComment(b: ByteArray, tags: AudioTags) {
        if (b.size < 8) return
        var p = 0
        val vlen = le32(b, p); p += 4
        if (vlen < 0 || p + vlen + 4 > b.size) return
        p += vlen
        val n = le32(b, p); p += 4
        var i = 0
        while (i++ < n && p + 4 <= b.size) {
            val l = le32(b, p); p += 4
            if (l < 0 || p + l > b.size) return
            val s = String(b, p, l, Charsets.UTF_8)
            p += l
            val eq = s.indexOf('=')
            if (eq <= 0) continue
            val key = s.substring(0, eq).uppercase()
            val v = s.substring(eq + 1).trim()
            if (v.isEmpty()) continue
            when (key) {
                "TITLE" -> if (tags.title == null) tags.title = v
                "ARTIST" -> if (tags.artist == null) tags.artist = v
                "ALBUM" -> if (tags.album == null) tags.album = v
                "ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST" -> if (tags.albumArtist == null) tags.albumArtist = v
                "GENRE" -> if (tags.genre == null) tags.genre = v
                "DATE", "YEAR", "ORIGINALDATE", "ORIGINALYEAR" -> if (tags.year == null) tags.year = parseYear(v)
                "TRACKNUMBER" -> if (tags.track == null) tags.track = parseLeadingInt(v)
                "DISCNUMBER" -> if (tags.disc == null) tags.disc = parseLeadingInt(v)
            }
        }
    }

    companion object {
        private val WIN1250: Charset = Charset.forName("windows-1250")

        // Č č Ć ć Đ đ in cp1250 (È è Æ æ Ð ð in Latin-1).
        private val CP1250_HINTS = setOf(0xC8, 0xE8, 0xC6, 0xE6, 0xD0, 0xF0)

        private val WANTED_TEXT = setOf("TIT2", "TPE1", "TALB", "TPE2", "TCON", "TRCK", "TPOS", "TYER", "TDRC", "TORY", "TDOR", "TLEN")

        private val BR_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
        private val BR_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
        private val BR_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val BR_V2_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
        private val BR_V2_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        private val SR_V1 = intArrayOf(44100, 48000, 32000)

        fun syncsafe(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or
                ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

        fun be32(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

        fun be24(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)

        fun le32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        fun deUnsync(b: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(b.size)
            var i = 0
            while (i < b.size) {
                val v = b[i].toInt() and 0xFF
                out.write(v)
                if (v == 0xFF && i + 1 < b.size && b[i + 1].toInt() == 0) i++
                i++
            }
            return out.toByteArray()
        }

        private fun indexOfZero(b: ByteArray, from: Int): Int {
            for (i in from until b.size) if (b[i].toInt() == 0) return i
            return -1
        }

        private fun indexOfDoubleZero(b: ByteArray, from: Int): Int {
            var i = from
            while (i + 1 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i
                i += 2
            }
            return -1
        }

        fun parseYear(s: String): Int? = Regex("(\\d{4})").find(s)?.groupValues?.get(1)?.toIntOrNull()

        fun parseLeadingInt(s: String): Int? = Regex("^\\s*(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull()

        fun parseGenre(raw: String): String {
            val s = raw.trim()
            Regex("^\\((\\d+)\\)(.*)$").find(s)?.let { m ->
                val rest = m.groupValues[2].trim()
                if (rest.isNotEmpty()) return rest
                val n = m.groupValues[1].toInt()
                return ID3_GENRES.getOrNull(n) ?: s
            }
            s.toIntOrNull()?.let { return ID3_GENRES.getOrNull(it) ?: s }
            return when (s) { "RX" -> "Remix"; "CR" -> "Cover"; else -> s }
        }

        val ID3_GENRES = arrayOf(
            "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
            "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
            "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
            "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
            "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
            "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
            "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
            "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
            "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion", "Bebop", "Latin", "Revival", "Celtic", "Bluegrass",
            "Avantgarde", "Gothic Rock", "Progressive Rock", "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band", "Chorus", "Easy Listening", "Acoustic",
            "Humour", "Speech", "Chanson", "Opera", "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus", "Porn Groove",
            "Satire", "Slow Jam", "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle",
            "Duet", "Punk Rock", "Drum Solo", "A Cappella", "Euro-House", "Dance Hall", "Goa", "Drum & Bass", "Club-House", "Hardcore",
            "Terror", "Indie", "BritPop", "Negerpunk", "Polsk Punk", "Beat", "Christian Gangsta Rap", "Heavy Metal", "Black Metal", "Crossover",
            "Contemporary Christian", "Christian Rock", "Merengue", "Salsa", "Thrash Metal", "Anime", "JPop", "Synthpop",
        )
    }
}
