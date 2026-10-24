package net.kravos.player.ui.screens

import android.text.Html
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.delay
import net.kravos.player.playback.POD_PREFIX
import net.kravos.player.podcast.Episode
import net.kravos.player.podcast.ListenState
import net.kravos.player.podcast.Podcast
import net.kravos.player.podcast.Progress
import net.kravos.player.ui.MainViewModel
import net.kravos.player.ui.PodChart
import net.kravos.player.ui.PodListState
import net.kravos.player.ui.PodScreen
import net.kravos.player.ui.ShowState
import net.kravos.player.ui.components.ArtImage
import net.kravos.player.ui.components.DownloadBadge
import net.kravos.player.ui.components.EmptyState
import net.kravos.player.ui.components.SectionHeader
import net.kravos.player.ui.components.TextInputDialog
import net.kravos.player.ui.components.fmtDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The Podcasts tab. Where you are inside it (a show, the search page) survives switching to other tabs. */
@Composable
fun PodcastsScreen(vm: MainViewModel) {
    val message by vm.podMessage.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.clearPodMessage()
        }
    }
    when (val s = vm.podCurrent) {
        PodScreen.Home -> PodHome(vm)
        PodScreen.Search -> PodSearch(vm)
        is PodScreen.Show -> PodShow(vm, s.podcast)
    }
}

// ---------------------------------------------------------------- home

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PodHome(vm: MainViewModel) {
    val subs by vm.podcasts.subs.collectAsStateWithLifecycle()
    val listen by vm.podcasts.listen.collectAsStateWithLifecycle()
    val shows by vm.shows.collectAsStateWithLifecycle()
    val np by vm.player.state.collectAsStateWithLifecycle()
    val offline by vm.podcastDownloads.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(subs.size) { vm.refreshSubscriptions() }
    val downloaded = remember(offline) { offline.values.sortedByDescending { it.publishedAt } }

    val continueList = remember(listen) { listen.inProgress.values.sortedByDescending { it.updatedAt } }
    val latest = remember(shows, subs, listen) {
        subs.flatMap { s ->
            shows[s.id]?.episodes.orEmpty()
                .filterNot { it.key in listen.played || listen.inProgress.containsKey(it.key) }
                .take(3)
        }.sortedByDescending { it.publishedAt }.take(25)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Podcasts") },
            actions = {
                IconButton(onClick = { vm.refreshSubscriptions(force = true) }) { Icon(Icons.Default.Refresh, "Refresh shows") }
                IconButton(onClick = { vm.podOpenSearch() }) { Icon(Icons.Default.Search, "Find podcasts") }
                IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "Add podcast by RSS address") }
            },
        )
        if (subs.isEmpty() && continueList.isEmpty() && downloaded.isEmpty()) {
            EmptyState("No podcasts yet. Find a show and subscribe, or add one by its RSS address (＋).") {
                Button(onClick = { vm.podOpenSearch() }) { Text("Find podcasts") }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = vm.podListState("home")) {
                if (continueList.isNotEmpty()) {
                    item(key = "h-continue") { SectionHeader("Continue listening") }
                    items(continueList, key = { "c-" + it.key }) { pr ->
                        EpisodeRow(vm, pr.episode, listen, np.mediaId, showName = true)
                    }
                }
                if (latest.isNotEmpty()) {
                    item(key = "h-latest") { SectionHeader("Latest episodes") }
                    items(latest, key = { "l-" + it.key }) { ep -> EpisodeRow(vm, ep, listen, np.mediaId, showName = true) }
                }
                if (downloaded.isNotEmpty()) {
                    item(key = "h-offline") { SectionHeader("Downloaded") }
                    items(downloaded, key = { "d-" + it.key }) { ep -> EpisodeRow(vm, ep, listen, np.mediaId, showName = true) }
                }
                if (subs.isNotEmpty()) {
                    item(key = "h-shows") { SectionHeader("Your shows") }
                    items(subs, key = { "s-" + it.id }) { p ->
                        PodcastRow(p, subscribed = true, onOpen = { vm.podOpenShow(p) }, onToggle = null)
                    }
                }
            }
        }
    }
    if (adding) TextInputDialog("Add podcast by RSS address", "https://", "Add", { adding = false }) {
        adding = false
        vm.podAddByUrl(it)
    }
}

