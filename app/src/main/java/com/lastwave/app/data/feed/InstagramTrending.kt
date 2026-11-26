package com.lastwave.app.data.feed

import com.lastwave.app.data.music.YouTubeMusicTrack

/** A reel audio currently trending on Instagram, with its public reel count. */
data class InstagramTrendingAudio(
    val audio: String,
    val artist: String,
    val reels: String,
)

/** An [InstagramTrendingAudio] resolved to a playable YouTube Music track. */
data class TrendingInstagramEntry(
    val audio: String,
    val artist: String,
    val reels: String,
    val track: YouTubeMusicTrack?,
)

/**
 * The week's trending Instagram reel audios, compiled from public reels-trend
 * trackers (CreatorDB week of Nov 18 2026: reel counts and heat rankings).
 * Each entry is resolved to a playable track when the home feed loads, so
 * tapping a card starts the song.
 */
object InstagramTrending {
    val CURRENT: List<InstagramTrendingAudio> = listOf(
        InstagramTrendingAudio("Housewife", "Tate McRae", "273K reels"),
        InstagramTrendingAudio("I Knew It, I Knew You", "Taylor Swift", "275K reels"),
        InstagramTrendingAudio("Last Christmas", "Wham!", "944K reels"),
        InstagramTrendingAudio("Coming Up Roses", "Harry Styles", "116K reels"),
        InstagramTrendingAudio("Mr. Brightside", "The Killers", "102K reels"),
        InstagramTrendingAudio("Stop the Wedding!", "Ashe", "82K reels"),
        InstagramTrendingAudio("Time", "Pink Floyd", "467K reels"),
        InstagramTrendingAudio("My Moon My Man", "FINNEAS", "Rising"),
    )
}

/** One home screen song row: a query that fills it with tracks. */
data class SongRowDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val query: String,
)

/**
 * The song-only rows of the rebuilt home screen. Every row is a plain list of
 * songs pulled from YouTube Music search at load time: no playlists, no album
 * cards, no artist cards. Hindi and English both covered, plus mood rows so
 * the home is full of songs to scroll.
 */
object SongRows {
    val DEFS: List<SongRowDef> = listOf(
        SongRowDef("trending", "Trending Songs", "Rising fastest on the charts", "top trending songs"),
        SongRowDef("global", "Global Hits", "What the world is playing", "top hits worldwide"),
        SongRowDef("hindi", "Hindi Hits", "Bollywood and beyond, in Hindi", "hindi top songs"),
        SongRowDef("english", "English Hits", "The English songs everyone plays", "top english songs"),
        SongRowDef("punjabi", "Punjabi Hits", "Loud, straight from Punjab", "punjabi top songs"),
        SongRowDef("party", "Party Hits", "Turn it up", "party songs hits"),
        SongRowDef("romantic", "Romantic Hits", "For the slow evenings", "romantic love songs hindi english"),
        SongRowDef("90s", "90s and 2000s Hits", "The golden throwbacks", "90s 2000s hit songs"),
        SongRowDef("sad", "Sad Songs", "For the feelings", "sad songs hindi english"),
        SongRowDef("new", "New Songs", "Fresh out this week", "new songs this week"),
        SongRowDef("lofi", "Lofi and Chill", "Slow beats for study and sleep", "lofi chill songs"),
        SongRowDef("retro", "Retro Bollywood", "The old Hindi classics", "old hindi songs kishore kumar lata"),
        SongRowDef("workout", "Workout Hits", "High energy for the gym", "workout songs hits"),
    )
}
