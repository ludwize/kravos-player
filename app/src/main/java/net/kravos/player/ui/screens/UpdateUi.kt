package net.kravos.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kravos.player.update.UpdateState
import net.kravos.player.update.Updater

/** Slim bar above the player/tabs when a newer build is on GitHub. */
@Composable
fun UpdateBanner(updater: Updater) {
    val state by updater.state.collectAsStateWithLifecycle()
    var hidden by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    val label = when (val s = state) {
        is UpdateState.Available -> "Version ${s.info.version} is available"
        is UpdateState.Downloading -> "Downloading ${s.info.version}… ${(s.progress * 100).toInt()}%"
        is UpdateState.Ready -> "Version ${s.info.version} is ready to install"
        else -> return
    }
    if (hidden) return
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.SystemUpdate, null)
                Spacer(Modifier.width(10.dp))
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                when (state) {
                    is UpdateState.Available -> Button(onClick = { updater.download() }) { Text("Update") }
                    is UpdateState.Ready -> Button(onClick = { updater.install(ctx) }) { Text("Install") }
                    else -> {}
                }
                IconButton(onClick = { hidden = true }) { Icon(Icons.Default.Close, "Hide") }
            }
            (state as? UpdateState.Downloading)?.let { d ->
                LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }
    }
}

/** "App updates" block in Settings: current version, check button, update/install. */
@Composable
fun UpdateSettings(updater: Updater) {
    val state by updater.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Installed: ${updater.currentVersion}", style = MaterialTheme.typography.bodyLarge)
        val status = when (val s = state) {
            UpdateState.Idle -> null
            UpdateState.Checking -> "Checking…"
            UpdateState.UpToDate -> "You have the latest version."
            is UpdateState.Available -> "Version ${s.info.version} is available."
            is UpdateState.Downloading -> "Downloading ${s.info.version}… ${(s.progress * 100).toInt()}%"
            is UpdateState.Ready -> "Version ${s.info.version} is downloaded."
            is UpdateState.Failed -> s.message
        }
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (state is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        (state as? UpdateState.Downloading)?.let { d ->
            LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        Row(Modifier.padding(top = 8.dp)) {
            when (state) {
                is UpdateState.Available -> Button(onClick = { updater.download() }) { Text("Download update") }
                is UpdateState.Ready -> Button(onClick = { updater.install(ctx) }) { Text("Install") }
                else -> {}
            }
            if (state is UpdateState.Available || state is UpdateState.Ready) Spacer(Modifier.width(8.dp))
            if (state is UpdateState.Available || state is UpdateState.Ready) {
                OutlinedButton(onClick = { updater.check(force = true) }) { Text("Check again") }
            } else {
                FilledTonalButton(onClick = { updater.check(force = true) }, enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading) {
                    Text("Check for updates")
                }
            }
        }
    }
}
