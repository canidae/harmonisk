package net.exent.harmonisk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MelodyZipImporterTest {

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
        assertEquals("godnatt.mid", MelodyZipImporter.melodyFileName("folder/godnatt.mid"))
        assertEquals("godnatt.MIDI", MelodyZipImporter.melodyFileName("godnatt.MIDI"))
        assertNull(MelodyZipImporter.melodyFileName("readme.txt"))
        assertNull(MelodyZipImporter.melodyFileName("sub/"))
        assertNull(MelodyZipImporter.melodyFileName("midi"))
    }

    @Test
    fun `melodies are flattened and named after the entry`() {
        val dir = temp.newFolder("kulturskolen")
        val zip = zipOf(
            "sanger/kykkeliky.mid" to byteArrayOf(1, 2, 3),
            "nested/deep/kvirrevitt.MIDI" to byteArrayOf(4, 5),
            "lesmeg.txt" to byteArrayOf(6)
        )

        val melodies = MelodyZipImporter.extract(zip.inputStream(), dir)

        assertEquals(listOf("kykkeliky", "kvirrevitt"), melodies.map { it.name })
        assertEquals(listOf("kvirrevitt.MIDI", "kykkeliky.mid"), dir.list()!!.sorted())
        assertEquals(listOf<Byte>(4, 5), melodies[1].file.readBytes().toList())
    }

    @Test
    fun `zip entries never escape the melody directory`() {
        val dir = temp.newFolder("kulturskolen")

        MelodyZipImporter.extract(zipOf("../../tyven.mid" to byteArrayOf(1)).inputStream(), dir)

        assertEquals(listOf("tyven.mid"), dir.list()!!.sorted())
        assertEquals(listOf("kulturskolen"), dir.parentFile!!.list()!!.sorted())
    }

    @Test
    fun `folder name comes from the zip file name`() {
        assertEquals("kulturskolen", MelodyZipImporter.folderName("kulturskolen.zip"))
        assertEquals("Norske_sanger", MelodyZipImporter.folderName("Norske sanger.ZIP"))
        assertEquals("imported_zip", MelodyZipImporter.folderName("imported.zip"))
        assertEquals("zip", MelodyZipImporter.folderName(".zip"))
    }
}
