package com.lastwave.app.playback

import com.lastwave.app.data.local.EqualizerSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Native bass boost for the Float32 chain, replacing the platform BassBoost
 * effect that the native path never reached.
 *
 * Three pieces, from the classic bass-enhancement recipe:
 *  1. A low-shelf biquad at 100 Hz (Q 0.707) lifting everything below the
 *     shelf by up to +9 dB, scaled by the equalizer sheet's Bass boost
 *     strength (0-100). Coefficients are rebuilt from a smoothed gain so
 *     moving the slider never clicks.
 *  2. A beat punch: a fast/slow envelope pair on the boosted low band; when
 *     a kick or bass note attacks, a short extra lift (about 150 ms) makes
 *     the hit land harder without raising the average level.
 *  3. A soft second harmonic of the low band, so small speakers are heard
 *     reproducing bass they cannot physically produce.
 *
 * Runs before the virtualizer and the per-song 8D / 16D spatializer; mono
 * sources are processed on both channels identically; bit-perfect output
 * skips the whole DSP chain.
 */
class BassBoostStage(
    settings: Flow<EqualizerSettings>,
    scope: CoroutineScope,
) {
    @Volatile private var targetGainDb = 0f
    private var appliedGainDb = 0f
    private var strength = 0f

    private var sampleRateHz = 0
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private var x1L = 0f
    private var x2L = 0f
    private var y1L = 0f
    private var y2L = 0f
    private var x1R = 0f
    private var x2R = 0f
    private var y1R = 0f
    private var y2R = 0f

    private var fastEnv = 0f
    private var slowEnv = 0f
    private var punch = 0f
    private var fastDecay = 0.9965f
    private var slowRise = 0.00002f

    init {
        scope.launch {
            settings.collect { s ->
                strength = s.bassStrength.coerceIn(0, 100) / 100f
                targetGainDb = if (s.bassEnabled && strength > 0f) strength * 9f else 0f
            }
        }
    }

    private fun rebuildCoefficients(gainDb: Float) {
        val sr = sampleRateHz
        if (sr <= 0) return
        val a = Math.pow(10.0, gainDb / 40.0).toFloat()
        val w0 = (2.0 * Math.PI * BASS_SHELF_HZ / sr).toFloat()
        val cosW0 = kotlin.math.cos(w0)
        val alpha = kotlin.math.sin(w0) * 0.5f * kotlin.math.sqrt(2f)
        val sqrtA = kotlin.math.sqrt(a)
        val a0 = (a + 1) + (a - 1) * cosW0 + 2 * sqrtA * alpha
        b0 = (a * ((a + 1) - (a - 1) * cosW0 + 2 * sqrtA * alpha)) / a0
        b1 = (2 * a * ((a - 1) - (a + 1) * cosW0)) / a0
        b2 = (a * ((a + 1) - (a - 1) * cosW0 - 2 * sqrtA * alpha)) / a0
        a1 = (-2 * ((a - 1) + (a + 1) * cosW0)) / a0
        a2 = ((a + 1) + (a - 1) * cosW0 - 2 * sqrtA * alpha) / a0
    }

    fun process(output: ByteBuffer, channelCount: Int, sampleRateHz: Int) {
        if (sampleRateHz <= 0) return
        if (sampleRateHz != this.sampleRateHz) {
            this.sampleRateHz = sampleRateHz
            fastDecay = Math.exp(-1.0 / (0.006 * sampleRateHz)).toFloat()
            slowRise = (1.0 / (0.250 * sampleRateHz)).toFloat()
            rebuildCoefficients(appliedGainDb)
        }
        val isStereo = channelCount == 2

        // Slide the shelf gain toward its target; at roughly one step per
        // buffer this takes about half a second and never clicks.
        if (kotlin.math.abs(targetGainDb - appliedGainDb) > 0.05f) {
            appliedGainDb += (targetGainDb - appliedGainDb) * 0.06f
            rebuildCoefficients(appliedGainDb)
        }
        val boosting = appliedGainDb > 0.05f || targetGainDb > 0.05f
        if (!boosting) return

        val punchStrength = strength
        val harmonicAmount = 0.12f * strength
        val originalOrder = output.order()
        try {
            output.order(ByteOrder.LITTLE_ENDIAN)
            var p = output.position()
            val limit = output.limit()
            if (isStereo) {
                while (p + 2 * Float.SIZE_BYTES <= limit) {
                    val l = output.getFloat(p)
                    val r = output.getFloat(p + Float.SIZE_BYTES)

                    val shelfL = b0 * l + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
                    x2L = x1L; x1L = l; y2L = y1L; y1L = shelfL
                    val shelfR = b0 * r + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
                    x2R = x1R; x1R = r; y2R = y1R; y1R = shelfR

                    // Beat punch: fast envelope vs slow envelope on the low band.
                    val low = (shelfL + shelfR) * 0.5f
                    val absLow = kotlin.math.abs(low)
                    fastEnv = if (absLow > fastEnv) absLow else absLow + (fastEnv - absLow) * fastDecay
                    slowEnv += (fastEnv - slowEnv) * slowRise
                    val onset = (fastEnv - slowEnv).coerceAtLeast(0f) / (slowEnv + 0.05f)
                    punch += ((onset.coerceAtMost(1.5f) * 0.8f) - punch) * 0.10f
                    val extra = 1f + punch * punchStrength * 0.30f

                    // Soft second harmonic of the low band.
                    val harm = low * kotlin.math.abs(low) * harmonicAmount

                    val outL = (shelfL * extra + harm * 0.8f) * 0.92f
                    val outR = (shelfR * extra + harm * 0.8f) * 0.92f
                    output.putFloat(p, outL.coerceIn(-1.0f, 1.0f))
                    output.putFloat(p + Float.SIZE_BYTES, outR.coerceIn(-1.0f, 1.0f))
                    p += 2 * Float.SIZE_BYTES
                }
            } else {
                while (p + Float.SIZE_BYTES <= limit) {
                    val x = output.getFloat(p)
                    val shelf = b0 * x + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
                    x2L = x1L; x1L = x; y2L = y1L; y1L = shelf
                    val absLow = kotlin.math.abs(shelf)
                    fastEnv = if (absLow > fastEnv) absLow else absLow + (fastEnv - absLow) * fastDecay
                    slowEnv += (fastEnv - slowEnv) * slowRise
                    val onset = (fastEnv - slowEnv).coerceAtLeast(0f) / (slowEnv + 0.05f)
                    punch += ((onset.coerceAtMost(1.5f) * 0.8f) - punch) * 0.10f
                    val extra = 1f + punch * punchStrength * 0.30f
                    val harm = shelf * kotlin.math.abs(shelf) * harmonicAmount
                    val out = (shelf * extra + harm) * 0.92f
                    output.putFloat(p, out.coerceIn(-1.0f, 1.0f))
                    p += Float.SIZE_BYTES
                }
            }
        } finally {
            output.order(originalOrder)
        }
    }

    private companion object {
        const val BASS_SHELF_HZ = 100f
    }
}
