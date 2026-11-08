package net.exent.harmonisk.audio

data class MidiCueNote(
    val channel: Int,
    val key: Int,
    val velocity: Int,
    val startMs: Int,
    val lengthMs: Int
)

data class MidiCue(
    val notes: List<MidiCueNote>,
    val programs: Map<Int, Int>,
    val drumChannels: Set<Int>
)

class MidiCueBuilder internal constructor() {
    private val notes = mutableListOf<MidiCueNote>()
    private val programs = mutableMapOf<Int, Int>()
    private val drums = mutableSetOf<Int>()

    fun program(channel: Int, program: Int) {
        programs[channel] = program
    }

    fun drum(channel: Int = DRUMS) {
        drums += channel
    }

    fun note(channel: Int, key: Int, startMs: Int, lengthMs: Int, velocity: Int = 96) {
        notes += MidiCueNote(channel, key, velocity, startMs, lengthMs)
    }

    fun chord(channel: Int, keys: List<Int>, startMs: Int, lengthMs: Int, velocity: Int = 80) {
        keys.forEach { note(channel, it, startMs, lengthMs, velocity) }
    }

    fun arp(
        channel: Int,
        keys: List<Int>,
        startMs: Int,
        stepMs: Int,
        lengthMs: Int,
        velocity: Int = 90
    ) {
        keys.forEachIndexed { index, key ->
            note(channel, key, startMs + index * stepMs, lengthMs, velocity)
        }
    }

    internal fun build() = MidiCue(notes.sortedBy { it.startMs }, programs.toMap(), drums.toSet())
}

fun midiCue(block: MidiCueBuilder.() -> Unit): MidiCue = MidiCueBuilder().apply(block).build()

private const val C = 0
private const val D = 2
private const val E = 4
private const val F = 5
private const val G = 7
private const val A = 9
private const val B = 11

private const val TIMPANI = 8
private const val DRUMS = 9
private const val HARP = 10
private const val BELLS = 11
private const val CHOIR = 12
private const val BRASS = 13
private const val HORN = 14
private const val EXTRA = 15

private fun n(octave: Int, note: Int): Int = (octave + 1) * 12 + note

object MidiCues {

    val TurnChime = midiCue {
        program(HARP, 46)
        program(BELLS, 9)
        note(HARP, n(3, G), 0, 1_300, 52)
        arp(HARP, listOf(n(4, G), n(4, B), n(5, D), n(5, G), n(5, B), n(6, D)), 0, 80, 900, 78)
        arp(BELLS, listOf(n(5, G), n(6, D), n(6, G)), 40, 120, 700, 48)
    }

    val DiceRattle = midiCue {
        drum()
        val times = listOf(0, 45, 95, 150, 210, 275, 345, 420, 505, 600, 700, 810)
        times.forEachIndexed { index, at ->
            note(DRUMS, if (index % 2 == 0) 76 else 77, at, 40, 68 + index % 4 * 8)
        }
        note(DRUMS, 41, 920, 120, 108)
    }

    val Applause = midiCue {
        program(EXTRA, 126)
        note(EXTRA, n(4, C), 0, 2_600, 80)
    }

    val Whistle = midiCue {
        program(HORN, 78)
        note(HORN, n(6, C), 0, 950, 72)
    }

    val Faerie = midiCue {
        program(HARP, 46)
        program(BELLS, 9)
        program(CHOIR, 52)
        program(BRASS, 112)
        note(HARP, n(3, C), 0, 1_800, 48)
        arp(
            HARP,
            listOf(n(4, C), n(4, E), n(4, G), n(5, C), n(5, E), n(5, G), n(6, C)),
            0,
            105,
            1_400,
            80
        )
        arp(BELLS, listOf(n(6, G), n(7, C), n(7, E)), 420, 160, 900, 42)
        chord(CHOIR, listOf(n(4, C), n(4, E), n(4, G), n(5, C)), 900, 2_400, 34)
        arp(HARP, listOf(n(5, G), n(6, C), n(6, E), n(6, G)), 2_200, 120, 1_000, 70)
        arp(BRASS, listOf(n(7, C), n(7, E), n(7, G)), 2_600, 140, 800, 50)
        arp(
            HARP,
            listOf(n(6, C), n(5, G), n(5, E), n(5, C), n(4, G), n(4, E)),
            5_200,
            90,
            1_000,
            62
        )
        note(HARP, n(4, C), 5_200, 1_400, 50)
    }

