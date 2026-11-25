package net.kravos.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kravos.player.ui.MainViewModel
import net.kravos.player.ui.Screen
import net.kravos.player.ui.components.EmptyState
import net.kravos.player.ui.components.SectionHeader
import net.kravos.player.ui.components.SimpleRow
import net.kravos.player.ui.components.fmtBytes

/** Walk the whole Google Drive and play any file or folder, scanned or not. */
@Composable
fun DriveBrowserScreen(vm: MainViewModel) {
    val b by vm.browser.collectAsStateWithLifecycle()
    val connected by vm.graph.prefs.connected.collectAsStateWithLifecycle()
    val roots by vm.graph.prefs.roots.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf(b.searchQuery ?: "") }
    LaunchedEffect(Unit) { if (b.path.isEmpty() && b.searchQuery == null && b.folders.isEmpty()) vm.browserOpen(emptyList()) }
    BackHandler(enabled = b.path.isNotEmpty() || b.searchQuery != null) {
        if (b.searchQuery != null) query = ""
        vm.browserBack()
    }

    val title = b.searchQuery?.let { "“$it”" } ?: b.path.lastOrNull()?.second ?: "Google Drive"
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(vm, title)
        if (!connected) {
            EmptyState("Connect Google Drive first (Settings).") {
                Button(onClick = { vm.selectRoot(Screen.Settings) }) { Text("Open Settings") }
            }
            return@Column
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search Drive by file or folder name") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        query = ""
                        if (b.searchQuery != null) vm.browserBack()
                    }) { Icon(Icons.Default.Clear, "Clear") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.browserSearch(query) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        b.busy?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        val atTop = b.path.isEmpty() && b.searchQuery == null
        val folderId = b.path.lastOrNull()?.first
        val realFolder = b.searchQuery == null && folderId != null && folderId != MainViewModel.SHARED
        when {
            atTop -> LazyColumn(Modifier.fillMaxSize(), state = vm.browserListState(b)) {
                item { SimpleRow("My Drive", "All your folders", null, Icons.Default.Folder) { vm.browserOpen(listOf("root" to "My Drive")) } }
                item {
                    SimpleRow("Shared with me", "Folders others shared with you", null, Icons.Default.FolderShared) {
                        vm.browserOpen(listOf(MainViewModel.SHARED to "Shared with me"))
                    }
                }
            }
            b.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            b.error != null && b.folders.isEmpty() && b.files.isEmpty() -> EmptyState(b.error ?: "")
            else -> LazyColumn(Modifier.fillMaxSize(), state = vm.browserListState(b)) {
                if (realFolder && folderId != null) {
                    item {
                        val name = b.path.last().second
                        val inLibrary = roots.any { it.id == folderId }
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(onClick = { vm.playDriveFolder(folderId, name, shuffle = false) }, enabled = b.busy == null) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Play all")
                            }
                            FilledTonalButton(onClick = { vm.playDriveFolder(folderId, name, shuffle = true) }, enabled = b.busy == null) {
                                Icon(Icons.Default.Shuffle, null)
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { if (!inLibrary) vm.addRoot(folderId, name) }) {
                                Icon(
                                    if (inLibrary) Icons.Default.LibraryAddCheck else Icons.Default.LibraryAdd,
                                    if (inLibrary) "In library" else "Add folder to library",
                                    tint = if (inLibrary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        Text(
                            "“Play all” includes subfolders. Nothing needs to be scanned first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                if (b.folders.isNotEmpty()) {
                    if (b.searchQuery != null) item { SectionHeader("Folders") }
                    items(b.folders, key = { "d" + it.id }) { f ->
                        SimpleRow(
                            f.name,
                            null,
                            null,
                            Icons.Default.Folder,
                            trailing = {
                                IconButton(onClick = { vm.playDriveFolder(f.id, f.name, shuffle = false) }, enabled = b.busy == null) {
                                    Icon(Icons.Default.PlayArrow, "Play folder")
                                }
                            },
                        ) {
                            val newPath = if (b.searchQuery != null) listOf(f.id to f.name) else b.path + (f.id to f.name)
                            query = ""
                            vm.browserOpen(newPath)
                        }
                    }
                }
                if (b.files.isNotEmpty()) {
                    if (b.searchQuery != null) item { SectionHeader("Songs") }
                    itemsIndexed(b.files, key = { _, f -> "f" + f.id }) { i, f ->
                        SimpleRow(
                            f.name.substringBeforeLast('.'),
                            listOf(f.extension.uppercase(), if (f.size > 0) fmtBytes(f.size) else "").filter { it.isNotEmpty() }.joinToString(" • "),
                            null,
                            Icons.Default.MusicNote,
                        ) { vm.playDriveFiles(b.files, i) }
                    }
                }
                if (b.folders.isEmpty() && b.files.isEmpty()) item { EmptyState("No folders or MP3/FLAC files here") }
            }
        }
    }
}
