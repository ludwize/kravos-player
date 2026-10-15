package net.kravos.player.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "tracks",
    indices = [Index("albumKey"), Index("albumArtist"), Index("folderId"), Index("genre"), Index("addedAt")],
)
data class Track(
    @PrimaryKey val id: String,
    val folderId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val modifiedTime: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: Int,
    val trackNo: Int,
    val discNo: Int,
    val durationMs: Long,
    val format: String,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val bitrateKbps: Int,
    val hasEmbeddedArt: Boolean,
    val artUri: String?,
    val albumKey: String,
    val addedAt: Long,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0,
)

@Entity(tableName = "folders", indices = [Index("parentId")])
data class Folder(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val coverImageId: String?,
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "position"], indices = [Index("trackId")])
data class PlaylistTrack(
    val playlistId: Long,
    val position: Int,
    val trackId: String,
)

data class AlbumInfo(
    val albumKey: String,
    val album: String,
    val albumArtist: String,
    val year: Int,
    val trackCount: Int,
    val artUri: String?,
)

data class ArtistInfo(
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
    val artUri: String?,
)

data class GenreInfo(
    val name: String,
    val trackCount: Int,
    val artUri: String?,
)

data class PlaylistInfo(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val artUri: String?,
)

data class ScanInfo(
    val id: String,
    val modifiedTime: String,
    @ColumnInfo(name = "artUri") val artUri: String?,
)

@Dao
abstract class LibraryDao {
    // ---- tracks
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    abstract fun songs(): Flow<List<Track>>

    @Query("SELECT COUNT(*) FROM tracks")
    abstract fun trackCount(): Flow<Int>

    @Query("SELECT * FROM tracks WHERE id = :id")
    abstract suspend fun track(id: String): Track?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    abstract suspend fun tracksByIds(ids: List<String>): List<Track>

    @Query("SELECT * FROM tracks WHERE albumKey = :key ORDER BY discNo, trackNo, title COLLATE NOCASE")
    abstract fun albumTracks(key: String): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE albumKey = :key ORDER BY discNo, trackNo, title COLLATE NOCASE")
    abstract suspend fun albumTracksOnce(key: String): List<Track>

    @Query("SELECT * FROM tracks WHERE albumArtist = :name ORDER BY year, album COLLATE NOCASE, discNo, trackNo")
    abstract fun artistTracks(name: String): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE genre = :name ORDER BY albumArtist COLLATE NOCASE, year, album COLLATE NOCASE, discNo, trackNo")
    abstract fun genreTracks(name: String): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE folderId = :folderId ORDER BY discNo, trackNo, fileName COLLATE NOCASE")
    abstract fun folderTracks(folderId: String): Flow<List<Track>>

    @Query(
        "SELECT * FROM tracks WHERE title LIKE :q OR artist LIKE :q OR album LIKE :q OR albumArtist LIKE :q " +
            "ORDER BY title COLLATE NOCASE LIMIT 300",
    )
    abstract fun searchTracks(q: String): Flow<List<Track>>

