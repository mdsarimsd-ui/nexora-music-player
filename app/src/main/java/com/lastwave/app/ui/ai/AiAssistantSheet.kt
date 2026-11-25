package com.lastwave.app.ui.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The AI assistant surface: setup on first use, then quick actions
 * (mood playlists, free questions, playback diagnosis) with results
 * applied straight into the app's own data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantSheet(
    onDismiss: () -> Unit,
    viewModel: AiAssistantViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var keyInput by remember { mutableStateOf(settings.apiKey) }
    var modelInput by remember { mutableStateOf(settings.model) }
    var requestInput by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text("AI Assistant", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Uses your own Gemini API key, stored only on this device. Get a free key at aistudio.google.com/apikey.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enable AI Assistant", modifier = Modifier.weight(1f))
                Switch(checked = settings.enabled, onCheckedChange = { viewModel.setEnabled(it) })
            }

            OutlinedTextField(
                value = keyInput,
                onValueChange = {
                    keyInput = it
                    viewModel.setApiKey(it)
                },
                label = { Text("Gemini API key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = modelInput,
                onValueChange = {
                    modelInput = it
                    viewModel.setModel(it)
                },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = {
                viewModel.ask("Reply with exactly: OK")
            }) { Text("Test connection") }

            if (settings.isUsable) {
                HorizontalDivider()

                Text("What should the AI do?", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("Build me a playlist", "Why did songs skip?").forEach { chip ->
                        TextButton(onClick = {
                            when (chip) {
                                "Build me a playlist" -> viewModel.buildPlaylist(
                                    requestInput.ifBlank { "Surprise me with a playlist for right now" },
                                )
                                else -> viewModel.diagnose()
                            }
                        }) { Text(chip) }
                    }
                }
                OutlinedTextField(
                    value = requestInput,
                    onValueChange = { requestInput = it },
                    label = { Text("Ask anything: a mood, a vibe, a playlist idea") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (requestInput.contains("playlist", ignoreCase = true)) {
                            viewModel.buildPlaylist(requestInput)
                        } else {
                            viewModel.ask(requestInput)
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Send") }
            }

            when (val state = ui) {
                is AiUiState.Working -> CircularProgressIndicator(
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.CenterHorizontally),
                )
                is AiUiState.Answer -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider()
                    Text(state.text, style = MaterialTheme.typography.bodyMedium)
                }
                is AiUiState.Playlist -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider()
                    Text(state.suggestion.name, style = MaterialTheme.typography.titleMedium)
                    if (state.suggestion.subtitle.isNotBlank()) {
                        Text(state.suggestion.subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.suggestion.note.isNotBlank()) {
                        Text(state.suggestion.note, style = MaterialTheme.typography.bodySmall)
                    }
                    state.suggestion.tracks.forEach { track ->
                        Text(
                            "${track.name} - ${track.artist}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { viewModel.savePlaylist(state.suggestion) },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Save playlist") }
                }
                is AiUiState.Saved -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider()
                    Text(
                        "Saved \"${state.name}\" to your playlists.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is AiUiState.Error -> Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                is AiUiState.Idle -> Unit
            }
        }
    }
}
