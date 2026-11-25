package net.kravos.player.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kravos.player.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UpdateInfo(val build: Int, val version: String, val apkUrl: String, val sizeBytes: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update from the GitHub releases of this repo: CI publishes every build as release "build-N"
 * with the signed APK attached. Same signing key, so Android installs it over the current version.
 */
class Updater(private val context: Context, baseHttp: OkHttpClient, private val scope: CoroutineScope) {

    private val http = baseHttp.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val sp = context.getSharedPreferences("kravos_update", Context.MODE_PRIVATE)
    private val dir = File(context.cacheDir, "updates")
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Checks at most every few hours unless [force]d (the Settings button forces it). */
    fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong(K_LAST, 0L) < CHECK_EVERY_MS && _state.value !is UpdateState.Failed) return
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        job = scope.launch {
            _state.value = try {
                val info = withContext(Dispatchers.IO) { latest() }
                sp.edit().putLong(K_LAST, now).apply()
                when {
                    info == null || info.build <= BuildConfig.BUILD_NUMBER -> UpdateState.UpToDate
                    else -> readyFile(info)?.let { UpdateState.Ready(info, it) } ?: UpdateState.Available(info)
                }
            } catch (e: Exception) {
                UpdateState.Failed(if (e is NotPublic) e.message!! else "Can't check for updates (${e.message ?: "network"})")
            }
        }
    }

    private class NotPublic : IOException("Can't see the releases: the GitHub repo ${BuildConfig.GITHUB_REPO} must be public.")

    private fun latest(): UpdateInfo? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "KravosPlayer/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) throw NotPublic()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val o = JSONObject(r.body!!.string())
            val build = o.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return UpdateInfo(build, "1.0.$build", a.getString("browser_download_url"), a.optLong("size"))
                }
            }
            return null
        }
    }

    private fun apkFile(info: UpdateInfo) = File(dir, "KravosPlayer-${info.version}.apk")

    private fun readyFile(info: UpdateInfo): File? =
        apkFile(info).takeIf { it.isFile && (info.sizeBytes <= 0 || it.length() == info.sizeBytes) }

    fun download() {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.Failed -> return check(force = true)
            else -> return
        }
        _state.value = UpdateState.Downloading(info, 0f)
        job = scope.launch {
            _state.value = try {
                val file = withContext(Dispatchers.IO) { fetch(info) }
                UpdateState.Ready(info, file)
            } catch (e: Exception) {
                UpdateState.Failed("Download failed (${e.message ?: "network"})")
            }
        }
    }

    private fun fetch(info: UpdateInfo): File {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() } // keep only the newest download
        val target = apkFile(info)
        val tmp = File(dir, "download.tmp")
        http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastShown = 0f
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = done.toFloat() / total
                            if (p - lastShown >= 0.02f) {
                                lastShown = p
                                _state.value = UpdateState.Downloading(info, p)
                            }
                        }
                    }
                }
            }
        }
        if (!tmp.renameTo(target)) throw IOException("can't save the file")
        return target
    }

    /** Hands the APK to Android's installer (first asks for the "install unknown apps" permission). */
    fun install(ctx: Context) {
        val ready = _state.value as? UpdateState.Ready ?: return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", ready.file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        private const val K_LAST = "last_check"
        private const val CHECK_EVERY_MS = 3 * 60 * 60 * 1000L
    }
}
