package com.lastwave.app.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Per-song spatialization modes behind the now-playing screen's toggle. */
enum class SpatializationMode(val label: String) {
    OFF("Off"),
    D8("8D"),
    D16("16D"),
}

/**
 * YouTube-style rotating-pan spatializer for the 8D / 16D toggle.
 *
 * A panned core (mono sum) rotates around the head in a full circle (8D) or
 * swings ear to ear (16D) over a Schroeder reverb tail, while the original
 * stereo bed stays in underneath so the mix keeps its width. An ear floor
 * keeps each ear from ever dropping fully silent, and the reverb send is
 * lighter than before so vocals stay clear.
 *
 * Runs on the native DSP output path right after the virtualizer. Only
 * stereo Float32 buffers are touched; anything else passes through
 * untouched, and bit-perfect output skips this stage entirely.
 */
class SpatializationStage {
    @Volatile var mode: SpatializationMode = SpatializationMode.OFF
    /** Last mode process() saw, so a fresh mode restarts its swing from 0. */
    private var lastAppliedMode: SpatializationMode = SpatializationMode.OFF

    private var panRad = 0.0
    private var alternationPhase = 0.0
    private var reverbRateHz = 0

    /** Frames left in the engage ramp that fades the effect in after a mode change. */
    private var engageRampFrames = 0
    private var engageRampTotal = 0
    /** Set on a mode change; resolved to a real ramp length once the sample rate is known. */
    private var pendingEngageRamp = false

    private var combDelay = IntArray(0)
    private var combIdx = IntArray(0)
    private var combBuf = emptyArray<FloatArray>()
    private var combStore = FloatArray(0)
    private var allpassDelay = IntArray(0)
    private var allpassIdx = IntArray(0)
    private var allpassBuf = emptyArray<FloatArray>()

    /** Head-shadow low-pass state for the panned core (see process()). */
    private var shadowState = 0f

    /** Mono ring buffer feeding the per-ear interaural delays. */
    private var itdBuf = FloatArray(0)
    private var itdIdx = 0
    private var itdMaxSamples = 0

    /** One full circle every 8 s for 8D; no circle for 16D. */
    private val rotationHz: Double
        get() = if (mode == SpatializationMode.D8) 1.0 / 8.0 else 0.0

    /** 16D swings between the ears at 0.7 Hz (about every 1.4 s). */
    private val alternationHz: Double
        get() = if (mode == SpatializationMode.D16) 0.7 else 0.0

    private fun ensureReverb(sampleRateHz: Int) {
        if (sampleRateHz == reverbRateHz && combBuf.isNotEmpty()) return
        reverbRateHz = sampleRateHz
        panRad = 0.0
        alternationPhase = 0.0
        val scale = sampleRateHz / 44100.0
        combDelay = intArrayOf(1119, 1189, 1277, 1367)
            .map { (it * scale).toInt().coerceAtLeast(1) }
            .toIntArray()
        combIdx = IntArray(combDelay.size)
        combBuf = Array(combDelay.size) { FloatArray(combDelay[it]) }
        combStore = FloatArray(combDelay.size)
        allpassDelay = intArrayOf(225, 556)
            .map { (it * scale).toInt().coerceAtLeast(1) }
            .toIntArray()
        allpassIdx = IntArray(allpassDelay.size)
        allpassBuf = Array(allpassDelay.size) { FloatArray(allpassDelay[it]) }
        // Max interaural time difference of 0.6 ms: the physical limit of
        // ear-to-ear arrival delay a real head produces.
        itdMaxSamples = (0.0006 * sampleRateHz).toInt().coerceIn(8, 96)
        itdBuf = FloatArray(itdMaxSamples + 1)
        itdIdx = 0
        java.util.Arrays.fill(itdBuf, 0f)
    }

    /** One-sample Schroeder reverb (4 combs + 2 allpass) on the mono source. */
    private fun reverb(input: Float): Float {
        var sum = 0f
        for (c in combBuf.indices) {
            val buf = combBuf[c]
            val idx = combIdx[c]
            val y = buf[idx]
            combStore[c] += 0.30f * (y - combStore[c])
            buf[idx] = input + combStore[c] * 0.78f
            combIdx[c] = (idx + 1) % combDelay[c]
            sum += y
        }
        var out = sum * 0.25f
        for (a in allpassBuf.indices) {
            val buf = allpassBuf[a]
            val idx = allpassIdx[a]
            val bufOut = buf[idx]
            out = -out + bufOut
            buf[idx] = out * 0.5f
            allpassIdx[a] = (idx + 1) % allpassDelay[a]
        }
        return out
    }

