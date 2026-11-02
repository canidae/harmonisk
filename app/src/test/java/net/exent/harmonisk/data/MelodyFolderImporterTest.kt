package net.exent.harmonisk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MelodyFolderImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `only midi entries are melodies`() {
        assertEquals("godnatt.mid", MelodyFolderImporter.melodyFileName("folder/godnatt.mid"))
        assertEquals("godnatt.MIDI", MelodyFolderImporter.melodyFileName("godnatt.MIDI"))
        assertNull(MelodyFolderImporter.melodyFileName("readme.txt"))
        assertNull(MelodyFolderImporter.melodyFileName("sub/"))
        assertNull(MelodyFolderImporter.melodyFileName("midi"))
    }

    @Test
    fun `melodies are flattened and named after the entry`() {
        val dir = temp.newFolder("kulturskolen")
        val zip = zipOf(
            "sanger/kykkeliky.mid" to byteArrayOf(1, 2, 3),
            "nested/deep/kvirrevitt.MIDI" to byteArrayOf(4, 5),
            "lesmeg.txt" to byteArrayOf(6)
        )

        val melodies = MelodyFolderImporter.extract(zip.inputStream(), dir)

        assertEquals(listOf("kykkeliky", "kvirrevitt"), melodies.map { it.name })
        assertEquals(listOf("kvirrevitt.MIDI", "kykkeliky.mid"), dir.list()!!.sorted())
        assertEquals(listOf<Byte>(4, 5), melodies[1].file.readBytes().toList())
    }

    @Test
    fun `zip entries never escape the melody directory`() {
        val dir = temp.newFolder("kulturskolen")

        MelodyFolderImporter.extract(zipOf("../../tyven.mid" to byteArrayOf(1)).inputStream(), dir)

        assertEquals(listOf("tyven.mid"), dir.list()!!.sorted())
        assertEquals(listOf("kulturskolen"), dir.parentFile!!.list()!!.sorted())
    }

    @Test
    fun `folder name comes from the source file name`() {
        assertEquals("kulturskolen", MelodyFolderImporter.folderName("kulturskolen.zip"))
        assertEquals("Norske_sanger", MelodyFolderImporter.folderName("Norske sanger.ZIP"))
        assertEquals("imported_zip", MelodyFolderImporter.folderName("imported.zip"))
        assertEquals("songs", MelodyFolderImporter.folderName(".zip"))
        assertEquals("Kultuskolen-fiolin", MelodyFolderImporter.folderName("Kultuskolen-fiolin.abc"))
        assertEquals("Sanger_2_0", MelodyFolderImporter.folderName("Sanger 2.0"))
    }

    @Test
    fun `file names are sanitized without losing readable words`() {
        assertEquals("Rock_n_roll_med_mellomspill", MelodyFolderImporter.safeFileName("Rock'n roll (med mellomspill)"))
        assertEquals("songs", MelodyFolderImporter.safeFileName("!!!"))
    }
}
