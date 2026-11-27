package com.lastwave.app.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import com.lastwave.app.data.local.EQ_BAND_FREQS_HZ
import com.lastwave.app.data.local.applyListeningComfort
import com.lastwave.app.data.local.EQ_MAX_GAIN_DB
import com.lastwave.app.data.local.EqualizerPreferences
import com.lastwave.app.data.local.EqualizerPresets
import com.lastwave.app.data.local.EqualizerSettings
import com.lastwave.app.data.local.SettingsPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Last-resort Android AudioFX layer for devices where native decoded-PCM DSP
 * cannot run. It is mutually exclusive with [NativeProcessingAudioSink]: the
 * platform effects are released whenever native Float32 processing is active,
 * preventing doubled EQ, gain, or limiting.
 *
 * Loudness normalization (ReplayGain tags via [LoudnessNormalizer]) and the
 * Dolby/spatial makeup gain are applied here as a uniform input gain ahead of
 * the limiter. Both are bypassed whenever bit-perfect or USB-exclusive output
 * is active. Per-track feed contract for the player (read-only, no player
 * restructuring required): on track change, call [setReplayGainFromFormat]
 * with the new track's ExoPlayer [Format] (or [setReplayGainTags] with tags
 * parsed from file metadata via [LoudnessNormalizer.parseFromMap]); when the
 * direct USB path takes over, call [setUsbExclusiveActive].
 */
