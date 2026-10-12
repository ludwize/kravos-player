package net.kravos.player.drive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import net.kravos.player.data.AppDatabase
import net.kravos.player.data.Folder
import net.kravos.player.data.Prefs
import net.kravos.player.data.Track
import net.kravos.player.tags.AudioTags
import net.kravos.player.tags.BufferedRangeSource
import net.kravos.player.tags.FileNameInfo
import net.kravos.player.tags.PictureRef
import net.kravos.player.tags.TagReader
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max

data class ScanState(
    val running: Boolean = false,
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    val lastResult: String? = null,
)

class LibraryScanner(
    private val context: Context,
    private val drive: DriveApi,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private var job: Job? = null
    private val artDir = File(context.filesDir, "art").apply { mkdirs() }

    fun start(full: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                scan(full)
            } catch (e: Exception) {
                Log.w(TAG, "scan failed", e)
                _state.value = _state.value.copy(running = false, error = describe(e))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = _state.value.copy(running = false, phase = "Cancelled")
    }

    private class Node(val id: String, val name: String, val parentId: String?, val depth: Int, val parentName: String?)

    private suspend fun scan(full: Boolean) {
        val dao = db.dao()
        val roots = prefs.roots.value
        _state.value = ScanState(running = true, phase = "Reading folders…")
        if (roots.isEmpty()) {
            _state.value = ScanState(running = false, lastResult = "No music folders selected")
            return
        }
        if (prefs.account.value == null) runCatching { drive.about() }.getOrNull()?.let(prefs::setAccount)

        // 1) Walk the folder tree.
        val nodes = LinkedHashMap<String, Node>()
        val audio = ArrayList<Pair<DriveFile, Node>>()
        val covers = HashMap<String, String>() // folderId -> image file id
        var walkErrors = 0
        val queue = ArrayDeque<Node>()
        roots.forEach { r -> queue.add(Node(r.id, r.name, null, 0, null)) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (nodes.containsKey(n.id)) continue
            nodes[n.id] = n
            val children = try {
                drive.listChildren(n.id)
            } catch (e: NotAuthorizedException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "list failed for ${n.name}", e)
                walkErrors++
                continue
            }
            val images = ArrayList<DriveFile>()
            for (c in children) {
                when {
                    c.isFolder -> queue.add(Node(c.id, c.name, n.id, n.depth + 1, n.name))
                    c.extension in DriveApi.AUDIO_EXTENSIONS -> audio += c to n
                    c.extension in DriveApi.IMAGE_EXTENSIONS || c.mimeType.startsWith("image/") -> images += c
                }
            }
            pickCover(images)?.let { covers[n.id] = it.id }
            _state.value = _state.value.copy(phase = "Reading folders… ${nodes.size} folders, ${audio.size} songs")
        }

        // 2) Decide what needs (re)reading.
        val known = dao.scanInfo().associateBy { it.id }
        val seenIds = audio.mapTo(HashSet()) { it.first.id }
        val todo = audio.filter { (f, _) -> full || known[f.id]?.modifiedTime != f.modifiedTime }
        _state.value = _state.value.copy(phase = "Reading tags…", done = 0, total = todo.size)

        val legacy = prefs.legacyCharset.value
        val pictures = HashMap<String, Pair<DriveFile, PictureRef>>()
        val buffer = ArrayList<Track>()
        val bufLock = Mutex()
        val sem = Semaphore(PARALLEL)
        var done = 0
        val now = System.currentTimeMillis()

        suspend fun flush(force: Boolean) {
            val batch: List<Track> = bufLock.withLock {
                if (!force && buffer.size < 40) return
                val b = ArrayList(buffer); buffer.clear(); b
            }
            if (batch.isEmpty()) return
            val prev = batch.map { it.id }.chunked(500).flatMap { dao.existing(it) }.associateBy { it.id }
            dao.upsertTracks(batch.map { t ->
                val p = prev[t.id] ?: return@map t
                t.copy(
                    addedAt = p.addedAt,
                    playCount = p.playCount,
                    lastPlayedAt = p.lastPlayedAt,
                    artUri = t.artUri ?: p.artUri,
                )
            })
        }

        coroutineScope {
            todo.map { (file, node) ->
                async {
                    sem.withPermit {
                        val tags = try {
                            TagReader(legacy).read(BufferedRangeSource(drive.source(file)), file.extension)
                        } catch (e: NotAuthorizedException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "tags failed for ${file.name}", e)
                            AudioTags(format = file.extension.uppercase())
                        }
                        val track = buildTrack(file, node, tags, covers[node.id], now)
                        tags.picture?.let { synchronized(pictures) { pictures[file.id] = file to it } }
                        bufLock.withLock {
                            buffer += track
                            done++
                        }
                        _state.value = _state.value.copy(done = done)
                        flush(false)
                    }
                }
            }.awaitAll()
        }
        flush(true)

        // 3) Folder covers win over embedded art; refresh them for unchanged tracks too.
        _state.value = _state.value.copy(phase = "Album art…")
        covers.forEach { (folderId, imageId) -> dao.setFolderArt(folderId, DriveApi.mediaUrl(imageId)) }

        // 4) Extract embedded art once per album that has nothing else.
        val needing = dao.albumsNeedingEmbeddedArt()
        for (t in needing) {
            try {
                val (file, ref) = pictures[t.id] ?: run {
                    val df = DriveFile(t.id, t.fileName, t.mimeType, t.size, t.modifiedTime)
                    val tags = TagReader(legacy).read(BufferedRangeSource(drive.source(df)), df.extension)
                    tags.picture?.let { df to it }
                } ?: continue
                if (ref.length <= 0 || ref.length > MAX_ART_BYTES) continue
                val bytes = TagReader(legacy).readPicture(drive.source(file), ref)
                val out = saveArt(t.albumKey, bytes) ?: continue
                dao.setAlbumArtIfMissing(t.albumKey, Uri.fromFile(out).toString())
            } catch (e: NotAuthorizedException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "art failed for ${t.fileName}", e)
            }
        }

        // 5) Remove what disappeared — only when the whole tree was readable.
        var removed = 0
        if (walkErrors == 0) {
            val gone = known.keys.filterNot { it in seenIds }
            gone.chunked(500).forEach { dao.deleteTracks(it) }
            removed = gone.size
            db.withTransaction {
                dao.deleteAllFolders()
                dao.upsertFolders(nodes.values.map { Folder(it.id, it.name, it.parentId, covers[it.id]) })
            }
        } else {
            dao.upsertFolders(nodes.values.map { Folder(it.id, it.name, it.parentId, covers[it.id]) })
        }
        dao.markCompilations()

        prefs.setLastScan(System.currentTimeMillis())
        val summary = buildString {
            append("${audio.size} songs")
            if (todo.isNotEmpty()) append(", ${todo.size} read")
            if (removed > 0) append(", $removed removed")
            if (walkErrors > 0) append(" — $walkErrors folders could not be read")
        }
        _state.value = ScanState(running = false, lastResult = summary)
    }

    private fun buildTrack(file: DriveFile, node: Node, tags: AudioTags, coverId: String?, now: Long): Track {
        val fn = FileNameInfo.parse(file.name)
        val title = tags.title ?: fn.title
        val album = tags.album ?: if (node.depth >= 1) node.name else "Unknown Album"
        val artist = tags.artist ?: tags.albumArtist ?: fn.artist
            ?: (if (node.depth >= 2) node.parentName else null) ?: "Unknown Artist"
        val albumArtist = tags.albumArtist ?: artist
        val albumKey = if (tags.albumArtist != null) {
            "a:" + albumArtist.lowercase().trim() + "|" + album.lowercase().trim()
        } else {
            "f:" + node.id + "|" + album.lowercase().trim()
        }
        return Track(
            id = file.id,
            folderId = node.id,
            fileName = file.name,
            mimeType = DriveApi.mimeForExtension(file.extension, file.mimeType),
            size = file.size,
            modifiedTime = file.modifiedTime,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = tags.genre ?: "",
            year = tags.year ?: 0,
            trackNo = tags.track ?: fn.track ?: 0,
            discNo = tags.disc ?: fn.disc ?: 1,
            durationMs = tags.durationMs,
            format = tags.format.ifEmpty { file.extension.uppercase() },
            sampleRate = tags.sampleRate,
            bitsPerSample = tags.bitsPerSample,
            bitrateKbps = tags.bitrateKbps,
            hasEmbeddedArt = tags.picture != null,
            artUri = coverId?.let { DriveApi.mediaUrl(it) },
            albumKey = albumKey,
            addedAt = now,
        )
    }

    private fun pickCover(images: List<DriveFile>): DriveFile? {
        if (images.isEmpty()) return null
        val ranked = images.sortedBy { f ->
            val n = f.name.substringBeforeLast('.').lowercase()
            when {
                n == "cover" -> 0
                n == "folder" -> 1
                n == "front" -> 2
                n.startsWith("cover") -> 3
                n.contains("front") -> 4
                n == "album" || n.startsWith("albumart") -> 5
                n.contains("cover") -> 6
                n.contains("back") || n.contains("cd") || n.contains("inlay") -> 9
                else -> 7
            }
        }
        return ranked.first()
    }

    private fun saveArt(albumKey: String, bytes: ByteArray): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ART_SIZE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = ART_SIZE.toFloat() / max(bmp.width, bmp.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = File(artDir, sha1(albumKey) + ".jpg")
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return out
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "Scanner"
        private const val PARALLEL = 6
        private const val ART_SIZE = 640
        private const val MAX_ART_BYTES = 12 * 1024 * 1024

        fun describe(e: Throwable): String = when (e) {
            is NotAuthorizedException -> "Google Drive needs to be (re)connected"
            is DriveException -> "Drive error ${e.code}"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
