package net.kravos.player

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import net.kravos.player.auth.GoogleAuth
import net.kravos.player.data.AppDatabase
import net.kravos.player.data.Prefs
import net.kravos.player.download.Downloads
import net.kravos.player.drive.AuthInterceptor
import net.kravos.player.drive.DriveApi
import net.kravos.player.drive.LibraryScanner
import net.kravos.player.playback.PlayerConnection
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class App : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(Downloads.CHANNEL_ID, getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { graph.http }
            .respectCacheHeaders(false)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "art_cache"))
                    .maxSizeBytes(300L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}

/** Sleep timer request shared between UI and playback service. */
data class SleepTimer(val endsAt: Long = 0L, val endOfTrack: Boolean = false) {
    val active: Boolean get() = endsAt > 0L || endOfTrack
}

/** A-B loop points in ms; -1 = unset. */
data class LoopPoints(val a: Long = -1L, val b: Long = -1L) {
    val active: Boolean get() = a >= 0 && b > a
}

/** Process-wide state the UI and the playback service both see (same process, no IPC needed). */
class PlaybackExtras {
    val sleep = MutableStateFlow(SleepTimer())
    val loop = MutableStateFlow(LoopPoints())
}

@OptIn(UnstableApi::class)
class AppGraph(val app: App) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val prefs = Prefs(app)
    val auth = GoogleAuth(app, prefs)

    val plainHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Same client, plus Google auth for www.googleapis.com. */
    val http: OkHttpClient = plainHttp.newBuilder()
        .addInterceptor(AuthInterceptor(auth))
        .build()

    val drive = DriveApi(http)
    val db: AppDatabase = AppDatabase.create(app)
    val scanner = LibraryScanner(app, drive, db, prefs, scope)
    val extras = PlaybackExtras()

    val databaseProvider by lazy { StandaloneDatabaseProvider(app) }

    val downloadCache: SimpleCache by lazy {
        SimpleCache(File(app.filesDir, "offline"), NoOpCacheEvictor(), databaseProvider)
    }

    val streamCache: SimpleCache by lazy {
        val bytes = prefs.streamCacheMb.value.toLong() * 1024 * 1024
        SimpleCache(File(app.cacheDir, "stream"), LeastRecentlyUsedCacheEvictor(bytes), databaseProvider)
    }

    val httpDataSourceFactory: OkHttpDataSource.Factory by lazy { OkHttpDataSource.Factory(http) }

    /** Offline copies first, then the LRU stream cache, then Drive. */
    val playbackDataSourceFactory: DataSource.Factory by lazy {
        val streaming = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(streaming)
            .setCacheWriteDataSinkFactory(null)
    }

    val downloads: Downloads by lazy {
        Downloads(app, Downloads.newManager(app), scope) { runCatching { downloadCache.cacheSpace }.getOrDefault(0L) }
    }

    val player = PlayerConnection(app)
}
