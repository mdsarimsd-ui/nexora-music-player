package com.lastwave.app.ui.ai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.ai.AiPlaylistSuggestion

/**
 * The AI assistant surface, redesigned as a chat: full-width assistant
 * replies, a composer pinned at the bottom, dismissible suggestion chips,
 * a typing indicator, and a masked API-key setup card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantSheet(
    onDismiss: () -> Unit,
    viewModel: AiAssistantViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val settingsReady by viewModel.settingsReady.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    var keyEditorOpen by remember { mutableStateOf<Boolean?>(null) }
    var keyInput by remember { mutableStateOf("") }
    var modelInput by remember { mutableStateOf("") }
    var requestInput by remember { mutableStateOf("") }
    var chipsVisible by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    var voiceOn by remember { mutableStateOf(true) }
    var listening by remember { mutableStateOf(false) }

    // Spoken answers: every assistant reply is read out, Assistant-style.
    val tts = remember { runCatching { TextToSpeech(context) { } }.getOrNull() }
    androidx.compose.runtime.DisposableEffect(tts) {
        onDispose { tts?.shutdown() }
    }
    LaunchedEffect(chat, voiceOn) {
        val last = chat.lastOrNull()
        if (voiceOn && tts != null && last != null && !last.fromUser && !last.isError && last.playlist == null) {
            runCatching {
                tts.speak(last.text.take(600), TextToSpeech.QUEUE_FLUSH, null, "nexora-ai")
            }
        }
    }

    // Speech in: the composer fills itself from what you say, then sends.
    val speech = remember {
        runCatching {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else {
                null
            }
        }.getOrNull()
    }
    androidx.compose.runtime.DisposableEffect(speech) {
        onDispose { speech?.destroy() }
    }
    fun startListening() {
        val recognizer = speech ?: return
        listening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    chipsVisible = false
                    if (text.contains("playlist", ignoreCase = true) || text.contains("mix", ignoreCase = true)) {
                        viewModel.buildPlaylist(text)
                    } else {
                        viewModel.ask(text)
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.let { requestInput = it }
            }
            override fun onError(error: Int) {
                listening = false
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(intent)
    }

    // Decide fresh-vs-saved only once the real saved settings have landed,
    // never from the placeholder defaults.
    LaunchedEffect(settingsReady) {
        if (settingsReady && keyEditorOpen == null) {
            keyEditorOpen = settings.apiKey.isBlank()
        }
    }

    fun send() {
        val text = requestInput.trim()
        if (text.isBlank() || busy) return
        chipsVisible = false
        requestInput = ""
        if (text.contains("playlist", ignoreCase = true) || text.contains("mix", ignoreCase = true)) {
            viewModel.buildPlaylist(text)
        } else {
            viewModel.ask(text)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("Ask AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { voiceOn = !voiceOn }) {
                    Icon(
                        if (voiceOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        contentDescription = "Spoken answers",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    if (Settings.canDrawOverlays(context)) {
                        if (AiOverlayService.running) {
                            AiOverlayService.stop(context)
                        } else {
                            AiOverlayService.start(context)
                        }
                    } else {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + context.packageName),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }) {
                    Icon(
                        Icons.Filled.PictureInPictureAlt,
                        contentDescription = "Floating AI bubble",
                        tint = if (AiOverlayService.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!settingsReady) {
                Spacer(Modifier.height(24.dp))
            } else {
                // Setup stays one line away: masked key, never readable again.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AI Assistant", modifier = Modifier.weight(1f))
                    Switch(checked = settings.enabled, onCheckedChange = { viewModel.setEnabled(it) })
                }
                val editing = keyEditorOpen ?: settings.apiKey.isBlank()
                if (editing) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Add your AI key", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Stored only on this device. Any key works: Google (aistudio.google.com/apikey), OpenAI, Groq or OpenRouter - the app detects which one you paste.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = keyInput,
                                onValueChange = {
                                    keyInput = it
                                    viewModel.setApiKey(it.trim())
                                },
                                label = { Text("API key") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = modelInput,
                                onValueChange = {
                                    modelInput = it
                                    viewModel.setModel(it.trim())
                                },
                                label = { Text("Model (leave as is unless you know)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row {
                                TextButton(onClick = { viewModel.ask("Reply with exactly: OK") }) {
                                    Text("Test connection")
                                }
                                Spacer(Modifier.weight(1f))
                                if (settings.apiKey.isNotBlank()) {
                                    TextButton(onClick = { keyEditorOpen = false }) { Text("Done") }
                                }
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Key saved \u2022\u2022\u2022\u2022${settings.apiKey.takeLast(4)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            keyEditorOpen = true
                            keyInput = ""
                        }) { Text("Change key") }
                    }
                }

                // Transcript: nothing until the user speaks; then a chat.
                if (chat.isEmpty() && !busy) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "What do you want to hear?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "I build playlists from your library, answer questions about your music, and figure out why playback acts up.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        chat.forEach { msg -> ChatMessageRow(msg, busy, viewModel::savePlaylist) }
                        if (busy) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    "Thinking\u2026",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    LaunchedEffect(chat.size, busy) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                }

                if (chipsVisible) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            "Build me a workout playlist",
                            "Calm evening mix",
                            "Why did songs skip?",
                        ).forEach { chip ->
                            SuggestionChip(
                                onClick = {
                                    chipsVisible = false
                                    if (chip.contains("skip")) {
                                        viewModel.diagnose()
                                    } else {
                                        viewModel.buildPlaylist(chip)
                                    }
                                },
                                label = { Text(chip) },
                            )
                        }
                    }
                }

                if (busy || listening) {
                    val orbTransition = rememberInfiniteTransition(label = "ai_orb")
                    val orbScale by orbTransition.animateFloat(
                        initialValue = 0.85f,
                        targetValue = 1.15f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(650, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "orb_scale",
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .scale(orbScale)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary,
                                        ),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (listening) "listening" else "thinking",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = requestInput,
                        onValueChange = { requestInput = it },
                        placeholder = { Text("Ask anything: a mood, a vibe, a playlist idea") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            if (listening) {
                                listening = false
                                speech?.stopListening()
                            } else {
                                startListening()
                            }
                        },
                        enabled = !busy || listening,
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = if (listening) "Stop listening" else "Ask by voice",
                            tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { send() }, enabled = !busy && requestInput.isNotBlank()) {
                        Icon(Icons.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageRow(
    msg: AiChatMessage,
    busy: Boolean,
    onSave: (AiPlaylistSuggestion) -> Unit,
) {
    if (msg.fromUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (msg.isError) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                msg.playlist?.let { pl ->
                    HorizontalDivider()
                    Text(pl.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (pl.subtitle.isNotBlank()) {
                        Text(
                            pl.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    pl.tracks.forEach { track ->
                        Text(
                            "${track.name} - ${track.artist}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = { onSave(pl) },
                        enabled = !busy,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Save playlist") }
                }
            }
        }
    }
}
