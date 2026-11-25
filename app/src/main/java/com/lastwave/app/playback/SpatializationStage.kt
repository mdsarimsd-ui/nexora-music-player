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

    private var panRad = 0.0
    private var alternationPhase = 0.0
    private var reverbRateHz = 0

    private var combDelay = IntArray(0)
    private var combIdx = IntArray(0)
    private var combBuf = emptyArray<FloatArray>()
    private var combStore = FloatArray(0)
    private var allpassDelay = IntArray(0)
    private var allpassIdx = IntArray(0)
    private var allpassBuf = emptyArray<FloatArray>()

    /** One full circle every 10 s for 8D; no circle for 16D. */
    private val rotationHz: Double
        get() = if (mode == SpatializationMode.D8) 1.0 / 8.0 else 0.0

    /** 16D swings between the ears at 0.45 Hz (about every 1.1 s). */
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
        if (mode == SpatializationMode.OFF || channelCount != 2 || sampleRateHz <= 0) return
        ensureReverb(sampleRateHz)
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
                if (rotationPerFrame > 0.0) {
                    // Full circle around the head. The original stereo bed
                    // stays in underneath so the mix never collapses to mono.
                    gL = cos(theta).toFloat()
                    gR = sin(theta).toFloat()
                    theta += rotationPerFrame
                    if (theta > TAU) theta %= TAU
                } else {
                    // Ear to ear: a smooth left-right swing and back, equal
                    // power, with an ear floor so no ear ever drops fully
                    // silent (that dropout was what made 16D feel stuck).
                    val tri = 0.5 - 0.5 * cos(alt)
                    gL = cos(0.5 * PI * tri).toFloat()
                    gR = sin(0.5 * PI * tri).toFloat()
                    alt += alternationPerFrame
                    if (alt > TAU) alt %= TAU
                }
                val earFloor = 0.08f
                val panL = earFloor + (1f - earFloor) * gL.coerceAtLeast(0f)
                val panR = earFloor + (1f - earFloor) * gR.coerceAtLeast(0f)
                val dry = 0.35f
                val panDepth = 0.85f
                val wetMix = 0.30f
                val outL = ((l * dry + mono * panL * panDepth + wet * panL * wetMix) * 0.80f).coerceIn(-1.0f, 1.0f)
                val outR = ((r * dry + mono * panR * panDepth + wet * panR * wetMix) * 0.80f).coerceIn(-1.0f, 1.0f)
                output.putFloat(pos, outL)
                output.putFloat(pos + Float.SIZE_BYTES, outR)
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
        reverbRateHz = 0
        combBuf = emptyArray()
        allpassBuf = emptyArray()
    }

    private companion object {
        const val TAU = 2.0 * PI
    }
}