// ---------------------------------------------------------------- search + charts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PodSearch(vm: MainViewModel) {
    val results by vm.podSearchState.collectAsStateWithLifecycle()
    val charts by vm.podCharts.collectAsStateWithLifecycle()
    val subs by vm.podcasts.subs.collectAsStateWithLifecycle()
    val query = vm.podQuery
    val browsing = query.isBlank()

    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(450)
        vm.podSearch(query)
    }
    LaunchedEffect(vm.podChart, browsing) { if (browsing) vm.loadChart(vm.podChart) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Find podcasts") },
            navigationIcon = { IconButton(onClick = { vm.podBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        OutlinedTextField(
            value = query,
            onValueChange = { vm.podQuery = it },
            placeholder = { Text("Search podcasts") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.podQuery = "" }) { Icon(Icons.Default.Clear, "Clear") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.podSearch(query) }),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        if (browsing) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PodChart.entries.forEach { c ->
                    FilterChip(selected = vm.podChart == c, onClick = { vm.podChart = c }, label = { Text("Top • ${c.label}") })
                }
            }
        }
        val state: PodListState = if (browsing) charts[vm.podChart] ?: PodListState(loading = true) else results
        when {
            !browsing && query.trim().length < 2 -> EmptyState("Type at least two letters.")
            state.loading && state.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null && state.items.isEmpty() -> EmptyState(state.error) {
                Button(onClick = { if (browsing) vm.loadChart(vm.podChart, force = true) else vm.podSearch(query) }) { Text("Retry") }
            }
            state.items.isEmpty() -> EmptyState("Nothing found.")
            else -> LazyColumn(Modifier.fillMaxSize(), state = vm.podListState(if (browsing) "chart-${vm.podChart.name}" else "search")) {
                items(state.items, key = { it.id }) { p ->
                    PodcastRow(
                        p,
                        subscribed = subs.any { it.id == p.id },
                        onOpen = { vm.podOpenShow(p) },
                        onToggle = { vm.podcasts.toggleSubscription(p) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PodcastRow(p: Podcast, subscribed: Boolean, onOpen: () -> Unit, onToggle: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Open show" }
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(p.art, size = 56.dp, fallback = Icons.Default.Podcasts)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (p.author.isNotBlank()) {
                Text(p.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onToggle != null) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (subscribed) Icons.Default.LibraryAddCheck else Icons.Default.LibraryAdd,
                    if (subscribed) "Unsubscribe" else "Subscribe",
                    tint = if (subscribed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- one show

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PodShow(vm: MainViewModel, initial: Podcast) {
    val shows by vm.shows.collectAsStateWithLifecycle()
    val subs by vm.podcasts.subs.collectAsStateWithLifecycle()
    val listen by vm.podcasts.listen.collectAsStateWithLifecycle()
    val np by vm.player.state.collectAsStateWithLifecycle()
    val state: ShowState = shows[initial.id] ?: ShowState(initial, loading = true)
    val p = state.podcast
    val subscribed = subs.any { it.id == p.id }
    var oldestFirst by remember(p.id) { mutableStateOf(false) }
    var hidePlayed by remember(p.id) { mutableStateOf(false) }

    LaunchedEffect(initial.id) { vm.loadShow(initial) }

    val episodes = remember(state.episodes, oldestFirst, hidePlayed, listen.played) {
        val base = if (oldestFirst) state.episodes.reversed() else state.episodes
        if (hidePlayed) base.filterNot { it.key in listen.played } else base
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { vm.podBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { vm.loadShow(p, force = true) }) { Icon(Icons.Default.Refresh, "Reload feed") } },
        )
        LazyColumn(Modifier.fillMaxSize(), state = vm.podListState("show-${p.id}")) {
            item(key = "header") {
                ShowHeader(
                    p = p,
                    subscribed = subscribed,
                    oldestFirst = oldestFirst,
                    hidePlayed = hidePlayed,
                    onSubscribe = { vm.podcasts.toggleSubscription(p) },
                    onSort = { oldestFirst = !oldestFirst },
                    onHidePlayed = { hidePlayed = !hidePlayed },
                )
            }
            if (state.offline) {
                item(key = "offline") {
                    Text(
                        "Can't reach the feed right now — showing saved episodes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (state.loading && state.episodes.isEmpty()) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
            if (state.error != null && state.episodes.isEmpty()) {
                item(key = "error") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.loadShow(p, force = true) }) { Text("Retry") }
                    }
                }
            }
            items(episodes, key = { it.key }) { ep -> EpisodeRow(vm, ep, listen, np.mediaId, showName = false) }
        }
    }
}

@Composable
private fun ShowHeader(
    p: Podcast,
    subscribed: Boolean,
    oldestFirst: Boolean,
    hidePlayed: Boolean,
    onSubscribe: () -> Unit,
    onSort: () -> Unit,
    onHidePlayed: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val about = remember(p.description) { plainText(p.description) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtImage(p.art, size = 96.dp, corner = 10.dp, fallback = Icons.Default.Podcasts)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (p.author.isNotBlank()) {
                    Text(p.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                if (subscribed) {
                    FilledTonalButton(onClick = onSubscribe) {
                        Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Subscribed")
                    }
                } else {
                    Button(onClick = onSubscribe) { Text("Subscribe") }
                }
            }
        }
        if (about.isNotBlank()) {
            Text(
                about,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp).clickable { expanded = !expanded },
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = oldestFirst, onClick = onSort, label = { Text(if (oldestFirst) "Oldest first" else "Newest first") })
            FilterChip(selected = hidePlayed, onClick = onHidePlayed, label = { Text("Hide played") })
        }
    }
}

// ---------------------------------------------------------------- episode row

@Composable
private fun EpisodeRow(vm: MainViewModel, ep: Episode, listen: ListenState, playingId: String?, showName: Boolean) {
    var menu by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(false) }
    val progress: Progress? = listen.inProgress[ep.key]
    val played = ep.key in listen.played
    val current = playingId == POD_PREFIX + ep.key
    val dl by vm.downloadStates.collectAsStateWithLifecycle()
    val dlState = dl[POD_PREFIX + ep.key]

    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Play episode" }
            .clickable { vm.playEpisode(ep) }
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tapping the picture opens the episode details (notes); tapping the row plays it.
        ArtImage(
            ep.art,
            Modifier.semantics { contentDescription = "Episode details" }.clickable { notes = true },
            size = 52.dp,
            fallback = Icons.Default.Podcasts,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ep.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    current -> MaterialTheme.colorScheme.primary
                    played -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                DownloadBadge(dlState)
                if (dlState != null) Spacer(Modifier.width(4.dp))
                Text(
                    episodeSubtitle(ep, progress, played, showName),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Play next") },
                    leadingIcon = { Icon(Icons.Default.SkipNext, null) },
                    onClick = { menu = false; vm.playEpisodeNext(ep) },
                )
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                    onClick = { menu = false; vm.enqueueEpisode(ep) },
                )
                if (dlState == null || dlState == Download.STATE_FAILED) {
                    DropdownMenuItem(
                        text = { Text("Download") },
                        leadingIcon = { Icon(Icons.Default.Download, null) },
                        onClick = { menu = false; vm.downloadEpisode(ep) },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(if (dlState == Download.STATE_COMPLETED) "Remove download" else "Cancel download") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; vm.removeEpisodeDownload(ep.key) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (played) "Mark as not played" else "Mark as played") },
                    leadingIcon = { Icon(Icons.Default.Check, null) },
                    onClick = { menu = false; vm.setEpisodePlayed(ep.key, !played) },
                )
                DropdownMenuItem(
                    text = { Text("Episode details") },
                    leadingIcon = { Icon(Icons.Default.Info, null) },
                    onClick = { menu = false; notes = true },
                )
            }
        }
    }
    if (notes) EpisodeNotesSheet(vm, ep) { notes = false }
}

private fun episodeSubtitle(ep: Episode, progress: Progress?, played: Boolean, showName: Boolean): String {
    val parts = ArrayList<String>()
    if (showName && ep.showTitle.isNotBlank()) parts += ep.showTitle
    if (ep.publishedAt > 0) parts += SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ep.publishedAt))
    when {
        played -> parts += "Played"
        progress != null && progress.durMs > 0 -> parts += "${fmtLeft(progress.durMs - progress.posMs)} left"
        ep.durationMs > 0 -> parts += fmtLeft(ep.durationMs)
    }
    return parts.joinToString(" • ")
}

private fun fmtLeft(ms: Long): String {
    val min = (ms / 60_000L).coerceAtLeast(0)
    return when {
        min >= 60 -> "${min / 60} h ${min % 60} min"
        min >= 1 -> "$min min"
        else -> fmtDuration(ms).ifEmpty { "<1 min" }
    }
}

/** Feed descriptions are HTML; show them as plain text. */
private fun plainText(html: String): String =
    if (html.isBlank()) "" else Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim()
