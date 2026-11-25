package com.lastwave.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import com.lastwave.app.playback.toPlayableTrack
import com.lastwave.app.ui.playlist.PlaylistViewModel
import com.lastwave.app.ui.shell.FloatingNavDefaults

/**
 * The bottom-nav Queue tab: the live current playlist with the same drag
 * reorder and swipe-to-remove as the in-player queue panel, plus a header
 * naming what is playing and a switcher that jumps straight into any saved
 * playlist.
 */
@Composable
internal fun QueueScreen(
    modifier: Modifier = Modifier,
) {
    val player = LocalMusicPlayer.current
    val state by player.state.collectAsStateWithLifecycle()
    val playlistViewModel: PlaylistViewModel = hiltViewModel()
    LaunchedEffect(Unit) { playlistViewModel.load() }
    val playlistUi by playlistViewModel.uiState.collectAsStateWithLifecycle()
    var switcherOpen by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Now playing from",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = state.sourceLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { switcherOpen = true }) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Switch playlist")
            }
        }
        QueuePanel(
            state,
            player,
            bottomPadding = FloatingNavDefaults.contentBottomPadding(),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        )
    }

    if (switcherOpen) {
        ModalBottomSheet(onDismissRequest = { switcherOpen = false }) {
            Text(
                text = "Switch playlist",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth()) {
                items(playlistUi.playlists, key = { it.id }) { playlist ->
                    val trackCount = playlist.tracks.size
                    ListItem(
                        headlineContent = {
                            Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(if (trackCount == 1) "1 song" else "$trackCount songs")
                        },
                        modifier = Modifier.clickable {
                            if (trackCount > 0) {
                                player.playQueue(
                                    tracks = playlist.tracks.map(GeneratedTrack::toPlayableTrack),
                                    startIndex = 0,
                                    sourceLabel = playlist.title,
                                )
                            }
                            switcherOpen = false
                        },
                    )
                }
            }
        }
    }
}