    fun process(output: ByteBuffer, channelCount: Int, sampleRateHz: Int) {
        val mode = mode
        if (mode != lastAppliedMode) {
            // A newly engaged (or switched) 8D / 16D starts its swing from the
            // center immediately, instead of continuing a stale rotation.
            lastAppliedMode = mode
            panRad = 0.0
            alternationPhase = 0.0
            shadowState = 0f
            itdIdx = 0
            if (itdBuf.isNotEmpty()) java.util.Arrays.fill(itdBuf, 0f)
            if (mode != SpatializationMode.OFF) pendingEngageRamp = true
        }
        if (mode == SpatializationMode.OFF || channelCount != 2 || sampleRateHz <= 0) return
        ensureReverb(sampleRateHz)
        if (pendingEngageRamp) {
            // Poweramp-style engage: the effect fades in over 250 ms so
            // toggling 8D / 16D or switching between them never clicks.
            engageRampTotal = (0.25 * sampleRateHz).toInt().coerceIn(1, sampleRateHz)
            engageRampFrames = engageRampTotal
            pendingEngageRamp = false
        }
        val originalOrder = output.order()
        try {
            output.order(ByteOrder.LITTLE_ENDIAN)
            val rotationPerFrame = 2.0 * PI * rotationHz / sampleRateHz
            val alternationPerFrame = 2.0 * PI * alternationHz / sampleRateHz
            var theta = panRad
            var alt = alternationPhase
            var pos = output.position()
            val limit = output.limit()
            while (pos + 2 * Float.SIZE_BYTES <= limit) {
                val l = output.getFloat(pos)
                val r = output.getFloat(pos + Float.SIZE_BYTES)
                val mono = (l + r) * 0.5f
                val wet = reverb(mono)
                val gL: Float
                val gR: Float
                // Position along the left-right axis (-1 far left .. +1 far
                // right) and how far behind the head the source sits (0..1).
                val lateral: Float
                var behindness = 0f
                if (rotationPerFrame > 0.0) {
                    // Full circle around the head. The original stereo bed
                    // stays in underneath so the mix never collapses to mono.
                    gL = cos(theta).toFloat()
                    gR = sin(theta).toFloat()
                    lateral = gL
                    // Front half of the circle is the positive sine side.
                    behindness = (-sin(theta)).toFloat().coerceIn(0f, 1f)
                    theta += rotationPerFrame
                    if (theta > TAU) theta %= TAU
                } else {
                    // Ear to ear: a smooth left-right swing and back, equal
                    // power, with an ear floor so no ear ever drops fully
                    // silent (that dropout was what made 16D feel stuck).
                    val tri = 0.5 - 0.5 * cos(alt)
                    gL = cos(0.5 * PI * tri).toFloat()
                    gR = sin(0.5 * PI * tri).toFloat()
                    lateral = (2.0 * tri - 1.0).toFloat()
                    alt += alternationPerFrame
                    if (alt > TAU) alt %= TAU
                }
                val earFloor = 0.08f
                val panL = earFloor + (1f - earFloor) * gL.coerceAtLeast(0f)
                val panR = earFloor + (1f - earFloor) * gR.coerceAtLeast(0f)

                // Head shadow: as the source passes behind the head its high
                // frequencies lose the direct path, so the panned core dims
                // toward a warm one-pole low-pass there. This is what turns
                // the flat left-right circle into something that passes
                // behind you. Applied before the ring write so both ears
                // share one shadow state and the overall energy stays put.
                val shadowAlpha = (1.0 - 0.72 * behindness).toFloat()
                shadowState += shadowAlpha * (mono - shadowState)

                // Interaural time difference: the ear on the far side of the
                // swing hears the panned core up to 0.6 ms later, the exact
                // cue human hearing uses to judge direction. Per-ear delayed
                // taps of the mono ring replace the shared pan core.
                if (itdBuf.isNotEmpty()) {
                    itdBuf[itdIdx] = shadowState
                }
                val delayL = (itdMaxSamples * lateral.coerceAtLeast(0f)).toInt()
                val delayR = (itdMaxSamples * (-lateral).coerceAtLeast(0f)).toInt()
                val n = itdBuf.size
                val idxL = (itdIdx - delayL + n) % n
                val idxR = (itdIdx - delayR + n) % n
                val panDepth = 0.85f
                val coreL = (if (n > 0) itdBuf[idxL] else mono) * panL * panDepth
                val coreR = (if (n > 0) itdBuf[idxR] else mono) * panR * panDepth
                itdIdx = if (n > 0) (itdIdx + 1) % n else 0

                val dry = 0.35f
                val wetMix = 0.30f
                val outL = ((l * dry + coreL + wet * panL * wetMix) * 0.80f).coerceIn(-1.0f, 1.0f)
                val outR = ((r * dry + coreR + wet * panR * wetMix) * 0.80f).coerceIn(-1.0f, 1.0f)
                // Fade the whole effect in over the engage ramp: ramp 0 is
                // plain passthrough, 1 is the full 8D / 16D mix above.
                val ramp = if (engageRampFrames > 0) {
                    engageRampFrames--
                    1f - engageRampFrames.toFloat() / engageRampTotal.toFloat()
                } else 1f
                output.putFloat(pos, (l + (outL - l) * ramp).coerceIn(-1.0f, 1.0f))
                output.putFloat(pos + Float.SIZE_BYTES, (r + (outR - r) * ramp).coerceIn(-1.0f, 1.0f))
                pos += 2 * Float.SIZE_BYTES
            }
            panRad = theta
            alternationPhase = alt
        } finally {
            output.order(originalOrder)
        }
    }

    fun reset() {
        panRad = 0.0
        alternationPhase = 0.0
        shadowState = 0f
        engageRampFrames = 0
        engageRampTotal = 0
        pendingEngageRamp = false
        itdBuf = FloatArray(0)
        itdIdx = 0
        itdMaxSamples = 0
        reverbRateHz = 0
        combBuf = emptyArray()
        allpassBuf = emptyArray()
    }

    private companion object {
        const val TAU = 2.0 * PI
    }
}
