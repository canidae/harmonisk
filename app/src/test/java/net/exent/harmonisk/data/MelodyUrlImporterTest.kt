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
    fun `zip archives are recognised by their magic bytes`() {
        assertTrue(MelodyUrlImporter.isZip(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0)))
        assertTrue(MelodyUrlImporter.isZip(byteArrayOf(0x50, 0x4B, 0x05, 0x06)))
        assertFalse(MelodyUrlImporter.isZip(byteArrayOf(0x4D, 0x54, 0x68, 0x64)))
        assertFalse(MelodyUrlImporter.isZip(byteArrayOf(0x50, 0x4B)))
    }
}
