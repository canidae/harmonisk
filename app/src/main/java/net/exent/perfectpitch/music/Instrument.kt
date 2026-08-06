package net.exent.perfectpitch.music

enum class Instrument(
    val displayName: String,
    val midiProgram: Int,
    val midiBank: Int = 0,
    val tuning: List<Int>? = null
) {
    PIANO("Piano", 0),
    VIOLIN("Violin", 40, tuning = listOf(55, 62, 69, 76)),
    CELLO("Cello", 42, tuning = listOf(36, 43, 50, 57));

    companion object {
        fun fromName(name: String): Instrument =
            entries.firstOrNull { it.name == name } ?: PIANO
    }
}