    val CastleFanfare = midiCue {
        program(TIMPANI, 47)
        program(HARP, 46)
        program(BELLS, 56)
        program(CHOIR, 52)
        program(BRASS, 61)
        program(HORN, 60)
        drum()
        note(DRUMS, 49, 0, 900, 100)
        note(DRUMS, 49, 1_300, 1_400, 110)
        note(TIMPANI, n(2, C), 0, 700, 105)
        note(TIMPANI, n(2, G), 450, 600, 95)
        note(TIMPANI, n(2, C), 1_300, 1_200, 110)
        arp(HARP, listOf(n(3, C), n(3, G), n(4, C), n(4, E), n(4, G), n(5, C)), 0, 45, 800, 70)
        chord(BRASS, listOf(n(3, C), n(3, G), n(4, C), n(4, E), n(4, G)), 0, 600, 82)
        chord(BRASS, listOf(n(3, F), n(3, A), n(4, C), n(4, F), n(4, A)), 650, 350, 84)
        chord(BRASS, listOf(n(3, G), n(3, B), n(4, D), n(4, G), n(4, B)), 1_000, 300, 88)
        chord(
            BRASS,
            listOf(n(3, C), n(3, G), n(4, C), n(4, E), n(4, G), n(5, C)),
            1_300,
            1_500,
            100
        )
        chord(CHOIR, listOf(n(4, C), n(4, E), n(4, G), n(5, C)), 0, 2_700, 36)
        chord(HORN, listOf(n(4, E), n(4, G)), 0, 1_000, 60)
        chord(HORN, listOf(n(4, F), n(4, A)), 650, 350, 62)
        chord(HORN, listOf(n(4, G), n(4, B)), 1_000, 300, 64)
        chord(HORN, listOf(n(4, E), n(4, G), n(5, C)), 1_300, 1_500, 72)
        arp(BELLS, listOf(n(5, C), n(5, E), n(5, G), n(6, C)), 0, 130, 500, 92)
        note(BELLS, n(6, C), 450, 1_000, 100)
        arp(
            HARP,
            listOf(n(6, C), n(5, G), n(5, E), n(5, C), n(4, G), n(4, E)),
            1_300,
            80,
            1_200,
            65
        )
    }

    val Fanfare = midiCue {
        program(TIMPANI, 47)
        program(HARP, 46)
        program(BELLS, 56)
        program(CHOIR, 52)
        program(BRASS, 61)
        program(HORN, 60)
        drum()
        note(DRUMS, 49, 0, 1_000, 100)
        note(DRUMS, 49, 2_950, 1_600, 110)
        note(TIMPANI, n(2, C), 0, 800, 108)
        note(TIMPANI, n(2, G), 160, 600, 88)
        note(TIMPANI, n(2, C), 320, 600, 96)
        arp(HARP, listOf(n(4, C), n(4, E), n(4, G), n(5, C), n(5, E), n(5, G), n(6, C)), 0, 65, 900, 68)
        chord(BRASS, listOf(n(3, C), n(3, G), n(4, C), n(4, E), n(4, G)), 0, 700, 88)
        chord(CHOIR, listOf(n(4, C), n(4, E), n(4, G), n(5, C)), 0, 2_300, 36)
        chord(HORN, listOf(n(4, E), n(4, G)), 0, 600, 58)
        note(BELLS, n(5, C), 200, 300, 85)
        note(BELLS, n(4, G), 600, 350, 95)
        note(BELLS, n(5, C), 950, 350, 95)
        note(BELLS, n(5, E), 1_300, 350, 95)
        note(BELLS, n(5, G), 1_650, 700, 100)
        chord(BRASS, listOf(n(3, C), n(3, G), n(4, C), n(4, E), n(4, G)), 600, 1_750, 55)
        chord(HORN, listOf(n(4, E), n(4, G)), 600, 1_050, 55)
        chord(HORN, listOf(n(4, G), n(5, C)), 1_650, 700, 62)
        chord(BRASS, listOf(n(3, F), n(3, A), n(4, C), n(4, F), n(4, A)), 2_350, 300, 90)
        chord(HORN, listOf(n(4, F), n(4, A)), 2_350, 300, 64)
        note(TIMPANI, n(2, F), 2_350, 300, 100)
        note(BELLS, n(5, A), 2_350, 250, 95)
        chord(BRASS, listOf(n(3, G), n(3, B), n(4, D), n(4, G), n(4, B)), 2_650, 300, 94)
        chord(HORN, listOf(n(4, G), n(4, B)), 2_650, 300, 66)
        note(TIMPANI, n(2, G), 2_650, 300, 102)
        note(BELLS, n(5, B), 2_650, 250, 98)
        val roll = List(10) { if (it % 2 == 0) n(2, C) else n(2, G) }
        arp(TIMPANI, roll, 2_350, 60, 90, 55)
        chord(
            BRASS,
            listOf(n(3, C), n(3, G), n(4, C), n(4, E), n(4, G), n(5, C)),
            2_950,
            1_800,
            105
        )
        chord(CHOIR, listOf(n(4, C), n(4, E), n(4, G), n(5, C)), 2_950, 1_800, 55)
        chord(HORN, listOf(n(4, E), n(4, G), n(5, C)), 2_950, 1_600, 75)
        note(BELLS, n(6, C), 2_950, 1_600, 100)
        note(TIMPANI, n(2, C), 2_950, 700, 112)
        arp(HARP, listOf(n(5, C), n(5, E), n(5, G), n(6, C), n(6, E), n(6, G), n(7, C)), 2_950, 55, 1_200, 75)
        note(HARP, n(3, C), 2_950, 1_800, 62)
    }
}
