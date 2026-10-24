package net.kravos.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import net.kravos.player.playback.POD_PREFIX
import net.kravos.player.podcast.Episode
import net.kravos.player.ui.MainViewModel
import net.kravos.player.ui.components.ArtImage
import net.kravos.player.ui.components.fmtDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Episode details: what it's about (show notes, with working links), plus play and download. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeNotesSheet(vm: MainViewModel, ep: Episode, onDismiss: () -> Unit) {
    val notes by produceState<String?>(null, ep.key) { value = runCatching { vm.episodeNotes(ep) }.getOrDefault("") }
    val np by vm.player.state.collectAsStateWithLifecycle()
    val dl by vm.downloadStates.collectAsStateWithLifecycle()
    val listen by vm.podcasts.listen.collectAsStateWithLifecycle()
    val dlState = dl[POD_PREFIX + ep.key]
    val current = np.mediaId == POD_PREFIX + ep.key
    val started = listen.inProgress.containsKey(ep.key)
    val linkColor = MaterialTheme.colorScheme.primary
    val rich = remember(notes, linkColor) { notes?.takeIf { it.isNotBlank() }?.let { notesToAnnotated(it, linkColor) } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtImage(ep.art, size = 72.dp, corner = 8.dp, fallback = Icons.Default.Podcasts)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(ep.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            ep.showTitle.ifBlank { null },
                            ep.publishedAt.takeIf { it > 0 }?.let { SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(it)) },
                            fmtDuration(ep.durationMs).ifEmpty { null },
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val pausing = current && np.isPlaying
                Button(onClick = { vm.playEpisode(ep) }) {
                    Icon(if (pausing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (pausing) "Pause" else if (started) "Resume" else "Play")
                }
                if (dlState == null || dlState == Download.STATE_FAILED) {
                    OutlinedButton(onClick = { vm.downloadEpisode(ep) }) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download")
                    }
                } else {
                    OutlinedButton(onClick = { vm.removeEpisodeDownload(ep.key) }) {
                        Icon(if (dlState == Download.STATE_COMPLETED) Icons.Default.DownloadDone else Icons.Default.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (dlState == Download.STATE_COMPLETED) "Remove download" else "Cancel download")
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 460.dp)) {
                when {
                    notes == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    rich == null -> Text(
                        "This episode has no notes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(rich, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

private val URL = Regex("""https?://[^\s<>"]+""")

/** Feed notes are usually HTML; plain-text ones get their line breaks kept and their links made tappable. */
internal fun notesToAnnotated(raw: String, link: Color): AnnotatedString {
    val looksHtml = Regex("""<\s*(p|br|a|ul|li|div|b|strong|i|em)\b""", RegexOption.IGNORE_CASE).containsMatchIn(raw)
    val html = if (looksHtml) {
        raw
    } else {
        val escaped = raw.trim().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        URL.replace(escaped) { m -> "<a href=\"${m.value}\">${m.value}</a>" }.replace("\n", "<br>")
    }
    return AnnotatedString.fromHtml(
        html.trim(),
        linkStyles = TextLinkStyles(style = SpanStyle(color = link, textDecoration = TextDecoration.Underline)),
    )
}
