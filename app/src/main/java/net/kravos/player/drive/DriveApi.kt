package net.kravos.player.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.kravos.player.auth.GoogleAuth
import net.kravos.player.tags.RangeSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.random.Random

/** Adds the Google bearer token to Drive API requests and retries once on 401. */
class AuthInterceptor(private val auth: GoogleAuth) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host != DriveApi.HOST || req.header("Authorization") != null) return chain.proceed(req)
        val token = auth.tokenBlocking() ?: throw NotAuthorizedException()
        val resp = chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
        if (resp.code != 401) return resp
        resp.close()
        auth.invalidate(token)
        val fresh = auth.tokenBlocking() ?: throw NotAuthorizedException()
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $fresh").build())
    }
}

class NotAuthorizedException : IOException("Google Drive is not connected or needs re-authorization")

class DriveException(val code: Int, message: String) : IOException(message)

data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modifiedTime: String,
) {
    val isFolder: Boolean get() = mimeType == DriveApi.FOLDER_MIME
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

class DriveApi(private val http: OkHttpClient) {

    suspend fun about(): String? = withContext(Dispatchers.IO) {
        val url = "$BASE/about".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "user(displayName,emailAddress)")
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw DriveException(r.code, r.body?.string() ?: "")
            val user = JSONObject(r.body!!.string()).optJSONObject("user")
            user?.optString("emailAddress")?.ifEmpty { null } ?: user?.optString("displayName")
        }
    }

    /** All direct children of a folder (any type), following pagination. */
    suspend fun listChildren(folderId: String, foldersOnly: Boolean = false): List<DriveFile> =
        query(
            buildString {
                append("'").append(folderId.replace("'", "\\'")).append("' in parents and trashed = false")
                if (foldersOnly) append(" and mimeType = '$FOLDER_MIME'")
            },
        )

    /** Folders shared with the user (top level of "Shared with me"). */
    suspend fun sharedFolders(): List<DriveFile> =
        query("sharedWithMe = true and mimeType = '$FOLDER_MIME' and trashed = false", orderBy = null)
            .sortedBy { it.name.lowercase() }

    suspend fun query(q: String, orderBy: String? = "folder,name"): List<DriveFile> = withContext(Dispatchers.IO) {
        val out = ArrayList<DriveFile>()
        var pageToken: String? = null
        do {
            val url = "$BASE/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size,modifiedTime)")
                .addQueryParameter("pageSize", "1000")
                .apply { orderBy?.let { addQueryParameter("orderBy", it) } }
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val body = retrying {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    val b = r.body?.string() ?: ""
                    if (!r.isSuccessful) throw DriveException(r.code, b)
                    b
                }
            }
            run {
                val json = JSONObject(body)
                val files = json.optJSONArray("files")
                if (files != null) {
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        out += DriveFile(
                            id = f.getString("id"),
                            name = f.optString("name"),
                            mimeType = f.optString("mimeType"),
                            size = f.optString("size", "0").toLongOrNull() ?: 0L,
                            modifiedTime = f.optString("modifiedTime"),
                        )
                    }
                }
                pageToken = json.optString("nextPageToken").ifEmpty { null }
            }
        } while (pageToken != null)
        out
    }

    /** Reads [length] bytes at [offset] with an HTTP Range request. */
    suspend fun readRange(fileId: String, offset: Long, length: Int): ByteArray =
        if (length <= 0) ByteArray(0) else retrying { readRangeOnce(fileId, offset, length) }

    private suspend fun readRangeOnce(fileId: String, offset: Long, length: Int): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(mediaUrl(fileId))
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 416) return@use ByteArray(0)
            if (!r.isSuccessful) throw DriveException(r.code, "range read failed for $fileId: " + (r.body?.string()?.take(300) ?: ""))
            val body = r.body ?: return@use ByteArray(0)
            if (r.code == 206) {
                body.bytes()
            } else {
                // Server ignored Range: read only what we asked for.
                val src = body.byteStream()
                if (offset > 0) src.skip(offset)
                val buf = ByteArray(length)
                var n = 0
                while (n < length) {
                    val k = src.read(buf, n, length - n)
                    if (k < 0) break
                    n += k
                }
                if (n == length) buf else buf.copyOf(n)
            }
        }
    }

    suspend fun download(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(mediaUrl(fileId)).build()).execute().use { r ->
            if (!r.isSuccessful) throw DriveException(r.code, "download failed for $fileId")
            r.body!!.bytes()
        }
    }

    fun source(file: DriveFile): RangeSource = object : RangeSource {
        override val size: Long = file.size
        override suspend fun read(offset: Long, length: Int): ByteArray = readRange(file.id, offset, length)
    }

    /** Retries rate-limit, server and network errors with exponential backoff. */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: NotAuthorizedException) {
                throw e
            } catch (e: DriveException) {
                val retryable = e.code == 429 || e.code >= 500 || (e.code == 403 && (e.message ?: "").contains("ateLimit"))
                if (!retryable || attempt >= 5) throw e
            } catch (e: IOException) {
                if (attempt >= 3) throw e
            }
            delay((500L shl attempt) + Random.nextLong(300))
            attempt++
        }
    }

    companion object {
        const val HOST = "www.googleapis.com"
        const val BASE = "https://www.googleapis.com/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"

        /** Stable URL for a file's content. Also used as the cache key, so never add volatile params. */
        fun mediaUrl(fileId: String): String = "$BASE/files/$fileId?alt=media&supportsAllDrives=true"

        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        fun mimeForExtension(ext: String, fallback: String): String = when (ext) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "oga" -> "audio/ogg"
            "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> fallback
        }
    }
}
