package com.lastwave.app.data.autoeq

/**
 * Built-in one-tap presets for the parametric EQ, in the spirit of the
 * preset libraries in NanoSonic and Flick (the references behind this screen).
 *
 * Every preset is expressed as ordinary parametric filters so it can be edited,
 * exported and persisted exactly like a hand-tuned curve. Preamp is derived with
 * AutoEQ's rule so no preset can clip the boost chain.
 */
data class PeqPreset(
    val id: String,
    val name: String,
    val description: String,
    val settings: PeqSettings,
)

object PeqPresets {

    /** The full list, in display order. */
    val all: List<PeqPreset> = listOf(
        preset(
            id = "flat",
            name = "Flat",
            description = "No correction, full range untouched",
            filters = emptyList(),
        ),
        preset(
            id = "bass_boost",
            name = "Bass Boost",
            description = "Deeper, fuller low end",
            filters = listOf(
                PeqFilter(PeqFilterType.LOW_SHELF, 80.0, 5.0, 0.707),
                PeqFilter(PeqFilterType.PEAKING, 150.0, 2.5, 1.0),
            ),
        ),
        preset(
            id = "warm",
            name = "Warm",
            description = "Rich bass, smooth relaxed treble",
            filters = listOf(
                PeqFilter(PeqFilterType.LOW_SHELF, 90.0, 3.5, 0.707),
                PeqFilter(PeqFilterType.PEAKING, 300.0, -1.5, 1.0),
                PeqFilter(PeqFilterType.PEAKING, 2500.0, -1.0, 1.0),
                PeqFilter(PeqFilterType.HIGH_SHELF, 10000.0, 2.0, 0.707),
            ),
        ),
        preset(
            id = "vocal",
            name = "Vocal",
            description = "Voices brought forward and clear",
            filters = listOf(
                PeqFilter(PeqFilterType.PEAKING, 300.0, -1.5, 1.0),
                PeqFilter(PeqFilterType.PEAKING, 1000.0, 2.0, 1.2),
                PeqFilter(PeqFilterType.PEAKING, 3000.0, 3.0, 1.1),
            ),
        ),
        preset(
            id = "bright",
            name = "Bright",
            description = "Air and sparkle on top",
            filters = listOf(
                PeqFilter(PeqFilterType.HIGH_SHELF, 6000.0, 3.5, 0.707),
                PeqFilter(PeqFilterType.PEAKING, 10000.0, 2.5, 1.0),
            ),
        ),
        preset(
            id = "v_shape",
            name = "V-Shape",
            description = "Exciting bass and treble, calmer middle",
            filters = listOf(
                PeqFilter(PeqFilterType.LOW_SHELF, 100.0, 4.0, 0.707),
                PeqFilter(PeqFilterType.PEAKING, 500.0, -2.5, 1.0),
                PeqFilter(PeqFilterType.HIGH_SHELF, 8000.0, 4.0, 0.707),
            ),
        ),
        preset(
            id = "electronic",
            name = "Electronic",
            description = "Punchy club-style low end and shine",
            filters = listOf(
                PeqFilter(PeqFilterType.LOW_SHELF, 60.0, 4.5, 0.707),
                PeqFilter(PeqFilterType.PEAKING, 120.0, 2.0, 0.9),
                PeqFilter(PeqFilterType.PEAKING, 2500.0, 2.0, 1.0),
                PeqFilter(PeqFilterType.HIGH_SHELF, 10000.0, 2.5, 0.707),
            ),
        ),
        preset(
            id = "rock",
            name = "Rock",
            description = "Driving guitars and present drums",
            filters = listOf(
                PeqFilter(PeqFilterType.LOW_SHELF, 90.0, 2.5, 0.707),
                PeqFilter(PeqFilterType.PEAKING, 200.0, 2.0, 1.0),
                PeqFilter(PeqFilterType.PEAKING, 3000.0, 2.0, 1.0),
                PeqFilter(PeqFilterType.HIGH_SHELF, 8000.0, 1.5, 0.707),
            ),
        ),
        preset(
            id = "acoustic",
            name = "Acoustic",
            description = "Natural tone for vocals and guitars",
            filters = listOf(
                PeqFilter(PeqFilterType.LOW_SHELF, 100.0, 2.0, 0.707),
                PeqFilter(PeqFilterType.PEAKING, 2500.0, 2.5, 1.0),
                PeqFilter(PeqFilterType.HIGH_SHELF, 9000.0, 1.5, 0.707),
            ),
        ),
        preset(
            id = "hip_hop",
            name = "Hip-Hop",
            description = "Maximum sub-bass weight, clean top",
            filters = listOf(
                PeqFilter(PeqFilterType.LOW_SHELF, 55.0, 6.0, 0.707),
                PeqFilter(PeqFilterType.PEAKING, 150.0, 3.0, 0.9),
                PeqFilter(PeqFilterType.PEAKING, 1000.0, -1.5, 1.0),
                PeqFilter(PeqFilterType.HIGH_SHELF, 12000.0, 1.5, 0.707),
            ),
        ),
        preset(
            id = "jazz",
            name = "Jazz",
            description = "Smooth mids with airy highs",
            filters = listOf(
                PeqFilter(PeqFilterType.LOW_SHELF, 100.0, 2.5, 0.707),
                PeqFilter(PeqFilterType.PEAKING, 1500.0, -1.5, 1.2),
                PeqFilter(PeqFilterType.PEAKING, 5000.0, 2.0, 1.0),
            ),
        ),
        preset(
            id = "classical",
            name = "Classical",
            description = "Gentle polish for orchestral music",
            filters = listOf(
                PeqFilter(PeqFilterType.LOW_SHELF, 120.0, 1.5, 0.707),
                PeqFilter(PeqFilterType.PEAKING, 4000.0, 1.0, 1.0),
                PeqFilter(PeqFilterType.HIGH_SHELF, 8000.0, 1.5, 0.707),
            ),
        ),
    )

    fun byId(id: String): PeqPreset? = all.firstOrNull { it.id == id }

    private fun preset(id: String, name: String, description: String, filters: List<PeqFilter>): PeqPreset =
        PeqPreset(
            id = id,
            name = name,
            description = description,
            settings = PeqSettings(preampDb = PeqMath.autoPreampDb(filters), filters = filters),
        )
}
