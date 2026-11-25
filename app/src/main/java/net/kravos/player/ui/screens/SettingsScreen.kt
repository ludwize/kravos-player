package net.kravos.player.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.common.api.ApiException
import net.kravos.player.BuildConfig
import net.kravos.player.tags.LegacyCharset
import net.kravos.player.ui.MainViewModel
import net.kravos.player.ui.Screen
import net.kravos.player.ui.components.EmptyState
import net.kravos.player.ui.components.SectionHeader
import net.kravos.player.ui.components.SimpleRow
import net.kravos.player.ui.components.fmtBytes
import java.text.DateFormat
import java.util.Date

/** Returns a function that starts the Google Drive consent flow. */
@Composable
fun rememberDriveConnector(vm: MainViewModel): () -> Unit {
    val auth = vm.graph.auth
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            runCatching { auth.authorizationResultFromIntent(res.data) }
                .onSuccess { vm.onAuthorized(it) }
                .onFailure { auth.onError(describeAuthError(it)) }
        } else {
            auth.onError("Authorization was cancelled")
        }
    }
    return remember(launcher) {
        val start: () -> Unit = {
            auth.authorize()
                .addOnSuccessListener { r ->
                    val pi = r.pendingIntent
                    if (r.hasResolution() && pi != null) {
                        launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    } else {
                        vm.onAuthorized(r)
                    }
                }
                .addOnFailureListener { e -> auth.onError(describeAuthError(e)) }
            Unit
        }
        start
    }
}

