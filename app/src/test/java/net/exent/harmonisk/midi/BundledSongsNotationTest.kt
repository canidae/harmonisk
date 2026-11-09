package net.exent.harmonisk.midi

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledSongsNotationTest {

    private val melodyRoot = File("src/main/assets/melodies")

    private fun bundled(): List<File> =
        melodyRoot.walkTopDown().filter { it.extension == "mid" }.sorted().toList()

    private fun sliverTies(tex: String): List<String> =
        SLIVER_TIE.findAll(tex).map { it.value }.toList()

    @Test
    fun `bundled songs exist to check`() {
        assertTrue("expected bundled MIDI files in ${melodyRoot.absolutePath}", bundled().size >= 5)
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
        val SLIVER_TIE = Regex("""[A-G][#b]?\d\{t\}\.(?:16|32)""")
    }
}
