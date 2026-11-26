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
