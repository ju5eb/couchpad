package dev.couchpad.boxart

import dev.couchpad.domain.BoxArtNames
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Box art for ROMs from libretro-thumbnails, downloaded once and kept in [dir].
 *
 * A definitive "not found" for every candidate name is remembered for a few
 * days so the lobby does not hammer the server; network errors are not, so a
 * TV without internet simply tries again next time.
 */
class BoxArtStore(private val dir: File, private val baseUrl: String = DEFAULT_BASE_URL) {

    private sealed interface Download {
        class Found(val bytes: ByteArray) : Download
        data object NotFound : Download
        data object Failed : Download
    }

    fun cached(rom: String): File? = artFile(rom).takeIf { it.isFile }

    /** Blocking network call: run it off the main thread. */
    fun fetch(rom: String): File? {
        cached(rom)?.let { return it }
        val miss = File(dir, "$rom.miss")
        if (miss.isFile && System.currentTimeMillis() - miss.lastModified() < RETRY_AFTER_MS) return null

        dir.mkdirs()
        var failed = false
        for (name in BoxArtNames.candidates(rom)) {
            when (val result = download("$baseUrl/${encode(name)}.png")) {
                is Download.Found -> return save(rom, result.bytes).also { miss.delete() }
                Download.NotFound -> Unit
                Download.Failed -> failed = true
            }
        }
        if (!failed) miss.writeText(BoxArtNames.candidates(rom).joinToString("\n"))
        return null
    }

    private fun save(rom: String, bytes: ByteArray): File {
        val target = artFile(rom)
        val partial = File(dir, "$rom.part")
        partial.writeBytes(bytes)
        target.delete()
        partial.renameTo(target)
        return target
    }

    private fun download(url: String): Download = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        try {
            when {
                connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND -> Download.NotFound
                connection.responseCode != HttpURLConnection.HTTP_OK -> Download.Failed
                connection.contentType?.startsWith("image/") != true -> Download.NotFound
                else -> connection.inputStream.use { it.readCapped(MAX_BYTES) }
                    ?.let { Download.Found(it) } ?: Download.NotFound
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: java.io.IOException) {
        Download.Failed
    }

    /** Null if the stream is larger than [limit]. */
    private fun InputStream.readCapped(limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) return out.toByteArray()
            out.write(buffer, 0, read)
            if (out.size() > limit) return null
        }
    }

    private fun artFile(rom: String) = File(dir, "$rom.png")

    private fun encode(name: String) = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    companion object {
        const val DEFAULT_BASE_URL =
            "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Super_Nintendo_Entertainment_System/master/Named_Boxarts"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val RETRY_AFTER_MS = 3L * 24 * 60 * 60 * 1000
    }
}
