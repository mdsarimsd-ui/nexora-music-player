package com.lastwave.app.data.ai

import android.util.Log
import com.lastwave.app.data.generate.GeneratedTrack
import com.lastwave.app.data.local.AiPreferences
import com.lastwave.app.data.local.AiSettings
import com.lastwave.app.data.local.db.DownloadedTrackDao
import com.lastwave.app.data.local.db.DownloadedTrackEntity
import com.lastwave.app.data.local.db.SongPlayStatsDao
import com.lastwave.app.data.local.db.SongPlayStatsEntity
import com.lastwave.app.data.playlist.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** A playlist the AI built from the listener's own library snapshot. */
data class AiPlaylistSuggestion(
    val name: String,
    val subtitle: String,
    val note: String,
    val tracks: List<GeneratedTrack>,
)

@Serializable
private data class AiSuggestionDto(
    val name: String = "",
    val subtitle: String = "",
    val note: String = "",
    val tracks: List<AiTrackDto> = emptyList(),
)

@Serializable
private data class AiTrackDto(
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
)

/**
 * The app's built-in AI assistant. Grounded on the user's own data: play
 * stats, downloaded songs and saved playlists are summarized into a compact
 * snapshot, the user's own AI provider fills in the intelligence, and the
 * result is applied through the app's normal repositories. Playback logs are
 * summarized for the diagnosis mode. Everything runs only when the user has
 * enabled the assistant and pasted their own API key.
 */
