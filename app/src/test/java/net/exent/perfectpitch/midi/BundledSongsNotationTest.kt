package net.exent.perfectpitch.midi

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled songs are exported from MuseScore, which cuts every note short by about 5%.
 * Before the gap handling in [MidiToAlphaTex], quantizing such a note ended it a grid step
 * early, so a plain note came out as a long note tied to a sliver, and the sheet music was
 * full of dots. Dotted notes are legitimate, but a dotted note tied to a sixteenth or
 * thirty-second in a row of even notes is the export artefact coming back.
 */
class BundledSongsNotationTest {

    private val melodyRoot = File("src/main/assets/melodies")

    private fun bundled(): List<File> =
        melodyRoot.walkTopDown().filter { it.extension == "mid" }.sorted().toList()

    private fun sliverTies(tex: String): List<String> =
        SLIVER_TIE.findAll(tex).map { it.value }.toList()

    @Test
    fun `bundled songs exist to check`() {
        assertTrue("expected bundled MIDI files in ${melodyRoot.absolutePath}", bundled().size >= 10)
    }

    @Test
    fun `no bundled song is notated as dotted notes tied to slivers`() {
        for (file in bundled()) {
            val track = file.inputStream().use { MidiParser().parse(it, file.name) }
            val tex = MidiToAlphaTex.generate(track)
            assertTrue(
                "${file.name} renders dotted notes tied to slivers: ${sliverTies(tex).take(3)}",
                sliverTies(tex).isEmpty()
            )
        }
    }

    private companion object {
        /**
         * A note of at least seven grid steps (a dotted quarter or longer) tied to a
         * sixteenth or thirty-second is an exported note that was cut short.
         */
        val SLIVER_TIE = Regex("""[A-G][#b]?\d\{t\}\.(?:16|32)""")
    }
}