private fun describeAuthError(e: Throwable): String {
    val code = (e as? ApiException)?.statusCode
    return when (code) {
        10 -> "Google rejected the app (DEVELOPER_ERROR). Check that the Android OAuth client in Google Cloud uses package net.kravos.player and the release SHA-1."
        7 -> "No network connection."
        16 -> "Authorization was cancelled."
        else -> "Authorization failed: ${e.message ?: e.javaClass.simpleName}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val g = vm.graph
    val connected by g.prefs.connected.collectAsStateWithLifecycle()
    val account by g.prefs.account.collectAsStateWithLifecycle()
    val needsReauth by g.auth.needsReauth.collectAsStateWithLifecycle()
    val authError by g.auth.lastError.collectAsStateWithLifecycle()
    val roots by g.prefs.roots.collectAsStateWithLifecycle()
    val scan by g.scanner.state.collectAsStateWithLifecycle()
    val lastScan by g.prefs.lastScanAt.collectAsStateWithLifecycle()
    val count by vm.trackCount.collectAsStateWithLifecycle()
    val wifiOnly by g.prefs.wifiOnlyDownloads.collectAsStateWithLifecycle()
    val offlineBytes by g.downloads.bytes.collectAsStateWithLifecycle()
    val cacheMb by g.prefs.streamCacheMb.collectAsStateWithLifecycle()
    val legacy by g.prefs.legacyCharset.collectAsStateWithLifecycle()
    val connect = rememberDriveConnector(vm)
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmRemoveAll by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionHeader("App updates")
            UpdateSettings(g.updater)
            SectionHeader("Google Drive")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cloud, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (connected) (account ?: "Connected") else "Not connected", style = MaterialTheme.typography.bodyLarge)
                    if (needsReauth) Text("Google needs you to confirm access again.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (!connected || needsReauth) {
                    Button(onClick = connect) { Text(if (connected) "Reconnect" else "Connect") }
                } else {
                    OutlinedButton(onClick = { confirmDisconnect = true }) { Text("Disconnect") }
                }
            }
            authError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
            }

            SectionHeader("Music folders")
            roots.forEach { r ->
                SimpleRow(r.name, null, null, Icons.Default.Folder, trailing = {
                    IconButton(onClick = { vm.removeRoot(r.id) }) { Icon(Icons.Default.Close, "Remove") }
                }) {}
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.pickerOpen(emptyList()); vm.open(Screen.FolderPicker) }, enabled = connected) { Text("Add folder") }
            }

            SectionHeader("Library")
            Text(
                "$count songs" + if (lastScan > 0) " • last scan ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastScan))}" else "",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (scan.running) {
                val p = if (scan.total > 0) scan.done.toFloat() / scan.total else 0f
                if (scan.total > 0) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp))
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp))
                Text(scan.phase + if (scan.total > 0) " ${scan.done}/${scan.total}" else "", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            } else {
                scan.lastResult?.let { Text("Last scan: $it", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                scan.error?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (scan.running) {
                    OutlinedButton(onClick = { g.scanner.cancel() }) { Text("Stop") }
                } else {
                    Button(onClick = { g.scanner.start() }, enabled = connected && roots.isNotEmpty()) { Text("Scan for changes") }
                    OutlinedButton(onClick = { g.scanner.start(full = true) }, enabled = connected && roots.isNotEmpty()) { Text("Full rescan") }
                }
            }
            Text(
                "Old tags (ID3v1 / Latin-1) encoding",
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            listOf(
                LegacyCharset.AUTO to "Auto (detect Č/Ć/Š/Ž/Đ from Windows-1250)",
                LegacyCharset.WINDOWS_1250 to "Always Windows-1250",
                LegacyCharset.LATIN_1 to "Always Latin-1",
            ).forEach { (v, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { g.prefs.setLegacyCharset(v) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = legacy == v, onClick = { g.prefs.setLegacyCharset(v) })
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                "Changing this needs a Full rescan.",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader("Offline")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Download on Wi-Fi only")
                    Text("Downloads pause on mobile data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = wifiOnly, onCheckedChange = { g.prefs.setWifiOnly(it); g.downloads.setWifiOnly(it) })
            }
            Text("Offline storage: ${fmtBytes(offlineBytes)}", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                OutlinedButton(onClick = { confirmRemoveAll = true }) { Text("Remove all downloads") }
            }

            SectionHeader("Streaming cache")
            Text(
                "Recently played songs are kept so replays don't hit Drive again. Size change applies after the app restarts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            listOf(512, 1024, 2048, 4096, 8192).forEach { mb ->
                Row(
                    Modifier.fillMaxWidth().clickable { g.prefs.setStreamCacheMb(mb) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = cacheMb == mb, onClick = { g.prefs.setStreamCacheMb(mb) })
                    Text(if (mb >= 1024) "${mb / 1024} GB" else "$mb MB")
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                OutlinedButton(onClick = { vm.clearStreamCache() }) { Text("Clear streaming cache") }
            }

            HorizontalDivider(Modifier.padding(top = 16.dp))
            Text(
                "Kravos Player ${BuildConfig.VERSION_NAME}\nPersonal build • talks only to Google Drive (read-only)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect Google Drive?") },
            text = { Text("Revokes this app's access. Your library index and downloads stay on the phone.") },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; vm.disconnect() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            title = { Text("Remove all downloads?") },
            text = { Text("Frees ${fmtBytes(offlineBytes)}. Songs stay available for streaming.") },
            confirmButton = { TextButton(onClick = { confirmRemoveAll = false; g.downloads.removeAll() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemoveAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun FolderPickerScreen(vm: MainViewModel) {
    val st by vm.picker.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (st.path.isEmpty() && st.items.isEmpty()) vm.pickerOpen(emptyList()) }
    BackHandler(enabled = st.path.isNotEmpty()) { vm.pickerBack() }
    val title = st.path.lastOrNull()?.second ?: "Choose music folder"
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(vm, title)
        if (st.path.isNotEmpty() && st.path.last().first != MainViewModel.SHARED) {
            val (id, name) = st.path.last()
            Button(
                onClick = { vm.addRoot(id, name); vm.back() },
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
            ) { Text("Use “$name”") }
        }
        when {
            st.path.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                item { SimpleRow("My Drive", "Browse your folders", null, Icons.Default.Folder) { vm.pickerOpen(listOf("root" to "My Drive")) } }
                item {
                    SimpleRow("Shared with me", "Folders others shared with you", null, Icons.Default.FolderShared) {
                        vm.pickerOpen(listOf(MainViewModel.SHARED to "Shared with me"))
                    }
                }
            }
            st.loading -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
            st.error != null -> EmptyState(st.error ?: "")
            st.items.isEmpty() -> EmptyState("No subfolders here")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(st.items, key = { it.id }) { f ->
                    SimpleRow(f.name, null, null, Icons.Default.Folder) { vm.pickerOpen(st.path + (f.id to f.name)) }
                }
            }
        }
    }
}
