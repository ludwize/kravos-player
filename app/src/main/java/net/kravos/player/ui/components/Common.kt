package net.kravos.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import coil.compose.AsyncImage
import net.kravos.player.data.AlbumInfo
import net.kravos.player.data.PlaylistInfo
import net.kravos.player.data.Track

fun fmtDuration(ms: Long): String {
    if (ms <= 0) return ""
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun fmtPrecise(ms: Long): String {
    if (ms < 0) return "–"
    val m = ms / 60000
    val s = (ms % 60000) / 1000.0
    return "%d:%04.1f".format(m, s)
}

fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%.0f KB".format(b / 1024.0)
}

@Composable
fun ArtImage(
    uri: Any?,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    corner: Dp = 6.dp,
    fallback: ImageVector = Icons.Default.MusicNote,
) {
    val m = (if (size != null) modifier.size(size) else modifier)
        .clip(RoundedCornerShape(corner))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    val painter = rememberVectorPainter(fallback)
    if (uri == null) {
        Box(m, contentAlignment = Alignment.Center) {
            Icon(fallback, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxSize(0.45f))
        }
    } else {
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = m,
            contentScale = ContentScale.Crop,
            error = painter,
            fallback = painter,
        )
    }
}

@Composable
fun DownloadBadge(state: Int?, modifier: Modifier = Modifier) {
    when (state) {
        Download.STATE_COMPLETED -> Icon(Icons.Default.DownloadDone, "Downloaded", modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Download.STATE_DOWNLOADING, Download.STATE_QUEUED, Download.STATE_RESTARTING ->
            Icon(Icons.Default.Downloading, "Downloading", modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {}
    }
}

/** Everything a track row's menu can do; null = hidden. */
class TrackActions(
    val onPlayNext: ((Track) -> Unit)? = null,
    val onEnqueue: ((Track) -> Unit)? = null,
    val onAddToPlaylist: ((Track) -> Unit)? = null,
    val onDownload: ((Track) -> Unit)? = null,
    val onRemoveDownload: ((Track) -> Unit)? = null,
    val onGoAlbum: ((Track) -> Unit)? = null,
    val onGoArtist: ((Track) -> Unit)? = null,
    val onRemoveFromList: ((Int) -> Unit)? = null,
    val onMoveUp: ((Int) -> Unit)? = null,
    val onMoveDown: ((Int) -> Unit)? = null,
)

@Composable
fun TrackRow(
    track: Track,
    index: Int,
    playing: Boolean,
    downloadState: Int?,
    actions: TrackActions,
    showArt: Boolean = true,
    onClick: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArt) {
            ArtImage(track.artUri, size = 46.dp)
        } else {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (track.trackNo > 0) track.trackNo.toString() else "·",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                DownloadBadge(downloadState)
                if (downloadState != null) Spacer(Modifier.width(4.dp))
                Text(
                    if (showArt) "${track.artist} • ${track.album}" else track.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            fmtDuration(track.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            TrackMenu(menu, { menu = false }, track, index, downloadState, actions)
        }
    }
}

@Composable
fun TrackMenu(expanded: Boolean, onDismiss: () -> Unit, track: Track, index: Int, downloadState: Int?, a: TrackActions) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        fun item(label: String, icon: ImageVector, action: () -> Unit): @Composable () -> Unit = {
            DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = { onDismiss(); action() })
        }
        a.onPlayNext?.let { f -> item("Play next", Icons.Default.SkipNext) { f(track) }() }
        a.onEnqueue?.let { f -> item("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { f(track) }() }
        a.onAddToPlaylist?.let { f -> item("Add to playlist…", Icons.AutoMirrored.Filled.PlaylistAdd) { f(track) }() }
        if (downloadState == Download.STATE_COMPLETED || downloadState == Download.STATE_DOWNLOADING || downloadState == Download.STATE_QUEUED) {
            a.onRemoveDownload?.let { f -> item("Remove download", Icons.Default.Delete) { f(track) }() }
        } else {
            a.onDownload?.let { f -> item("Download", Icons.Default.Download) { f(track) }() }
        }
        a.onGoAlbum?.let { f -> item("Go to album", Icons.Default.Album) { f(track) }() }
        a.onGoArtist?.let { f -> item("Go to artist", Icons.Default.Person) { f(track) }() }
        a.onMoveUp?.let { f -> item("Move up", Icons.Default.ArrowUpward) { f(index) }() }
        a.onMoveDown?.let { f -> item("Move down", Icons.Default.ArrowDownward) { f(index) }() }
        a.onRemoveFromList?.let { f -> item("Remove from playlist", Icons.Default.Delete) { f(index) }() }
    }
}

@Composable
fun AlbumCard(album: AlbumInfo, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        ArtImage(album.artUri, Modifier.fillMaxWidth().aspectRatio(1f), fallback = Icons.Default.Album)
        Spacer(Modifier.height(6.dp))
        Text(album.album, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            listOfNotNull(album.albumArtist, album.year.takeIf { it > 0 }?.toString()).joinToString(" • "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SimpleRow(
    title: String,
    subtitle: String?,
    art: Any?,
    fallback: ImageVector,
    roundArt: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(art, size = 48.dp, corner = if (roundArt) 24.dp else 6.dp, fallback = fallback)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistInfo>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("New playlist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (playlists.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    LazyColumn(Modifier.height(240.dp)) {
                        items(playlists, key = { it.id }) { p ->
                            Text(
                                "${p.name}  (${p.trackCount})",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(p.id) }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) { Text("Create & add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
