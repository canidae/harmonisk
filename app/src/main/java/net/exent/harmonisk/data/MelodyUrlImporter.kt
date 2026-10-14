package net.exent.harmonisk.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Fetches a melody (or a zip of melodies) over https. Only the user-supplied link is contacted.
 */
object MelodyUrlImporter {
    private const val MAX_BYTES = 32 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val USER_AGENT = "Harmonisk"

    private val MELODY_EXTENSIONS = listOf(".mid", ".midi", ".xml", ".musicxml", ".abc")
    private val HEADER_FILENAME = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)""", RegexOption.IGNORE_CASE)

    data class Download(val name: String, val bytes: ByteArray)

    /** True for the plain https links the app is willing to fetch. */
    fun isSupported(url: String): Boolean = url.trim().startsWith("https://", ignoreCase = true)

    /** True when [bytes] start with the zip local-file, central-directory or end-of-directory header. */
    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2].toInt() in intArrayOf(3, 5, 7)

    /** Fetch [url], following redirects, and name the result after its header hint or the URL path. */
    fun download(url: String): Download {
        require(isSupported(url)) { "Only https:// URLs are supported" }
        val connection = URL(url.trim()).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            Download(
                displayName(url, connection.getHeaderField("Content-Disposition")),
                connection.inputStream.use { readCapped(it) }
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Derive the display name from the Content-Disposition header, falling back to the URL path. */
    fun displayName(url: String, contentDisposition: String? = null): String {
        val raw = contentDisposition?.let { HEADER_FILENAME.find(it)?.groupValues?.get(1) }
            ?: runCatching { URL(url).path }.getOrNull()?.substringAfterLast('/')
            ?: ""
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return decoded.substringBefore('?')
            .let { name -> MELODY_EXTENSIONS.firstOrNull { name.endsWith(it, true) }?.let { name.dropLast(it.length) } ?: name }
            .replace('_', ' ')
            .trim()
            .ifEmpty { "Imported song" }
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) throw IOException("Download exceeds ${MAX_BYTES / (1024 * 1024)} MB")
        }
        return out.toByteArray()
    }
}