    @Query("SELECT * FROM tracks ORDER BY addedAt DESC, albumKey, discNo, trackNo LIMIT 300")
    abstract fun recentlyAdded(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE playCount > 0 ORDER BY playCount DESC, lastPlayedAt DESC LIMIT 200")
    abstract fun mostPlayed(): Flow<List<Track>>

    @Query("SELECT * FROM tracks WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT 200")
    abstract fun recentlyPlayed(): Flow<List<Track>>

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :at WHERE id = :id")
    abstract suspend fun markPlayed(id: String, at: Long)

    // ---- albums / artists / genres
    @Query(
        "SELECT albumKey, album, albumArtist, MAX(year) AS year, COUNT(*) AS trackCount, MAX(artUri) AS artUri " +
            "FROM tracks GROUP BY albumKey ORDER BY albumArtist COLLATE NOCASE, year, album COLLATE NOCASE",
    )
    abstract fun albums(): Flow<List<AlbumInfo>>

    @Query(
        "SELECT albumKey, album, albumArtist, MAX(year) AS year, COUNT(*) AS trackCount, MAX(artUri) AS artUri " +
            "FROM tracks WHERE albumArtist = :artist GROUP BY albumKey ORDER BY year, album COLLATE NOCASE",
    )
    abstract fun artistAlbums(artist: String): Flow<List<AlbumInfo>>

    @Query(
        "SELECT albumKey, album, albumArtist, MAX(year) AS year, COUNT(*) AS trackCount, MAX(artUri) AS artUri " +
            "FROM tracks WHERE album LIKE :q OR albumArtist LIKE :q GROUP BY albumKey ORDER BY album COLLATE NOCASE LIMIT 100",
    )
    abstract fun searchAlbums(q: String): Flow<List<AlbumInfo>>

    @Query(
        "SELECT albumArtist AS name, COUNT(DISTINCT albumKey) AS albumCount, COUNT(*) AS trackCount, MAX(artUri) AS artUri " +
            "FROM tracks GROUP BY albumArtist ORDER BY albumArtist COLLATE NOCASE",
    )
    abstract fun artists(): Flow<List<ArtistInfo>>

    @Query(
        "SELECT albumArtist AS name, COUNT(DISTINCT albumKey) AS albumCount, COUNT(*) AS trackCount, MAX(artUri) AS artUri " +
            "FROM tracks WHERE albumArtist LIKE :q OR artist LIKE :q GROUP BY albumArtist ORDER BY albumArtist COLLATE NOCASE LIMIT 50",
    )
    abstract fun searchArtists(q: String): Flow<List<ArtistInfo>>

    @Query(
        "SELECT genre AS name, COUNT(*) AS trackCount, MAX(artUri) AS artUri FROM tracks " +
            "GROUP BY genre ORDER BY genre COLLATE NOCASE",
    )
    abstract fun genres(): Flow<List<GenreInfo>>

    // ---- folders
    @Query("SELECT * FROM folders WHERE parentId IS NULL ORDER BY name COLLATE NOCASE")
    abstract fun rootFolders(): Flow<List<Folder>>

    @Query("SELECT * FROM folders")
    abstract suspend fun allFoldersOnce(): List<Folder>

    @Query("SELECT * FROM folders WHERE name LIKE :q ORDER BY name COLLATE NOCASE LIMIT 200")
    abstract fun searchFolders(q: String): Flow<List<Folder>>

    @Query("SELECT * FROM tracks WHERE folderId IN (:ids)")
    abstract suspend fun tracksInFolders(ids: List<String>): List<Track>

    @Query("SELECT * FROM folders WHERE parentId = :id ORDER BY name COLLATE NOCASE")
    abstract fun childFolders(id: String): Flow<List<Folder>>

    // ---- scanning
    @Query("SELECT id, modifiedTime, artUri FROM tracks")
    abstract suspend fun scanInfo(): List<ScanInfo>

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    abstract suspend fun existing(ids: List<String>): List<Track>

    @Upsert
    abstract suspend fun upsertTracks(tracks: List<Track>)

    @Upsert
    abstract suspend fun upsertFolders(folders: List<Folder>)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    abstract suspend fun deleteTracks(ids: List<String>)

    @Query("DELETE FROM folders WHERE id NOT IN (:keep)")
    abstract suspend fun deleteFoldersExcept(keep: List<String>)

    @Query("DELETE FROM folders")
    abstract suspend fun deleteAllFolders()

    @Query("DELETE FROM tracks")
    abstract suspend fun deleteAllTracks()

    @Query("UPDATE tracks SET artUri = :art WHERE albumKey = :albumKey AND artUri IS NULL")
    abstract suspend fun setAlbumArtIfMissing(albumKey: String, art: String)

    @Query("UPDATE tracks SET artUri = :art WHERE folderId = :folderId")
    abstract suspend fun setFolderArt(folderId: String, art: String)

    @Query(
        "UPDATE tracks SET albumArtist = 'Various Artists' WHERE albumKey IN " +
            "(SELECT albumKey FROM tracks WHERE albumKey LIKE 'f:%' GROUP BY albumKey HAVING COUNT(DISTINCT artist) > 1)",
    )
    abstract suspend fun markCompilations()

    @Query("SELECT * FROM tracks WHERE artUri IS NULL AND hasEmbeddedArt = 1 GROUP BY albumKey")
    abstract suspend fun albumsNeedingEmbeddedArt(): List<Track>

    // ---- playlists
    @Query(
        "SELECT p.id AS id, p.name AS name, COUNT(pt.trackId) AS trackCount, " +
            "(SELECT t.artUri FROM playlist_tracks x JOIN tracks t ON t.id = x.trackId WHERE x.playlistId = p.id AND t.artUri IS NOT NULL ORDER BY x.position LIMIT 1) AS artUri " +
            "FROM playlists p LEFT JOIN playlist_tracks pt ON pt.playlistId = p.id GROUP BY p.id ORDER BY p.name COLLATE NOCASE",
    )
    abstract fun playlists(): Flow<List<PlaylistInfo>>

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    abstract suspend fun playlistsOnce(): List<Playlist>

    @Query("SELECT t.* FROM playlist_tracks pt JOIN tracks t ON t.id = pt.trackId WHERE pt.playlistId = :id ORDER BY pt.position")
    abstract fun playlistTracks(id: Long): Flow<List<Track>>

    @Query("SELECT trackId FROM playlist_tracks WHERE playlistId = :id ORDER BY position")
    abstract suspend fun playlistTrackIds(id: Long): List<String>

    @Insert
    abstract suspend fun insertPlaylist(p: Playlist): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :at WHERE id = :id")
    abstract suspend fun renamePlaylist(id: Long, name: String, at: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun deletePlaylistRow(id: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :id")
    abstract suspend fun clearPlaylist(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertPlaylistTracks(rows: List<PlaylistTrack>)

    @Transaction
    open suspend fun setPlaylistTracks(id: Long, trackIds: List<String>) {
        clearPlaylist(id)
        insertPlaylistTracks(trackIds.mapIndexed { i, t -> PlaylistTrack(id, i, t) })
    }

    @Transaction
    open suspend fun appendToPlaylist(id: Long, trackIds: List<String>) {
        val current = playlistTrackIds(id)
        setPlaylistTracks(id, current + trackIds)
    }

    @Transaction
    open suspend fun deletePlaylist(id: Long) {
        clearPlaylist(id)
        deletePlaylistRow(id)
    }
}

@Database(entities = [Track::class, Folder::class, Playlist::class, PlaylistTrack::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "library.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
