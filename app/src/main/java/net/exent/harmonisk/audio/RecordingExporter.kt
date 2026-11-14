package net.exent.harmonisk.audio

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.exent.harmonisk.R
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object RecordingExporter {
    private const val DOWNLOAD_FOLDER = "Harmonisk"

    fun fileName(melodyName: String?, extension: String): String {
        val base = melodyName?.trim()?.takeIf { it.isNotEmpty() } ?: "Harmonisk"
        val safe = base.replace(Regex("""[\\/:*?"<>|]"""), "-").take(60)
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        val suffix = if (extension.equals("wav", ignoreCase = true)) "wav" else "m4a"
        return "$safe-$stamp.$suffix"
    }

    private fun mimeType(file: File): String =
        if (file.extension.equals("wav", ignoreCase = true)) "audio/wav" else "audio/mp4"

    suspend fun share(context: Context, file: File, displayName: String): Boolean {
        val shared = withContext(Dispatchers.IO) {
            runCatching {
                val shareDir = File(context.cacheDir, "shared").apply { mkdirs() }
                shareDir.listFiles()?.forEach { it.delete() }
                File(shareDir, displayName).also { file.copyTo(it, overwrite = true) }
            }.getOrNull()
        } ?: return false
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shared)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeType(file)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, displayName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, context.getString(R.string.recording_share_title)))
        }.isSuccess
    }

    suspend fun download(context: Context, file: File, displayName: String): Boolean =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType(file))
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_FOLDER"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: error("No output stream for $uri")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                false
            }
        }
}
