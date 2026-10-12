package net.kravos.player.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import net.kravos.player.drive.DriveApi
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Tiny LAN HTTP server so a Chromecast can fetch Drive files: the Cast receiver cannot send our
 * OAuth header, so it asks the phone, and the phone streams from Drive (with Range support).
 * Every URL carries a random per-session secret, so other devices on the network can't browse it.
 */
class CastProxyServer(private val context: Context, private val http: OkHttpClient, private val artDir: File) {

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val secret: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val mimes = ConcurrentHashMap<String, String>()

    @Volatile
    var baseUrl: String? = null
        private set

    @Synchronized
    fun start(): Boolean {
        if (server != null) return baseUrl != null
        val ip = lanAddress() ?: return false
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(0))
            server = ss
            baseUrl = "http://$ip:${ss.localPort}/$secret"
            Thread({ acceptLoop(ss) }, "cast-proxy").apply { isDaemon = true }.start()
            true
        } catch (e: Exception) {
            Log.w(TAG, "cannot start proxy", e)
            false
        }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        baseUrl = null
    }

    fun trackUrl(id: String, mime: String?): String? {
        val base = baseUrl ?: return null
        if (mime != null) mimes[id] = mime
        return "$base/t/$id"
    }

    fun artUrl(art: String?): String? {
        val base = baseUrl ?: return null
        if (art == null) return null
        return when {
            art.startsWith(DriveApi.BASE) -> "$base/d/" + art.substringAfter("/files/").substringBefore('?')
            art.startsWith("file:") -> Uri.parse(art).lastPathSegment?.let { "$base/f/$it" }
            else -> null
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try { ss.accept() } catch (e: Exception) { break }
            pool.execute { runCatching { handle(s) }.onFailure { Log.d(TAG, "request failed: $it") }; runCatching { s.close() } }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return respond(out, 400, "Bad Request")
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')
        if (method == "OPTIONS") return respondCors(out)
        if (method != "GET" && method != "HEAD") return respond(out, 405, "Method Not Allowed")
        val seg = path.trim('/').split('/')
        if (seg.size != 3 || seg[0] != secret) return respond(out, 404, "Not Found")
        val kind = seg[1]
        val id = seg[2]
        if (!id.matches(Regex("[A-Za-z0-9_\\-.]+"))) return respond(out, 400, "Bad Request")
        when (kind) {
            "t", "d" -> proxyDrive(out, id, headers["range"], method == "HEAD", if (kind == "t") mimes[id] else null)
            "f" -> serveFile(out, File(artDir, id), method == "HEAD")
            else -> respond(out, 404, "Not Found")
        }
    }

    private fun proxyDrive(out: OutputStream, id: String, range: String?, head: Boolean, mime: String?) {
        val req = Request.Builder().url(DriveApi.mediaUrl(id)).apply { if (range != null) header("Range", range) }.build()
        http.newCall(req).execute().use { r ->
            if (r.code != 200 && r.code != 206) return respond(out, if (r.code == 416) 416 else 502, "Upstream ${r.code}")
            val sb = StringBuilder()
            sb.append("HTTP/1.1 ").append(r.code).append(if (r.code == 206) " Partial Content" else " OK").append("\r\n")
            sb.append("Content-Type: ").append(mime ?: r.header("Content-Type") ?: "application/octet-stream").append("\r\n")
            r.header("Content-Length")?.let { sb.append("Content-Length: ").append(it).append("\r\n") }
            r.header("Content-Range")?.let { sb.append("Content-Range: ").append(it).append("\r\n") }
            sb.append("Accept-Ranges: bytes\r\n")
            sb.append("Access-Control-Allow-Origin: *\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (!head) r.body?.byteStream()?.use { copy(it, out) }
            out.flush()
        }
    }

    private fun serveFile(out: OutputStream, f: File, head: Boolean) {
        if (!f.isFile || f.parentFile?.canonicalPath != artDir.canonicalPath) return respond(out, 404, "Not Found")
        val hdr = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.length()}\r\n" +
            "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
        out.write(hdr.toByteArray(Charsets.ISO_8859_1))
        if (!head) f.inputStream().use { copy(it, out) }
        out.flush()
    }

    private fun respondCors(out: OutputStream) {
        val hdr = "HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nAccess-Control-Allow-Headers: Range\r\n" +
            "Connection: close\r\n\r\n"
        out.write(hdr.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun respond(out: OutputStream, code: Int, msg: String) {
        val body = msg.toByteArray()
        val hdr = "HTTP/1.1 $code $msg\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(hdr.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun copy(input: InputStream, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun lanAddress(): String? {
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val lp = cm.getLinkProperties(cm.activeNetwork)
            lp?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }.getOrNull()?.let { return it }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }

    companion object {
        private const val TAG = "CastProxy"
    }
}
