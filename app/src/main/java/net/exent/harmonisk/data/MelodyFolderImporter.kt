package net.exent.harmonisk.data

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

object MelodyFolderImporter {
    private const val IMPORTED_DIR = "imported"
    private const val ZIP_EXTENSION = ".zip"

    data class ExtractedMelody(val name: String, val file: File)

    fun melodyFileName(entryName: String): String? {
        val fileName = entryName.substringAfterLast('/').trim()
        return fileName.takeIf { name -> MelodyNames.extensions.any { name.endsWith(it, true) } }
    }

    fun displayName(fileName: String): String =
        MelodyNames.displayName(fileName.substringBeforeLast('.')).orEmpty()

    fun folderName(name: String): String {
        val base = (MelodyNames.extensions + ZIP_EXTENSION)
            .firstOrNull { name.endsWith(it, true) }
            ?.let { name.dropLast(it.length) }
            ?: name
        val sanitized = safeFileName(base)
        return if (sanitized == MelodyRepository.IMPORTED_FOLDER) "${sanitized}_zip" else sanitized
    }

    fun safeFileName(name: String): String = MelodyNames.safeFileName(name)

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