@Singleton
class AiAssistantRepository @Inject constructor(
    private val aiPreferences: AiPreferences,
    private val geminiClient: GeminiClient,
    private val songPlayStatsDao: SongPlayStatsDao,
    private val downloadedTrackDao: DownloadedTrackDao,
    private val playlistRepository: PlaylistRepository,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Free-form ask: "what should I listen to tonight?" and similar. */
    suspend fun ask(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { usableSettings() }.mapCatching {
            geminiClient.generate(
                it.apiKey,
                it.model,
                ASSISTANT_SYSTEM_PROMPT,
                prompt,
            ).getOrThrow()
        }
    }

    /** Builds a playlist from the library snapshot for a mood-style request. */
    suspend fun suggestPlaylist(userPrompt: String): Result<AiPlaylistSuggestion> =
        withContext(Dispatchers.IO) {
            runCatching { usableSettings() }.mapCatching { settings ->
                val snapshot = buildLibrarySnapshot()
                val prompt = buildString {
                    appendLine("Listener's music snapshot:")
                    appendLine(snapshot)
                    appendLine()
                    append("Request: ").append(userPrompt)
                }
                val answer = geminiClient.generate(
                    settings.apiKey,
                    settings.model,
                    PLAYLIST_SYSTEM_PROMPT,
                    prompt,
                ).getOrThrow()
                parseSuggestion(answer)
            }
        }

    /** Summarizes recent warning/error logs so playback problems make sense. */
    suspend fun diagnosePlayback(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { usableSettings() }.mapCatching { settings ->
            val prompt = buildString {
                appendLine("These are the last warning/error log lines from the listener's music player app.")
                appendLine("Explain in plain language what is going wrong, list likely causes, and suggest what to try, as short bullet points.")
                appendLine()
                append(recentLogs())
            }
            geminiClient.generate(settings.apiKey, settings.model, DIAGNOSE_SYSTEM_PROMPT, prompt).getOrThrow()
        }
    }

    /** Connectivity check for the settings screen. */
    suspend fun testKey(settings: AiSettings): Result<String> =
        geminiClient.generate(
            settings.apiKey,
            settings.model,
            "You are a connection test. Reply with exactly: OK",
            "Reply with exactly: OK",
        )

    /** Persists an AI playlist through the normal playlist pipeline. */
    suspend fun saveSuggestedPlaylist(suggestion: AiPlaylistSuggestion): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                playlistRepository.save(
                    title = suggestion.name.ifBlank { "AI Playlist" },
                    subtitle = suggestion.subtitle,
                    mode = "custom",
                    tracks = suggestion.tracks,
                ).id
            }
        }

    private suspend fun usableSettings(): AiSettings {
        val settings = aiPreferences.settings.first()
        if (!settings.isUsable) {
            throw IllegalStateException(
                "AI assistant is off. Enable it and add your own API key in Settings > AI Assistant.",
            )
        }
        return settings
    }

    private suspend fun buildLibrarySnapshot(): String {
        val stats: List<SongPlayStatsEntity> = runCatching { songPlayStatsDao.getAll() }
            .getOrDefault(emptyList())
        val downloads: List<DownloadedTrackEntity> = runCatching { downloadedTrackDao.getAllList() }
            .getOrDefault(emptyList())
        val playlistNames: List<String> = runCatching { playlistRepository.getAll().map { p -> p.title } }
            .getOrDefault(emptyList())

        return buildString {
            appendLine("TOP_PLAYED (title | artist | playCount | listenedMinutes | oftenSkipped):")
            stats.sortedByDescending { it.totalPlayTimeMs }.take(120).forEach { s ->
                appendLine(
                    "- ${s.title} | ${s.artist} | ${s.playCount} | ${s.totalPlayTimeMs / 60000L}" +
                        if (s.skipCount > s.playCount) " | yes" else "",
                )
            }
            appendLine("LOCAL_SONGS (title | artist | album):")
            downloads.take(150).forEach { d ->
                appendLine("- ${d.title} | ${d.artist} | ${d.album.ifBlank { "-" }}")
            }
            if (playlistNames.isNotEmpty()) {
                appendLine("SAVED_PLAYLISTS: ${playlistNames.take(30).joinToString(", ")}")
            }
        }
    }

    private fun parseSuggestion(answer: String): AiPlaylistSuggestion {
        val cleaned = answer
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw IllegalStateException("The AI did not return a playlist. Try rephrasing the request.")
        }
        val dto = json.decodeFromString<AiSuggestionDto>(cleaned.substring(start, end + 1))
        if (dto.tracks.isEmpty()) {
            throw IllegalStateException("The AI returned a playlist with no songs. Try again.")
        }
        // Ground every track in the listener's own data where possible so the
        // saved playlist opens with correct artwork and metadata. Unmatched
        // suggestions keep their AI-provided names; the playlist pipeline
        // backfills their video ids from a YouTube search on first read.
        val known = buildList {
            addAll(stats.map { Triple(it.title, it.artist, null as String?) })
            addAll(downloads.map { Triple(it.title, it.artist, it.album.ifBlank { null }) })
        }.map { (t, a, al) -> TrackRef(t, a, al) }
        val tracks = dto.tracks.mapNotNull { dtoTrack ->
            if (dtoTrack.title.isBlank()) return@mapNotNull null
            val match = known.firstOrNull { ref ->
                norm(ref.title) == norm(dtoTrack.title) ||
                    (norm(ref.artist) == norm(dtoTrack.artist) && norm(ref.title).contains(norm(dtoTrack.title)))
            } ?: known.firstOrNull { ref ->
                norm(ref.title).startsWith(norm(dtoTrack.title)) && ref.title.length <= dtoTrack.title.length + 12
            }
            GeneratedTrack(
                name = match?.title ?: dtoTrack.title.trim(),
                artist = match?.artist ?: dtoTrack.artist.trim(),
                album = match?.album ?: dtoTrack.album?.takeIf { it.isNotBlank() },
                artworkUrl = null,
                url = "",
            )
        }
        if (tracks.isEmpty()) {
            throw IllegalStateException("The AI returned a playlist with no songs. Try again.")
        }
        return AiPlaylistSuggestion(
            name = dto.name.trim().ifBlank { "AI Playlist" },
            subtitle = dto.subtitle.trim(),
            note = dto.note.trim(),
            tracks = tracks,
        )
    }

    private data class TrackRef(val title: String, val artist: String, val album: String?)

    private fun norm(value: String): String =
        value.lowercase().trim().replace(Regex("\\s+"), " ")

    private fun recentLogs(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-t", "400").start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text.takeLast(6000).ifBlank { "(no logs available)" }
    }.getOrElse { "(no logs available: ${it.message ?: "unknown error"})" }

    private companion object {
        const val TAG = "AiAssistant"

        val ASSISTANT_SYSTEM_PROMPT = """
            You are the built-in AI assistant of Nexora, an Android music player.
            You help the listener with their music: suggestions, playlists, app questions.
            Answer in plain short text, no markdown headings, no preamble.
        """.trimIndent()

        val PLAYLIST_SYSTEM_PROMPT = """
            You are the AI DJ of the Nexora music player.
            You get the listener's music snapshot (top played, local songs, saved playlists) and a request.
            Reply with ONLY minified JSON, no prose, no code fences, shaped exactly:
            {"name":"short playlist name","subtitle":"one line","note":"one line why this fits","tracks":[{"title":"...","artist":"..."}]}
            Rules: 12-20 tracks. Prefer songs that appear in the snapshot; you may add a few new songs that clearly fit the request.
            Never invent a song that shares a title with a snapshot song but a different artist.
        """.trimIndent()

        val DIAGNOSE_SYSTEM_PROMPT = """
            You are a debugging assistant inside the Nexora Android music player.
            The user is not a developer. Explain log findings in plain language, briefly, and give practical next steps.
        """.trimIndent()
    }
}
