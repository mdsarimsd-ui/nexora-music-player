<div align="center">

<img src="assets/Nexora_banner_1440p.png" alt="Nexora Banner" width="100%" style="border-radius: 16px;" />

<h1>Nexora</h1>

**Simple Android YouTube Music client with Material UI, Liquid Glass, Bit-Perfect playback, Automix, Lossless addons, Musicolet-style equalizer & advanced audiophile features.**

<br/>

<p align="center">
  <a href="https://github.com/mdsarimsd-ui/nexora-music-player/blob/main/LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/License-GPL%20v3-blue.svg?style=for-the-badge" /></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android-green.svg?style=for-the-badge" />
  <img alt="Language" src="https://img.shields.io/badge/language-Kotlin-purple.svg?style=for-the-badge" />
  <img alt="Min SDK" src="https://img.shields.io/badge/API-24%2B-orange.svg?style=for-the-badge" />
  <img alt="Status" src="https://img.shields.io/badge/status-Active%20Development-brightgreen.svg?style=for-the-badge" />
</p>

</div>

---

## Overview

Nexora is a fast, native YouTube Music client for Android that looks great and sounds even better. Built with Material 3 Expressive and Liquid Glass UI, it gives you a clean, fluid design that adapts to your music.

For audio lovers, Nexora packs Bit-Perfect USB DAC output, Lossless FLAC addon support, a Musicolet-style 15-band equalizer with Bass Boost and 3D Surround, AutoEQ headphone presets with parametric EQ, smooth Automix transitions, Spotify Canvas integration, and real-time synced lyrics, all in a lightweight app.

---

## Features

<table>
  <tr>
    <td width="50%" valign="top">

#### Playback
- Stream any track or video from YouTube Music
- Background playback with lockscreen & Android Auto controls
- High-res audio downloads with embedded tags & `.lrc` lyrics
- Download & cache for offline listening
- Skip silence & sleep timer

</td>
    <td width="50%" valign="top">

#### Audio
- Bit-Perfect USB DAC output (direct hardware bypass)
- Lossless FLAC addon support (up to 24-bit Hi-Res)
- Musicolet-style 15-band Equalizer with Bass Boost & 3D Surround
- Classic EQ presets: Normal, Classical, Dance, Folk, Heavy Metal, Hip Hop, Jazz, Pop, Rock & more
- AutoEQ headphone presets & parametric EQ
- Smart Automix transitions & smooth crossfade
- Audio normalization (ReplayGain / LUFS)
- Real-time tempo & pitch control

</td>
  </tr>
  <tr>
    <td width="50%" valign="top">

#### Lyrics & Discovery
- Live millisecond-synchronized lyrics (LRCLIB)
- Lyrics romanization for non-Latin scripts (Japanese, Korean, etc.)
- Multi-language lyrics translation
- Spotify Canvas & animated video backgrounds (Apple Music & Tidal)
- Personalized quick picks, mood radios & genre discovery
- Search songs, albums, artists, videos, and playlists

</td>
    <td width="50%" valign="top">

#### Library & Account
- Full library management & offline cached library
- Spotify Integration (Account login, Liked Songs sync & deep links)
- Import public playlists from Spotify & Apple Music
- YouTube Music account login & bidirectional sync
- Local & cloud playlists with custom reordering
- Custom audio tag editing & cover art selection

</td>
  </tr>
  <tr>
    <td width="50%" valign="top">

#### Social
- Listen together with friends in real-time
- Built-in Last.fm scrobbler with background tracking
- Safe Discord Rich Presence with live album art
- Export and share lyrics as aesthetic quote cards

</td>
    <td width="50%" valign="top">

#### Interface
- Material 3 Expressive & Liquid Glass UI
- Real-time backdrop refraction shaders
- Dynamic Monet theming + 19 preset color palettes
- Light, Dark, Pure Black (AMOLED), and Dynamic modes
- Interactive home screen widgets & fluid tactile haptics

</td>
  </tr>
</table>

---

## Getting Started

### Installation

| Channel | Source | Compatibility |
|:---|:---|:---|
| **Stable Release** | [GitHub Releases](https://github.com/mdsarimsd-ui/nexora-music-player/releases) | Recommended for all users • Android 7.0+ (API 24+) |
| **Nightly Builds** | [GitHub Actions](https://github.com/mdsarimsd-ui/nexora-music-player/actions) | Latest automated builds with bleeding-edge features |

### Quick Setup

1. Install the APK on your Android device.
2. *(Optional)* Navigate to **Settings → Audio** to configure:
   - **Equalizer:** Open the 15-band equalizer for Bass Boost, 3D Surround and presets.
   - **Bit-Perfect Output:** Enable exclusive USB DAC mode for bit-perfect audio.
   - **AutoEQ Presets:** Search for your headphones/IEMs to apply calibrated parametric EQ curves.
   - **Lossless Addons:** Enable the modular addon service for 24-bit Hi-Res FLAC playback.
3. Start streaming music, listening to customized radios, and enjoying synced lyrics!

---

## Building from Source

### Prerequisites
- **JDK:** OpenJDK 17 or higher
- **Android SDK:** API Level 34+ with CMake & Android NDK installed (for native audio C++ engine & USB driver)

### Build Instructions

```bash
# Clone the repository
git clone https://github.com/mdsarimsd-ui/nexora-music-player.git
cd nexora-music-player

# Build the release APK
./gradlew assembleRelease

# The generated APK will be at:
# app/build/outputs/apk/release/app-release-unsigned.apk
```

> [!TIP]
> For local development and debugging, run `./gradlew assembleDebug` or install directly to a connected device via `./gradlew installDebug`.

---

## Roadmap

- 8D / 16D spatial audio with a per-song toggle on the player screen
- Per-device equalizer memory (different EQ for headphones vs speaker)
- Crossfade slider refinements
- Playback speed & pitch enhancements

---

## Acknowledgements

Nexora is a fork of **[LastWave](https://github.com/Clash-Projects/LastWave-native)** (GPL-3.0) with a rebrand and an expanded equalizer. The equalizer experience is inspired by the beloved **[Musicolet](https://play.google.com/store/apps/details?id=in.krosbits.musolet)** music player.

Nexora is made possible thanks to these open-source projects (carried over from LastWave):

- **[Metrolist](https://github.com/MetrolistGroup/Metrolist)** — InnerTubeX & stream resolving
- **[SimpMusic](https://github.com/maxrave-dev/SimpMusic)** — Canvas extraction & Liquid Glass inspiration
- **[InnerTune](https://github.com/z-huang/InnerTune)** — InnerTube extraction architecture
- **[Decent USB Audio Driver](https://github.com/silva96/decent-usb-audio-driver)** — Bit-perfect direct USB DAC audio engine
- **[AutoEQ](https://github.com/jaakkopasanen/AutoEq)** — Headphone & IEM frequency-response calibration targets
- **[NewPipe](https://github.com/TeamNewPipe/NewPipeExtractor)** — Stream extraction & media URL resolving
- **[SponsorBlock](https://sponsor.ajay.app/)** — Video segment detection for music video intro skit synchronization

---

## Disclaimer

This project is not affiliated with YouTube, Google, Spotify, or Musicolet. Streaming from YouTube Music may violate their Terms of Service in some regions; use at your own discretion. The app is distributed as an installable APK and is not published on Google Play.

---

## License

Nexora is licensed under the [GNU General Public License v3.0](LICENSE).
