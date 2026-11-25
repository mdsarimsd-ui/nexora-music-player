package com.lastwave.app.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Per-song spatialization modes behind the now-playing screen's toggle. */
enum class SpatializationMode(val label: String) {
    OFF("Off"),
    D8("8D"),
    D16("16D"),
}

/**
 * Rotating-pan spatializer for the 8D / 16D toggle. It turns the stereo image
 * around the listener the way popular "8D audio" mixes do: the L/R pair
 * rotates slowly through a full circle, swapping channel phase once per
 * revolution, which reads as the mix passing behind the head. 16D rotates
 * twice as fast and adds a quick left/right alternation on top.
 *
 * Runs on the native DSP output path right after the loudness stage. Only
 * stereo Float32 buffers are touched; anything else passes through
 * untouched, and bit-perfect output skips this stage entirely.
 */
class SpatializationStage {
    @Volatile var mode: SpatializationMode = SpatializationMode.OFF

    private var phaseRad = 0.0
    private var lastSampleRateHz = 0

    /** One full circle every 12 s for 8D, every 6 s for 16D. */
    private val rotationHz: Double
        get() = if (mode == SpatializationMode.D8) 1.0 / 12.0 else 1.0 / 6.0

    /** 16D's fast triangle alternation between the ears, in Hz. */
    private val alternationHz: Double
        get() = if (mode == SpatializationMode.D16) 0.75 else 0.0

    fun process(output: ByteBuffer, channelCount: Int, sampleRateHz: Int) {
        val mode = mode
        if (mode == SpatializationMode.OFF || channelCount != 2 || sampleRateHz <= 0) return
        if (sampleRateHz != lastSampleRateHz) {
            phaseRad = 0.0
            lastSampleRateHz = sampleRateHz
        }
        val originalOrder = output.order()
        try {
            output.order(ByteOrder.LITTLE_ENDIAN)
            val ratePerFrame = 2.0 * PI * rotationHz / sampleRateHz
            val alternationPerFrame = 2.0 * PI * alternationHz / sampleRateHz
            var theta = phaseRad
            var alternationPhase = 0.0
            var pos = output.position()
            val limit = output.limit()
            while (pos + 2 * Float.SIZE_BYTES <= limit) {
                val l = output.getFloat(pos)
                val r = output.getFloat(pos + Float.SIZE_BYTES)
                val cosT = cos(theta)
                val sinT = sin(theta)
                // Rotation of the stereo pair is orthonormal, so peak levels
                // stay unchanged; the phase swap halfway through is what makes
                // the mix feel like it moves behind the listener.
                var outL = l * cosT - r * sinT
                var outR = l * sinT + r * cosT
                if (alternationPerFrame > 0.0) {
                    val pan = abs(2.0 * (alternationPhase / (2.0 * PI) % 1.0) - 1.0)
                    outL *= 0.4 + 0.6 * (1.0 - pan)
                    outR *= 0.4 + 0.6 * pan
                    alternationPhase = (alternationPhase + alternationPerFrame) % (2.0 * PI)
                }
                output.putFloat(pos, outL.toFloat())
                output.putFloat(pos + Float.SIZE_BYTES, outR.toFloat())
                pos += 2 * Float.SIZE_BYTES
                theta += ratePerFrame
            }
            if (theta > TAU_WRAP_GUARD) theta %= 2.0 * PI
            phaseRad = theta
        } finally {
            output.order(originalOrder)
        }
    }

    fun reset() {
        phaseRad = 0.0
        lastSampleRateHz = 0
    }

    private companion object {
        /** Keeps the rotation phase from losing float precision on long sessions. */
        const val TAU_WRAP_GUARD = 1.0e5
    }
}
