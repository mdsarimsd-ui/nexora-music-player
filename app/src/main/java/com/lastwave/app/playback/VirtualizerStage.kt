package com.lastwave.app.playback

import com.lastwave.app.data.local.EqualizerSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Musicolet-style 3D surround (virtualizer) for the native Float32 chain.
 *
 * Widens the stereo image with mid/side expansion plus a short Haas
 * crossfeed between the ears, scaled by the equalizer sheet's strength
 * (0-100). Runs before the per-song 8D / 16D spatializer; mono sources pass
 * through untouched, and bit-perfect output skips the whole DSP chain.
 */
class VirtualizerStage(
    settings: Flow<EqualizerSettings>,
    scope: CoroutineScope,
) {
    @Volatile private var amount = 0f
    private var haasSamples = 0
    private var delayL = FloatArray(0)
    private var delayR = FloatArray(0)
    private var pos = 0

    init {
        scope.launch {
            settings.collect { s ->
                amount = if (s.virtualizerEnabled) s.virtualizerStrength.coerceIn(0, 100) / 100f else 0f
            }
        }
    }

    fun process(output: ByteBuffer, channelCount: Int, sampleRateHz: Int) {
        val strength = amount
        if (strength <= 0f || channelCount != 2 || sampleRateHz <= 0) return
        val haas = (sampleRateHz * 0.014f).toInt().coerceAtLeast(1)
        if (haasSamples != haas) {
            delayL = FloatArray(haas)
            delayR = FloatArray(haas)
            pos = 0
            haasSamples = haas
        }
        val widen = 1f + strength * 1.2f
        val feed = strength * 0.28f
        val originalOrder = output.order()
        try {
            output.order(ByteOrder.LITTLE_ENDIAN)
            var p = output.position()
            val limit = output.limit()
            while (p + 2 * Float.SIZE_BYTES <= limit) {
                val l = output.getFloat(p)
                val r = output.getFloat(p + Float.SIZE_BYTES)
                val dl = delayL[pos]
                val dr = delayR[pos]
                delayL[pos] = r
                delayR[pos] = l
                pos = (pos + 1) % haas
                val mid = (l + r) * 0.5f
                val side = (l - r) * 0.5f
                val outL = (mid + side * widen + dr * feed).coerceIn(-1.2f, 1.2f)
                val outR = (mid - side * widen + dl * feed).coerceIn(-1.2f, 1.2f)
                output.putFloat(p, outL)
                output.putFloat(p + Float.SIZE_BYTES, outR)
                p += 2 * Float.SIZE_BYTES
            }
        } finally {
            output.order(originalOrder)
        }
    }
}
