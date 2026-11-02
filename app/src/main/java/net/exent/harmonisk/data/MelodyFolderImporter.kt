package net.exent.harmonisk.data

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

object MelodyFolderImporter {
    private const val IMPORTED_DIR = "imported"
    private const val ZIP_EXTENSION = ".zip"
    private val MELODY_EXTENSIONS = listOf(".mid", ".midi", ".xml", ".musicxml", ".abc")

    data class ExtractedMelody(val name: String, val file: File)

    fun melodyFileName(entryName: String): String? {
        val fileName = entryName.substringAfterLast('/').trim()
        return fileName.takeIf { name -> MELODY_EXTENSIONS.any { name.endsWith(it, true) } }
    }

    fun displayName(fileName: String): String =
        fileName.substringBeforeLast('.').replace('_', ' ').trim()

    fun folderName(name: String): String {
        val base = (MELODY_EXTENSIONS + ZIP_EXTENSION)
            .firstOrNull { name.endsWith(it, true) }
            ?.let { name.dropLast(it.length) }
            ?: name
        val sanitized = safeFileName(base)
        return if (sanitized == MelodyRepository.IMPORTED_FOLDER) "${sanitized}_zip" else sanitized
    }

    fun safeFileName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifEmpty { "songs" }

    fun directory(filesDir: File, folder: String): File = File(filesDir, "$IMPORTED_DIR/$folder")

    fun extract(input: InputStream, dir: File): List<ExtractedMelody> {
        dir.mkdirs()
        val melodies = mutableListOf<ExtractedMelody>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                melodyFileName(entry.name)?.let { fileName ->
                    val target = File(dir, fileName)
                    target.outputStream().use { zip.copyTo(it) }
                    melodies.add(ExtractedMelody(displayName(fileName), target))
                }
                zip.closeEntry()
            }
        }
        return melodies
    }
}
