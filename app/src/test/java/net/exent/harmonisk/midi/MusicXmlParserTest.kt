package net.exent.harmonisk.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class MusicXmlParserTest {

    private fun parse(xml: String): List<MelodyTrack> =
        MusicXmlParser.parse(ByteArrayInputStream(xml.toByteArray()), "Test")

    @Test
    fun `a part keeps its key, meter, tempo and notes`() {
        val tracks = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Violin</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes>
                    <divisions>4</divisions>
                    <key><fifths>2</fifths><mode>major</mode></key>
                    <time><beats>4</beats><beat-type>4</beat-type></time>
                  </attributes>
                  <direction><direction-type><metronome><beat-unit>quarter</beat-unit><per-minute>120</per-minute></metronome></direction-type></direction>
                  <note><pitch><step>D</step><octave>4</octave></pitch><duration>4</duration><type>quarter</type></note>
                  <note><pitch><step>F</step><alter>1</alter><octave>4</octave></pitch><duration>4</duration><type>quarter</type></note>
                  <note><pitch><step>A</step><octave>4</octave></pitch><duration>4</duration><type>quarter</type></note>
                  <note><pitch><step>D</step><octave>5</octave></pitch><duration>4</duration><type>quarter</type></note>
                </measure>
              </part>
            </score-partwise>
            """.trimIndent()
        )

        val track = tracks.single()
        assertEquals("Violin", track.name)
        assertEquals(480, track.ticksPerQuarterNote)
        assertEquals(KeySignature(2, false), track.keySignature)
        assertEquals(TimeSignatureEvent(0, 4, 4), track.timeSignatures.single())
        assertEquals(500_000L, track.tempoChanges.single().microsecondsPerBeat)
        assertEquals(
            listOf(62, 66, 69, 74),
            track.notes.map { it.noteNumber }
        )
        assertEquals(listOf(0L, 480L, 960L, 1440L), track.notes.map { it.startTick })
        assertTrue(track.notes.all { it.durationTicks == 480L })
    }

    @Test
    fun `chords share a start and ties are merged`() {
        val tracks = parse(
            """
            <?xml version="1.0"?>
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Piano</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>2</divisions><time><beats>2</beats><beat-type>4</beat-type></time></attributes>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration></note>
                  <note><chord/><pitch><step>E</step><octave>4</octave></pitch><duration>2</duration></note>
                  <note><pitch><step>D</step><octave>4</octave></pitch><duration>2</duration><tie type="start"/></note>
                  <note><pitch><step>D</step><octave>4</octave></pitch><duration>2</duration><tie type="stop"/></note>
                </measure>
              </part>
            </score-partwise>
            """.trimIndent()
        )

        val notes = tracks.single().notes
        assertEquals(3, notes.size)
        assertEquals(60 to 0L, notes.first { it.noteNumber == 60 }.let { it.noteNumber to it.startTick })
        assertEquals(64 to 0L, notes.first { it.noteNumber == 64 }.let { it.noteNumber to it.startTick })
        val d4 = notes.single { it.noteNumber == 62 }
        assertEquals(480L, d4.startTick)
        assertEquals(960L, d4.durationTicks)
    }

    @Test
    fun `repeat barlines are unrolled`() {
        val tracks = parse(
            """
            <?xml version="1.0"?>
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Repeat</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>1</divisions><time><beats>1</beats><beat-type>4</beat-type></time></attributes>
                  <barline location="left"><repeat direction="forward"/></barline>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>1</duration></note>
                </measure>
                <measure number="2">
                  <note><pitch><step>D</step><octave>4</octave></pitch><duration>1</duration></note>
                  <barline location="right"><repeat direction="backward"/></barline>
                </measure>
                <measure number="3">
                  <note><pitch><step>E</step><octave>4</octave></pitch><duration>1</duration></note>
                </measure>
              </part>
            </score-partwise>
            """.trimIndent()
        )

        assertEquals(
            listOf(60L to 0L, 62L to 480L, 60L to 960L, 62L to 1440L, 64L to 1920L),
            tracks.single().notes.map { it.noteNumber.toLong() to it.startTick }
        )
    }

    @Test
    fun `a missing key falls back to detection`() {
        val note = { step: String, octave: Int, alter: String ->
            "<note><pitch><step>$step</step>$alter<octave>$octave</octave></pitch><duration>1</duration></note>"
        }
        val sharp = "<alter>1</alter>"
        val tracks = parse(
            """
            <?xml version="1.0"?>
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Detect</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>1</divisions><time><beats>8</beats><beat-type>4</beat-type></time></attributes>
                  ${note("D", 4, "")}${note("E", 4, "")}${note("F", 4, sharp)}${note("G", 4, "")}
                  ${note("A", 4, "")}${note("B", 4, "")}${note("C", 5, sharp)}${note("D", 5, "")}
                </measure>
              </part>
            </score-partwise>
            """.trimIndent()
        )
        assertEquals(KeySignature(2, false), tracks.single().keySignature)
    }

    @Test
    fun `a score with several parts yields several voices`() {
        val part = { id: String, name: String ->
            """<score-part id="$id"><part-name>$name</part-name></score-part>"""
        }
        val tracks = parse(
            """
            <?xml version="1.0"?>
            <score-partwise version="4.0">
              <part-list>${part("P1", "Lead")}${part("P2", "Bass")}</part-list>
              <part id="P1">
                <measure number="1"><attributes><divisions>1</divisions></attributes>
                <note><pitch><step>G</step><octave>4</octave></pitch><duration>1</duration></note></measure>
              </part>
              <part id="P2">
                <measure number="1"><attributes><divisions>1</divisions></attributes>
                <note><pitch><step>C</step><octave>3</octave></pitch><duration>1</duration></note></measure>
              </part>
            </score-partwise>
            """.trimIndent()
        )
        assertEquals(listOf("Lead", "Bass"), tracks.map { it.name })
        assertEquals(listOf(67), tracks[0].notes.map { it.noteNumber })
        assertEquals(listOf(48), tracks[1].notes.map { it.noteNumber })
    }

    @Test
    fun `a non MusicXML document is rejected`() {
        val tracks = parse("<html><body>nope</body></html>")
        assertTrue(tracks.isEmpty())
    }
}
