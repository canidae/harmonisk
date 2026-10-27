package net.exent.harmonisk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MelodyUrlImporterTest {

    @Test
    fun `only https links are supported`() {
        assertTrue(MelodyUrlImporter.isSupported("https://example.com/song.mid"))
        assertTrue(MelodyUrlImporter.isSupported("  HTTPS://example.com/song.mid"))
        assertFalse(MelodyUrlImporter.isSupported("http://example.com/song.mid"))
        assertFalse(MelodyUrlImporter.isSupported("ftp://example.com/song.mid"))
        assertFalse(MelodyUrlImporter.isSupported("example.com/song.mid"))
    }

    @Test
    fun `display name comes from the url path`() {
        assertEquals("kykkeliky", MelodyUrlImporter.displayName("https://example.com/songs/kykkeliky.mid"))
        assertEquals("Norske sanger.zip", MelodyUrlImporter.displayName("https://example.com/Norske_sanger.zip"))
        assertEquals("Imported song", MelodyUrlImporter.displayName("https://example.com/"))
        assertEquals("song", MelodyUrlImporter.displayName("https://example.com/song.mid?token=abc"))
    }

    @Test
    fun `display name prefers the content disposition header`() {
        assertEquals(
            "Kvirrevitt",
            MelodyUrlImporter.displayName(
                "https://example.com/download?id=42",
                "attachment; filename=\"Kvirrevitt.musicxml\""
            )
        )
        assertEquals(
            "Blåmann",
            MelodyUrlImporter.displayName(
                "https://example.com/download",
                "attachment; filename*=UTF-8''Bl%C3%A5mann.abc"
            )
        )
    }

    @Test
    fun `google drive share links become direct downloads`() {
        val direct = "https://drive.usercontent.google.com/download?id=1KyBNswCV1gYgAvOkz5aP99G7UlU5dBs9&export=download"
        assertEquals(direct, MelodyUrlImporter.directUrl("https://drive.google.com/file/d/1KyBNswCV1gYgAvOkz5aP99G7UlU5dBs9/view?usp=drive_link"))
        assertEquals(direct, MelodyUrlImporter.directUrl("  https://drive.google.com/drive/u/0/file/d/1KyBNswCV1gYgAvOkz5aP99G7UlU5dBs9/view  "))
        assertEquals(direct, MelodyUrlImporter.directUrl("https://drive.google.com/open?id=1KyBNswCV1gYgAvOkz5aP99G7UlU5dBs9"))
        assertEquals(direct, MelodyUrlImporter.directUrl("https://drive.google.com/uc?export=download&id=1KyBNswCV1gYgAvOkz5aP99G7UlU5dBs9"))
    }

    @Test
    fun `other links are left alone`() {
        assertEquals(
            "https://example.com/song.mid",
            MelodyUrlImporter.directUrl("https://example.com/song.mid")
        )
        assertEquals(
            "https://example.com/download?id=1KyBNswCV1gYgAvOkz5aP99G7UlU5dBs9",
            MelodyUrlImporter.directUrl("https://example.com/download?id=1KyBNswCV1gYgAvOkz5aP99G7UlU5dBs9")
        )
        assertEquals(
            "https://drive.google.com/drive/folders/1KyBNswCV1gYgAvOkz5aP99G7UlU5dBs9",
            MelodyUrlImporter.directUrl("https://drive.google.com/drive/folders/1KyBNswCV1gYgAvOkz5aP99G7UlU5dBs9")
        )
    }

    @Test
    fun `zip archives are recognised by their magic bytes`() {
        assertTrue(MelodyUrlImporter.isZip(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0)))
        assertTrue(MelodyUrlImporter.isZip(byteArrayOf(0x50, 0x4B, 0x05, 0x06)))
        assertFalse(MelodyUrlImporter.isZip(byteArrayOf(0x4D, 0x54, 0x68, 0x64)))
        assertFalse(MelodyUrlImporter.isZip(byteArrayOf(0x50, 0x4B)))
    }
}
