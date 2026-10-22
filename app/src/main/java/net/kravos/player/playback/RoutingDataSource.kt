package net.kravos.player.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import net.kravos.player.drive.DriveApi

/**
 * Sends Google Drive requests through the offline/stream caches and everything else (live radio)
 * straight to the network: an endless radio stream must never be written into the song cache.
 */
@OptIn(UnstableApi::class)
class RoutingDataSource(
    private val drive: DataSource,
    private val other: DataSource,
    private val isOffline: (Uri) -> Boolean,
) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        drive.addTransferListener(transferListener)
        other.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // Drive files and downloaded podcast episodes go through the offline/stream caches.
        val target = if (dataSpec.uri.host == DriveApi.HOST || isOffline(dataSpec.uri)) drive else other
        current = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: throw IllegalStateException("read before open")

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        val c = current
        current = null
        c?.close()
    }

    class Factory(
        private val drive: DataSource.Factory,
        private val other: DataSource.Factory,
        private val isOffline: (Uri) -> Boolean,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(drive.createDataSource(), other.createDataSource(), isOffline)
    }
}
