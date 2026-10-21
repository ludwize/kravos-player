package net.kravos.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.kravos.player.App
import net.kravos.player.ui.screens.AlbumScreen
import net.kravos.player.ui.screens.DriveBrowserScreen
import net.kravos.player.ui.screens.RadioScreen
import net.kravos.player.ui.screens.ArtistScreen
import net.kravos.player.ui.screens.FolderPickerScreen
import net.kravos.player.ui.screens.FolderScreen
import net.kravos.player.ui.screens.GenreScreen
import net.kravos.player.ui.screens.LibraryScreen
import net.kravos.player.ui.screens.MiniPlayer
import net.kravos.player.ui.screens.NowPlayingScreen
import net.kravos.player.ui.screens.PlaylistDetailScreen
import net.kravos.player.ui.screens.PlaylistsScreen
import net.kravos.player.ui.screens.PodcastsScreen
import net.kravos.player.ui.screens.SearchScreen
import net.kravos.player.ui.screens.SettingsScreen
import net.kravos.player.ui.screens.SmartScreen
import net.kravos.player.ui.theme.KravosTheme

class MainActivity : AppCompatActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { KravosTheme { Root(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) vm.showPlayer = true
    }

    override fun onStart() {
        super.onStart()
        (application as App).graph.player.connect()
    }

    override fun onStop() {
        (application as App).graph.player.release()
        super.onStop()
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}

@Composable
private fun Root(vm: MainViewModel) {
    val np by vm.player.state.collectAsStateWithLifecycle()
    BackHandler(enabled = vm.showPlayer || vm.stack.size > 1 || (vm.current == Screen.Podcasts && vm.podStack.size > 1)) { vm.back() }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Column {
                    if (np.hasItem) MiniPlayer(vm, np) { vm.showPlayer = true }
                    NavigationBar {
                        NavItem(vm, Screen.Library, "Library") { Icon(Icons.Default.LibraryMusic, null) }
                        NavItem(vm, Screen.Playlists, "Lists") { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) }
                        NavItem(vm, Screen.Radio, "Radio") { Icon(Icons.Default.Radio, null) }
                        NavItem(vm, Screen.Podcasts, "Podcasts") { Icon(Icons.Default.Podcasts, null) }
                        NavItem(vm, Screen.Search, "Search") { Icon(Icons.Default.Search, null) }
                        NavItem(vm, Screen.Settings, "Settings") { Icon(Icons.Default.Settings, null) }
                    }
                }
            },
        ) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
                when (val s = vm.current) {
                    Screen.Library -> LibraryScreen(vm)
                    Screen.Playlists -> PlaylistsScreen(vm)
                    Screen.Search -> SearchScreen(vm)
                    Screen.Settings -> SettingsScreen(vm)
                    Screen.FolderPicker -> FolderPickerScreen(vm)
                    Screen.Radio -> RadioScreen(vm)
                    Screen.Podcasts -> PodcastsScreen(vm)
                    Screen.DriveBrowser -> DriveBrowserScreen(vm)
                    is Screen.Album -> AlbumScreen(vm, s)
                    is Screen.Artist -> ArtistScreen(vm, s)
                    is Screen.Genre -> GenreScreen(vm, s)
                    is Screen.Folder -> FolderScreen(vm, s)
                    is Screen.PlaylistScreen -> PlaylistDetailScreen(vm, s)
                    is Screen.Smart -> SmartScreen(vm, s)
                }
            }
        }
        AnimatedVisibility(
            visible = vm.showPlayer && np.hasItem,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen(vm, np)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavItem(
    vm: MainViewModel,
    screen: Screen,
    label: String,
    icon: @Composable () -> Unit,
) {
    NavigationBarItem(
        selected = vm.root == screen,
        onClick = {
            if (vm.root == screen && vm.stack.size == 1) {
                // Tapping the Podcasts tab again leaves the show you were in and goes to its home.
                if (screen == Screen.Podcasts) vm.podHome()
            } else {
                vm.selectRoot(screen)
            }
        },
        icon = icon,
        label = { Text(label, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Clip) },
    )
}
