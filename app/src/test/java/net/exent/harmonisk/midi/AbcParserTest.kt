package net.exent.harmonisk.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbcParserTest {

    private fun parse(abc: String): List<MelodyTrack> = AbcParser.parse(abc, "Fallback")

    private fun track(abc: String): MelodyTrack = parse(abc).single()

    @Test
    fun `headers give the title, meter, tempo and key`() {
        val track = track(
            """
            X:1
            T:Scale
            M:4/4
            L:1/4
            Q:1/4=120
            K:C
            C D E F | G A B c |
            """.trimIndent()
        )
        assertEquals("Scale", track.name)
        assertEquals(TimeSignatureEvent(0, 4, 4), track.timeSignatures.single())
        assertEquals(500_000L, track.tempoChanges.single().microsecondsPerBeat)
        assertEquals(KeySignature(0, false), track.keySignature)
    }

    @Test
    fun `notes, octaves and the unit note length are honoured`() {
        val track = track(
            """
            X:1
            M:4/4
            L:1/4
            K:C
            C D E F | G A B c | c' C, |
            """.trimIndent()
        )
        assertEquals(listOf(60, 62, 64, 65, 67, 69, 71, 72, 84, 48), track.notes.map { it.noteNumber })
        assertTrue(track.notes.all { it.durationTicks == 480L })
        assertEquals(listOf(0L, 480L, 960L, 1440L, 1920L, 2400L, 2880L, 3360L, 3840L, 4320L), track.notes.map { it.startTick })
    }

    @Test
    fun `durations scale the unit note length`() {
        val track = track(
            """
            X:1
            M:4/4
            L:1/4
            K:C
            C2 D/2 E3/2 F/ | G//
            """.trimIndent()
        )
        assertEquals(listOf(960L, 240L, 720L, 240L, 120L), track.notes.map { it.durationTicks })
    }

    @Test
    fun `the key signature raises notes until an accidental says otherwise`() {
        val track = track(
            """
            X:1
            M:4/4
            L:1/4
            K:D
            F C | =F =C | ^F _C |
            """.trimIndent()
        )
        assertEquals(listOf(66, 61, 65, 60, 66, 59), track.notes.map { it.noteNumber })
    }

    @Test
    fun `an accidental lasts for the rest of the bar`() {
        val track = track(
            """
            X:1
            M:4/4
            L:1/4
            K:C
            ^F F | F =F |
            """.trimIndent()
        )
        assertEquals(listOf(66, 66, 65, 65), track.notes.map { it.noteNumber })
    }

    @Test
    fun `chords sound together and ties merge into one note`() {
        val track = track(
            """
            X:1
            M:2/4
            L:1/4
            K:C
            [CEG]2 D- | D2 |
            """.trimIndent()
        )
        assertEquals(
            listOf(60, 64, 67),
            track.notes.filter { it.startTick == 0L }.map { it.noteNumber }.sorted()
        )
        val d4 = track.notes.single { it.noteNumber == 62 }
        assertEquals(960L, d4.startTick)
        assertEquals(1440L, d4.durationTicks)
    }

    @Test
    fun `repeats with first and second endings are unrolled`() {
        val track = track(
            """
            X:1
            M:4/4
            L:1/4
            K:C
            |: C D |1 E F :|2 G A | B |
            """.trimIndent()
        )
        assertEquals(listOf(60, 62, 64, 65, 60, 62, 67, 69, 71), track.notes.map { it.noteNumber })
    }

    @Test
    fun `a plain repeat plays twice`() {
        val track = track(
            """
            X:1
            M:4/4
            L:1/4
            K:C
            |: C D E F :|
            """.trimIndent()
        )
        assertEquals(listOf(60, 62, 64, 65, 60, 62, 64, 65), track.notes.map { it.noteNumber })
    }

    @Test
    fun `tuplets and broken rhythm adjust durations`() {
        val track = track(
            """
            X:1
            M:4/4
            L:1/4
            K:C
            (3 C D E | F>G |
            """.trimIndent()
        )
        assertEquals(listOf(320L, 320L, 320L, 720L, 240L), track.notes.map { it.durationTicks })
    }

    @Test
    fun `several voices become several tracks`() {
        val tracks = parse(
            """
            X:1
            T:Duet
            M:4/4
            L:1/4
            K:C
            V:1
            C D E F |
            V:2
            C, D, E, F, |
            """.trimIndent()
        )
        assertEquals(listOf("Duet (1)", "Duet (2)"), tracks.map { it.name })
        assertEquals(listOf(60, 62, 64, 65), tracks[0].notes.map { it.noteNumber })
        assertEquals(listOf(48, 50, 52, 53), tracks[1].notes.map { it.noteNumber })
    }

    @Test
    fun `rests leave gaps but are not notes`() {
        val track = track(
            """
            X:1
            M:4/4
            L:1/4
            K:C
            C z D z |
            """.trimIndent()
        )
        assertEquals(listOf(60L to 0L, 62L to 960L), track.notes.map { it.noteNumber.toLong() to it.startTick })
    }

    @Test
    fun `an ABC file without a key falls back to detection`() {
        val track = track(
            """
            X:1
            M:4/4
            L:1/4
            K:none
            D E ^F G | A B ^c d |
            """.trimIndent()
        )
        assertEquals(KeySignature(2, false), track.keySignature)
    }

    @Test
    fun `a real tune with repeats, voltas, grace notes and slurs parses`() {
        val track = track(
            """
            X:1
            T:The Kesh Jig
            M:6/8
            L:1/8
            Q:3/8=100
            K:G
            |:G2G GAB|ABA ABd|edd gdd|edB dBA|
            G2G GAB|ABA ABd|edd gdB|1 AGF G3:|2 AGF G2B||
            |:d2B d2B|dBd efg|{a}faf gfe|dBA B2c|
            d2B d2B|dBd efg|(faf) gfe|dBA B2G:|
            """.trimIndent()
        )
        assertEquals("The Kesh Jig", track.name)
        assertEquals(TimeSignatureEvent(0, 6, 8), track.timeSignatures.single())
        assertEquals(KeySignature(1, false), track.keySignature)
        // Both repeated sections play twice, the first with its two endings:
        // (5+6+6+6+5+6+6+4) + (5+6+6+6+5+6+6+5) + 2 * (4+6+6+5+4+6+6+5)
        assertEquals(173, track.notes.size)
        assertTrue(track.notes.all { it.durationTicks > 0 })
        assertTrue(track.durationSeconds > 20.0)
    }
}