@Singleton
class AudioEffectsEngine @Inject constructor(
    equalizerPreferences: EqualizerPreferences,
    settingsPreferences: SettingsPreferences,
    applicationScope: CoroutineScope,
    // Optional so manually constructed standby engines keep compiling without
    // the prefs binding; null simply leaves loudness normalization OFF.
    loudnessPrefs: LoudnessPrefs? = null,
) {
    private val effectMutex = Mutex()
    private val applyRequests = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var requestedSessionId = C.AUDIO_SESSION_ID_UNSET
    @Volatile private var fallbackRequired = false
    @Volatile private var equalizerSettings = EqualizerSettings()
    @Volatile private var studioClarityEnabled = false
    @Volatile private var bitPerfectActive = false
    @Volatile private var usbExclusiveActive = false
    @Volatile private var systemEffectsActive = false
    @Volatile private var loudnessMode: LoudnessMode = LoudnessMode.OFF
    @Volatile private var loudnessPreampDb = 0f
    @Volatile private var replayGainTags: ReplayGainTags? = null
    @Volatile private var loudnessGainDb = 0f
    @Volatile private var spatialContent = false

    private var attachedSessionId = C.AUDIO_SESSION_ID_UNSET
    private var toneEffect: ToneEffect? = null
    private var bassEffect: BassBoost? = null
    private var virtualizerEffect: Virtualizer? = null

    init {
        applicationScope.launch(Dispatchers.Default) {
            for (ignored in applyRequests) {
                effectMutex.withLock { applyInternal() }
            }
        }
        applicationScope.launch(Dispatchers.Default) {
            equalizerPreferences.settings.collect { settings ->
                equalizerSettings = settings
                publishMusicoletEq(settings)
                requestApply()
            }
        }
        applicationScope.launch(Dispatchers.Default) {
            settingsPreferences.settings.collect { settings ->
                studioClarityEnabled = settings.isStudioMasterClarityEnabled
                requestApply()
            }
        }
        applicationScope.launch(Dispatchers.Default) {
            loudnessPrefs?.settings?.collect { settings ->
                loudnessMode = settings.mode
                loudnessPreampDb = settings.preampDb
                refreshLoudnessGain()
                requestApply()
            }
        }
    }

    /**
     * Publishes the Musicolet-style sheet (15 band gains + bass shelf) to the
     * process-wide native parametric EQ, which the Float32 DSP chain and every
     * direct route pick up. The old AutoEQ publisher keeps working: whichever
     * feature the user last touched owns the chain. Only stands the chain down
     * once the Musicolet EQ itself has been on in this session, so AutoEQ
     * survives a settings re-read.
     */
    @Volatile private var musicoletEqEverOn = false

    private fun publishMusicoletEq(settings: EqualizerSettings) {
        val eqNative = com.lastwave.app.playback.eq.ParametricEqNative
        if (!eqNative.isAvailable) return
        val chainOn = settings.enabled || settings.bassEnabled
        if (chainOn) musicoletEqEverOn = true
        if (!settings.enabled && !musicoletEqEverOn) return
        val filters = buildList {
            EQ_BAND_FREQS_HZ.forEachIndexed { index, hz ->
                val gain = settings.gainsDb.getOrElse(index) { 0f }
                add(
                    com.lastwave.app.playback.eq.ParametricEqNative.NativeFilter(
                        type = 0, // peaking
                        frequencyHz = hz.toFloat(),
                        gainDb = gain,
                        q = 2.15f,
                        enabled = gain != 0f,
                    )
                )
            }
        }
        if (!settings.enabled && !settings.bassEnabled) {
            eqNative.publish(false, 0f, emptyList())
            return
        }
        val maxBoost = filters.maxOf { it.gainDb }
        // Native soft-knee limiter owns clipping headroom now, so the
        // static preamp cut only trims the loudest boost quarter.
        val preamp = -(maxBoost * 0.25f).coerceIn(0f, 6f)
        eqNative.publish(chainOn, preamp, filters)
    }

    /**
     * Applies an in-flight equalizer edit to the audible Musicolet chain
     * immediately, without waiting for the DataStore write to round-trip.
     * Band drags and preset taps land on the next audio block instead of
     * after persistence. Never changes saved state.
     */
    fun previewMusicoletEq(enabled: Boolean, gainsDb: FloatArray) {
        val current = equalizerSettings
        publishMusicoletEq(
            current.copy(enabled = enabled, gainsDb = gainsDb.toList()),
        )
    }

    /** Follows ExoPlayer whenever Android creates or replaces its audio session. */
    fun attach(audioSessionId: Int) {
        requestedSessionId = audioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET }
            ?: C.AUDIO_SESSION_ID_UNSET
        requestApply()
    }

    /** Enables AudioFX only after both native output tiers have failed. */
    fun setFallbackRequired(required: Boolean) {
        if (fallbackRequired == required) return
        fallbackRequired = required
        requestApply()
    }

    /** Dynamically suppresses AudioFX when Bit-Perfect mode is active on a FLAC/Lossless track. */
    fun setBitPerfectActive(active: Boolean) {
        if (bitPerfectActive == active) return
        bitPerfectActive = active
        requestApply()
    }

    /** System audio-effects mode: the session belongs to external EQ / OEM
     *  Dolby, so the fallback tone effect stands down to a flat feed. */
    fun setSystemEffectsActive(active: Boolean) {
        if (systemEffectsActive == active) return
        systemEffectsActive = active
        requestApply()
    }

    /** Suppresses all processing (including loudness gain) while the direct USB path owns output. */
    fun setUsbExclusiveActive(active: Boolean) {
        if (usbExclusiveActive == active) return
        usbExclusiveActive = active
        requestApply()
    }

    /**
     * Feeds the current track's ReplayGain tags. Intended to be called by the
     * player on track change; passing null clears the correction.
     */
    fun setReplayGainTags(tags: ReplayGainTags?) {
        replayGainTags = tags
        refreshLoudnessGain()
        requestApply()
    }

    /**
     * Convenience feed that extracts ReplayGain tags from an ExoPlayer audio
     * [Format]'s container metadata. Safe to call with null or with formats
     * that expose no tags: the correction then falls back to 0 dB.
     */
    fun setReplayGainFromFormat(format: Format?) {
        val sampleMime = format?.sampleMimeType?.lowercase().orEmpty()
        spatialContent = SPATIAL_MIME_MARKERS.any(sampleMime::contains)
        setReplayGainTags(LoudnessNormalizer.parseFromFormat(format))
    }

    fun detach() {
        requestedSessionId = C.AUDIO_SESSION_ID_UNSET
        fallbackRequired = false
        requestApply()
    }

    private fun requestApply() {
        applyRequests.trySend(Unit)
    }

    private fun applyInternal() {
        val targetSessionId = requestedSessionId
        if (targetSessionId != attachedSessionId) {
            releaseAllInternal()
            attachedSessionId = targetSessionId
        }
        if (bitPerfectActive || usbExclusiveActive || systemEffectsActive || attachedSessionId == C.AUDIO_SESSION_ID_UNSET) {
            releaseAllInternal()
            return
        }

        val userEqEnabled = equalizerSettings.enabled
        // Dolby/spatial decodes reach this path (multichannel skips the native
        // DSP) mastered around -18 LKFS, far below stereo masters. The makeup
        // is applied always, not only with normalization on.
        val outputGainDb = (
            (if (loudnessMode != LoudnessMode.OFF) loudnessGainDb else 0f) +
                (if (spatialContent) SPATIAL_MAKEUP_GAIN_DB else 0f)
            ).coerceIn(-LOUDNESS_GAIN_CLAMP_DB, LOUDNESS_GAIN_CLAMP_DB)
        // The platform tone stage is the last-resort fallback only; the native
        // DSP path carries the EQ itself when it is active.
        val needsTone = fallbackRequired && (userEqEnabled || studioClarityEnabled || outputGainDb != 0f)
        if (needsTone) {
            applyToneEffect(buildEqCurve(userEqEnabled), outputGainDb)
        } else {
            releaseToneInternal()
        }

        // Musicolet-style bass boost and 3D surround ride on top of whichever
        // output path is live (native DSP or the fallback tone stage), but
        // never on bit-perfect, USB-exclusive or system-effects routes.
        applyBassVirtualizerInternal()
    }

    private fun applyBassVirtualizerInternal() {
        val sessionId = attachedSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) {
            releaseBassVirtualizerInternal()
            return
        }
        if (!equalizerSettings.bassEnabled && !equalizerSettings.virtualizerEnabled) {
            releaseBassVirtualizerInternal()
            return
        }
        if (equalizerSettings.bassEnabled) {
            var effect = bassEffect
            if (effect == null) {
                effect = runCatching { BassBoost(EFFECT_PRIORITY, sessionId) }
                    .onFailure { Log.w(TAG, "BassBoost creation failed", it) }
                    .getOrNull()
                bassEffect = effect
            }
            effect?.let { boost ->
                runCatching {
                    boost.enabled = true
                    boost.setStrength((equalizerSettings.bassStrength.coerceIn(0, 100) * 10).toShort())
                }.onFailure { Log.w(TAG, "BassBoost apply failed", it) }
            }
        } else {
            runCatching { bassEffect?.enabled = false }
            bassEffect?.release()
            bassEffect = null
        }
        if (equalizerSettings.virtualizerEnabled) {
            var effect = virtualizerEffect
            if (effect == null) {
                effect = runCatching { Virtualizer(EFFECT_PRIORITY, sessionId) }
                    .onFailure { Log.w(TAG, "Virtualizer creation failed", it) }
                    .getOrNull()
                virtualizerEffect = effect
            }
            effect?.let { virtualizer ->
                runCatching {
                    virtualizer.enabled = true
                    virtualizer.setStrength((equalizerSettings.virtualizerStrength.coerceIn(0, 100) * 10).toShort())
                }.onFailure { Log.w(TAG, "Virtualizer apply failed", it) }
            }
        } else {
            runCatching { virtualizerEffect?.enabled = false }
            virtualizerEffect?.release()
            virtualizerEffect = null
        }
    }

    private fun releaseBassVirtualizerInternal() {
        runCatching { bassEffect?.enabled = false }
        bassEffect?.release()
        bassEffect = null
        runCatching { virtualizerEffect?.enabled = false }
        virtualizerEffect?.release()
        virtualizerEffect = null
    }

    private fun refreshLoudnessGain() {
        // The fallback stage has no true peak limiter of its own, so the
        // peak guard always stays engaged here (limiterEngaged = false).
        loudnessGainDb = LoudnessNormalizer.gainForTags(
            tags = replayGainTags,
            mode = loudnessMode,
            preampDb = loudnessPreampDb,
            limiterEngaged = false,
        )
    }

    private fun buildEqCurve(userEqEnabled: Boolean): FloatArray {
        val curve = FloatArray(EQ_BAND_FREQS_HZ.size) { index ->
            val studioGain = if (studioClarityEnabled) {
                EqualizerPresets.STUDIO_MASTER.gainsDb.getOrElse(index) { 0f }
            } else 0f
            val userGain = if (userEqEnabled) {
                equalizerSettings.gainsDb.getOrElse(index) { 0f }
            } else 0f
            val combined = studioGain + userGain
            if (combined.isFinite()) combined.coerceIn(-EQ_MAX_GAIN_DB, EQ_MAX_GAIN_DB) else 0f
        }
        // Same listening-comfort taming the native DSP path applies, so the
        // fallback stage matches: long sessions stay easy on the ears.
        return applyListeningComfort(curve)
    }

    /**
     * [outputGainDb] is a uniform level change. It must stay out of the EQ
     * curve: the curve's boost-headroom subtraction cancelled all but 1 dB of
     * a uniform boost, so the old +6 dB Atmos makeup arrived as +1 dB.
     */
    private fun applyToneEffect(gainsDb: FloatArray, outputGainDb: Float) {
        var effect = toneEffect
        if (effect == null || !effect.hasControl()) {
            releaseToneInternal()
            effect = createToneEffect(attachedSessionId)
            toneEffect = effect
        }
        if (effect?.apply(gainsDb, outputGainDb) == true) return

        if (effect is ModernDynamicsEffect) {
            Log.w(TAG, "DynamicsProcessing rejected this device; using legacy Equalizer")
            releaseToneInternal()
            val legacy = LegacyEqualizerEffect.create(attachedSessionId)
            toneEffect = legacy
            if (legacy?.apply(gainsDb, outputGainDb) != true) {
                Log.w(TAG, "Platform Equalizer is unavailable on this audio route")
                releaseToneInternal()
            }
        } else {
            Log.w(TAG, "Platform Equalizer failed on this audio route")
            releaseToneInternal()
        }
    }

    private fun createToneEffect(audioSessionId: Int): ToneEffect? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ModernDynamicsEffect.create(audioSessionId)?.let { return it }
        }
        return LegacyEqualizerEffect.create(audioSessionId)
    }

    private fun releaseAllInternal() {
        releaseToneInternal()
        releaseBassVirtualizerInternal()
    }

    private fun releaseToneInternal() {
        toneEffect?.release()
        toneEffect = null
    }

    private interface ToneEffect {
        fun hasControl(): Boolean
        fun apply(gainsDb: FloatArray, outputGainDb: Float): Boolean
        fun release()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private class ModernDynamicsEffect private constructor(
        private val processor: DynamicsProcessing,
    ) : ToneEffect {
        override fun hasControl(): Boolean = runCatching { processor.hasControl() }.getOrDefault(false)

        override fun apply(gainsDb: FloatArray, outputGainDb: Float): Boolean = runCatching {
            // The -1 dBFS limiter below catches the peaks a positive gain pushes over.
            processor.setInputGainAllChannelsTo(calculateHeadroomDb(gainsDb) + outputGainDb)
            val preEq = DynamicsProcessing.Eq(true, true, EQ_BAND_FREQS_HZ.size)
            EQ_BAND_FREQS_HZ.forEachIndexed { index, frequency ->
                preEq.setBand(
                    index,
                    DynamicsProcessing.EqBand(true, frequency.toFloat(), gainsDb.getOrElse(index) { 0f }),
                )
            }
            processor.setPreEqAllChannelsTo(preEq)
            processor.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(
                    true,
                    true,
                    0,
                    1.0f,
                    120.0f,
                    12.0f,
                    -1.0f,
                    0.0f,
                ),
            )
            processor.enabled = true
        }.onFailure { Log.w(TAG, "DynamicsProcessing apply failed", it) }.isSuccess

        override fun release() {
            runCatching {
                processor.enabled = false
                processor.release()
            }
        }

        companion object {
            fun create(audioSessionId: Int): ModernDynamicsEffect? = runCatching {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true,
                    EQ_BAND_FREQS_HZ.size,
                    false,
                    0,
                    false,
                    0,
                    true,
                ).build()
                ModernDynamicsEffect(DynamicsProcessing(EFFECT_PRIORITY, audioSessionId, config))
            }.onFailure { Log.w(TAG, "DynamicsProcessing creation failed", it) }.getOrNull()
        }
    }

    private class LegacyEqualizerEffect private constructor(
        private val equalizer: Equalizer,
    ) : ToneEffect {
        override fun hasControl(): Boolean = runCatching { equalizer.hasControl() }.getOrDefault(false)

        override fun apply(gainsDb: FloatArray, outputGainDb: Float): Boolean = runCatching {
            val range = equalizer.bandLevelRange
            val minimumMb = range.first().toInt()
            val maximumMb = range.last().toInt()
            val headroomDb = calculateHeadroomDb(gainsDb) + outputGainDb
            for (band in 0 until equalizer.numberOfBands.toInt()) {
                val centerHz = equalizer.getCenterFreq(band.toShort()) / MILLIHERTZ_PER_HZ
                val gainDb = interpolateCurve(centerHz, gainsDb) + headroomDb
                val levelMb = (gainDb * MILLIBELS_PER_DB).roundToInt().coerceIn(minimumMb, maximumMb)
                equalizer.setBandLevel(band.toShort(), levelMb.toShort())
            }
            equalizer.enabled = true
        }.onFailure { Log.w(TAG, "Legacy Equalizer apply failed", it) }.isSuccess

        override fun release() {
            runCatching {
                equalizer.enabled = false
                equalizer.release()
            }
        }

        companion object {
            fun create(audioSessionId: Int): LegacyEqualizerEffect? = runCatching {
                LegacyEqualizerEffect(Equalizer(EFFECT_PRIORITY, audioSessionId))
            }.onFailure { Log.w(TAG, "Legacy Equalizer creation failed", it) }.getOrNull()
        }
    }

    private companion object {
        const val TAG = "FallbackAudioFX"
        const val EFFECT_PRIORITY = 0
        const val MILLIHERTZ_PER_HZ = 1_000
        const val MILLIBELS_PER_DB = 100f
        const val MAX_PRE_LIMITER_BOOST_DB = 1.0f
        /** Lifts ~-18 LKFS Atmos music toward the level of stereo masters. */
        const val SPATIAL_MAKEUP_GAIN_DB = 6f
        val SPATIAL_MIME_MARKERS = listOf("eac3", "ec-3", "ac-3", "ac3", "ac4", "mha1", "mhm1", "atmos", "dolby")

        fun calculateHeadroomDb(gainsDb: FloatArray): Float {
            val maximumBoost = (gainsDb.maxOrNull() ?: 0f).coerceAtLeast(0f)
            return -(maximumBoost - MAX_PRE_LIMITER_BOOST_DB).coerceAtLeast(0f)
        }

        fun interpolateCurve(hz: Int, gainsDb: FloatArray): Float {
            if (gainsDb.size != EQ_BAND_FREQS_HZ.size) return 0f
            if (hz <= EQ_BAND_FREQS_HZ.first()) return gainsDb.first()
            if (hz >= EQ_BAND_FREQS_HZ.last()) return gainsDb.last()
            val query = ln(hz.toFloat())
            var index = 0
            for (i in 0 until EQ_BAND_FREQS_HZ.lastIndex) {
                if (query >= ln(EQ_BAND_FREQS_HZ[i].toFloat())) index = i
            }
            val low = ln(EQ_BAND_FREQS_HZ[index].toFloat())
            val high = ln(EQ_BAND_FREQS_HZ[index + 1].toFloat())
            val t = ((query - low) / (high - low)).coerceIn(0f, 1f)
            // Catmull-Rom through the neighbouring bands: a smooth curve across
            // the slider line instead of straight joins, so adjacent-band
            // settings blend rather than kink.
            val p0 = gainsDb[(index - 1).coerceAtLeast(0)]
            val p1 = gainsDb[index]
            val p2 = gainsDb[index + 1]
            val p3 = gainsDb[(index + 2).coerceAtMost(gainsDb.lastIndex)]
            val t2 = t * t
            val t3 = t2 * t
            val curve = 0.5f * (
                2f * p1 +
                    (-p0 + p2) * t +
                    (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
                    (-p0 + 3f * p1 - 3f * p2 + p3) * t3
                )
            // Spline curves overshoot: between two very different band
            // settings the Catmull-Rom curve can peak past the slider values
            // themselves, which reads as an unintended boost and can clip.
            // Keep the interpolated gain inside the envelope of the sliders.
            val curveLow = gainsDb.minOrNull() ?: 0f
            val curveHigh = gainsDb.maxOrNull() ?: 0f
            return curve.coerceIn(minOf(curveLow, curveHigh), maxOf(curveLow, curveHigh))
        }
    }
}
