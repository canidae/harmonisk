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
    private const val DRIVE_DOWNLOAD = "https://drive.usercontent.google.com/download?id="

    private val HEADER_FILENAME = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)""", RegexOption.IGNORE_CASE)
    private val DRIVE_PATH_ID = Regex("""drive\.google\.com/(?:drive/(?:u/\d+/)?)?file/d/([A-Za-z0-9_-]{10,})""")
    private val DRIVE_QUERY_ID = Regex("""[?&]id=([A-Za-z0-9_-]{10,})""")
    private val CONFIRM_TOKEN = Regex("""[?&]confirm=([0-9A-Za-z_-]+)""")

    data class Download(val name: String, val bytes: ByteArray)

    /** True for the plain https links the app is willing to fetch. */
    fun isSupported(url: String): Boolean = url.trim().startsWith("https://", ignoreCase = true)

    /** True when [bytes] start with the zip local-file, central-directory or end-of-directory header. */
    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2].toInt() in intArrayOf(3, 5, 7)

    /** Rewrite a Google Drive share link to its direct download, leaving other links untouched. */
    fun directUrl(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.contains("drive.google.com/")) return trimmed
        val id = DRIVE_PATH_ID.find(trimmed)?.groupValues?.get(1)
            ?: DRIVE_QUERY_ID.find(trimmed)?.groupValues?.get(1)
            ?: return trimmed
        return "$DRIVE_DOWNLOAD$id&export=download"
    }

    /** Fetch [url], following redirects, and name the result after its header hint or the URL path. */
    fun download(url: String): Download {
        require(isSupported(url)) { "Only https:// URLs are supported" }
        var target = directUrl(url)
        repeat(2) {
            val connection = URL(target).openConnection() as HttpURLConnection
            val finalUrl: String
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
                finalUrl = connection.url.toString()
                val body = connection.inputStream.use { readCapped(it) }
                if (connection.contentType?.startsWith("text/html", true) != true) {
                    return Download(displayName(finalUrl, connection.getHeaderField("Content-Disposition")), body)
                }
                val confirm = CONFIRM_TOKEN.find(String(body, Charsets.UTF_8))?.groupValues?.get(1)
                    ?: throw IOException("Server returned a web page instead of a file")
                target = "$finalUrl&confirm=$confirm"
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Server returned a web page instead of a file")
    }

    /** Derive the display name from the Content-Disposition header, falling back to the URL path. */
    fun displayName(url: String, contentDisposition: String? = null): String {
        val raw = contentDisposition?.let { HEADER_FILENAME.find(it)?.groupValues?.get(1) }
            ?: runCatching { URL(url).path }.getOrNull()?.substringAfterLast('/')
            ?: ""
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return MelodyNames.displayName(decoded.substringBefore('?')) ?: "Imported song"
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
