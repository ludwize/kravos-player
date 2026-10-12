package net.kravos.player.ui.screens

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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.exoplayer.offline.Download
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import kotlinx.coroutines.delay
import net.kravos.player.playback.NowPlaying
import net.kravos.player.ui.MainViewModel
import net.kravos.player.ui.Screen
import net.kravos.player.ui.components.ArtImage
import net.kravos.player.ui.components.TextInputDialog
import net.kravos.player.ui.components.fmtDuration
import net.kravos.player.ui.components.fmtPrecise
import kotlin.math.log2
import kotlin.math.roundToInt

@Composable
fun MiniPlayer(vm: MainViewModel, np: NowPlaying, onOpen: () -> Unit) {
    val pos by produceState(0L, np.mediaId, np.isPlaying) {
        while (true) {
            value = vm.player.position()
            delay(500)
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            val frac = if (np.durationMs > 0) (pos.toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtImage(np.artUri, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(np.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        if (np.casting) "Casting • ${np.artist}" else np.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.player.togglePlay() }) {
                    Icon(if (np.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause")
                }
                IconButton(onClick = { vm.player.next() }) { Icon(Icons.Default.SkipNext, "Next") }
            }
        }
    }
}

@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            MediaRouteButton(ctx).apply { runCatching { CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, this) } }
        },
        modifier = modifier.size(48.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(vm: MainViewModel, np: NowPlaying) {
    val loop by vm.graph.extras.loop.collectAsStateWithLifecycle()
    val sleep by vm.graph.extras.sleep.collectAsStateWithLifecycle()
    val dl by vm.downloadStates.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf<Float?>(null) }
    var showQueue by remember { mutableStateOf(false) }
    var showPractice by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    val pos by produceState(0L, np.mediaId) {
        while (true) {
            value = vm.player.position()
            delay(200)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.showPlayer = false }) { Icon(Icons.Default.KeyboardArrowDown, "Close") }
                Text(
                    if (np.casting) "Casting" else "Now playing",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CastButton()
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f, fill = true).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ArtImage(np.artUri, Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f), corner = 12.dp)
            }
            Spacer(Modifier.height(16.dp))
            Text(np.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(
                np.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable {
                    vm.showPlayer = false
                    vm.open(Screen.Artist(np.artist))
                },
            )
            Text(
                listOf(np.album, np.format).filter { it.isNotEmpty() }.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            np.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            // Seek bar
            val dur = np.durationMs.coerceAtLeast(1L)
            val shown = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f)
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { vm.player.seekTo((it * dur).toLong()) }
                    dragging = null
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmtDuration(((dragging ?: shown) * dur).toLong()).ifEmpty { "0:00" }, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                if (loop.active) {
                    Text("A–B ${fmtPrecise(loop.a)} → ${fmtPrecise(loop.b)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                }
                Text(fmtDuration(np.durationMs), style = MaterialTheme.typography.bodySmall)
            }

            // Transport
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.player.toggleShuffle() }) {
                    Icon(Icons.Default.Shuffle, "Shuffle", tint = if (np.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.player.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(36.dp))
                }
                FilledIconButton(
                    onClick = { vm.player.togglePlay() },
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(if (np.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause", Modifier.size(40.dp))
                }
                IconButton(onClick = { vm.player.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipNext, "Next", Modifier.size(36.dp))
                }
                IconButton(onClick = { vm.player.cycleRepeat() }) {
                    Icon(
                        if (np.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Repeat",
                        tint = if (np.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Extras
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                val id = np.mediaId
                val state = id?.let { dl[it] }
                IconButton(onClick = { id?.let { vm.toggleDownloadById(it) } }) {
                    Icon(
                        if (state == Download.STATE_COMPLETED) Icons.Default.DownloadDone else Icons.Default.Download,
                        "Download",
                        tint = if (state == Download.STATE_COMPLETED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = { showSleep = true }) {
                    Icon(Icons.Default.Bedtime, "Sleep timer", tint = if (sleep.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = { showPractice = true }) {
                    val active = loop.active || np.speed != 1f || np.pitch != 1f
                    Icon(Icons.Default.Speed, "Practice", tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue") }
            }
            if (sleep.active) {
                val left by produceState(0L, sleep) {
                    while (true) {
                        value = (sleep.endsAt - System.currentTimeMillis()).coerceAtLeast(0)
                        delay(1000)
                    }
                }
                Text(
                    if (sleep.endOfTrack) "Sleep: after this song" else "Sleep in ${fmtDuration(left).ifEmpty { "0:00" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }

    if (showQueue) QueueSheet(vm) { showQueue = false }
    if (showPractice) PracticeSheet(vm, np) { showPractice = false }
    if (showSleep) SleepDialog(vm) { showSleep = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val queue by vm.player.queue.collectAsStateWithLifecycle()
    val np by vm.player.state.collectAsStateWithLifecycle()
    var save by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { if (np.index in queue.indices) listState.scrollToItem((np.index - 2).coerceAtLeast(0)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue • ${queue.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { save = true }, enabled = queue.isNotEmpty()) { Text("Save as playlist") }
            TextButton(onClick = { vm.player.clearQueue(); onDismiss() }) { Text("Clear") }
        }
        HorizontalDivider()
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(520.dp)) {
            itemsIndexed(queue, key = { i, it -> "$i-${it.mediaId}" }) { i, item ->
                val current = i == np.index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (current) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable { vm.player.skipTo(i) }
                        .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.mediaMetadata.title?.toString() ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            item.mediaMetadata.artist?.toString() ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.player.move(i, i - 1) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Up") }
                    IconButton(onClick = { vm.player.move(i, i + 1) }, enabled = i < queue.size - 1) { Icon(Icons.Default.ArrowDownward, "Down") }
                    IconButton(onClick = { vm.player.removeAt(i) }) { Icon(Icons.Default.Close, "Remove") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (save) TextInputDialog("Save queue as playlist", "", "Save", { save = false }) { vm.saveQueueAsPlaylist(it); save = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PracticeSheet(vm: MainViewModel, np: NowPlaying, onDismiss: () -> Unit) {
    val loop by vm.graph.extras.loop.collectAsStateWithLifecycle()
    var speed by remember(np.speed) { mutableFloatStateOf(np.speed) }
    val semis = (12 * log2(np.pitch.toDouble())).roundToInt()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Practice", style = MaterialTheme.typography.titleMedium)
            if (np.casting) {
                Text("Speed and pitch work only when playing on the phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tempo", Modifier.width(70.dp))
                Slider(
                    value = speed,
                    onValueChange = { speed = (it * 20).roundToInt() / 20f },
                    onValueChangeFinished = { vm.player.setSpeedPitch(speed, semis) },
                    valueRange = 0.5f..1.5f,
                    steps = 19,
                    modifier = Modifier.weight(1f),
                )
                Text("×%.2f".format(speed), Modifier.width(56.dp), textAlign = TextAlign.End)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.5f, 0.75f, 0.85f, 1f).forEach { s ->
                    AssistChip(onClick = { speed = s; vm.player.setSpeedPitch(s, semis) }, label = { Text("×$s") })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pitch", Modifier.width(70.dp))
                OutlinedButton(onClick = { vm.player.setSpeedPitch(speed, (semis - 1).coerceAtLeast(-12)) }) { Text("−") }
                Text(
                    if (semis == 0) "0 st" else "%+d st".format(semis),
                    Modifier.width(72.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(onClick = { vm.player.setSpeedPitch(speed, (semis + 1).coerceAtMost(12)) }) { Text("+") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { speed = 1f; vm.player.setSpeedPitch(1f, 0) }) { Text("Reset") }
            }
            Text(
                "Tip: a song in E♭ (half-step down) → +1 st to play along in standard tuning.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("A–B loop", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            LoopPointRow("A", loop.a, onSet = { vm.setLoopA(vm.player.position()) }) { d -> vm.nudgeLoop((loop.a + d).coerceAtLeast(0), loop.b) }
            LoopPointRow("B", loop.b, onSet = { vm.setLoopB(vm.player.position()) }, enabled = loop.a >= 0) { d -> vm.nudgeLoop(loop.a, loop.b + d) }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { if (loop.a >= 0) vm.player.seekTo(loop.a) }, enabled = loop.a >= 0) { Text("Back to A") }
                OutlinedButton(onClick = { vm.clearLoop() }, enabled = loop.a >= 0) { Text("Clear loop") }
            }
            Text(
                "Loop clears when the song changes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun LoopPointRow(label: String, value: Long, onSet: () -> Unit, enabled: Boolean = true, onNudge: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        FilledTonalButton(onClick = onSet, enabled = enabled, modifier = Modifier.width(96.dp)) { Text("Set $label") }
        Spacer(Modifier.width(12.dp))
        Text(fmtPrecise(value), Modifier.width(72.dp), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { onNudge(-250) }, enabled = value >= 0) { Text("−¼s") }
        TextButton(onClick = { onNudge(250) }, enabled = value >= 0) { Text("+¼s") }
    }
}

@Composable
private fun SleepDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                listOf(10, 15, 30, 45, 60, 90).forEach { m ->
                    Text(
                        "$m minutes",
                        Modifier.fillMaxWidth().clickable { vm.setSleep(m); onDismiss() }.padding(vertical = 10.dp),
                    )
                }
                Text("End of current song", Modifier.fillMaxWidth().clickable { vm.setSleep(null, endOfTrack = true); onDismiss() }.padding(vertical = 10.dp))
                Text("Off", Modifier.fillMaxWidth().clickable { vm.setSleep(null); onDismiss() }.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
