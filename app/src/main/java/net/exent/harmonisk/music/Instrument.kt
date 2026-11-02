package net.exent.harmonisk.music

enum class Instrument(
    val midiProgram: Int,
    val midiBank: Int = 0,
    val tuning: List<Int>
) {
    VIOLIN(40, tuning = listOf(55, 62, 69, 76)),
    VIOLA(41, tuning = listOf(48, 55, 62, 69)),
    CELLO(42, tuning = listOf(36, 43, 50, 57));

    val stringLabels: List<String>
        get() = tuning.map { NoteUtils.NOTE_NAMES[it % 12] }

    val highestFirstPositionMidi: Int
        get() = tuning.last() + if (this == CELLO) 5 else 7

    companion object {
        fun fromName(name: String): Instrument =
            entries.firstOrNull { it.name == name } ?: VIOLIN
    }
}
