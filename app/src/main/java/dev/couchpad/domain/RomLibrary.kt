package dev.couchpad.domain

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** ROM files stored in [dir]. Uploaded names are sanitised so they can never escape it. */
class RomLibrary(private val dir: File) {

    sealed interface SaveResult {
        data class Saved(val name: String) : SaveResult
        data class Rejected(val reason: String) : SaveResult
    }

    fun list(): List<String> = dir.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
        ?.map { it.name }
        ?.sorted()
        .orEmpty()

    fun resolve(name: String?): File? = name?.takeIf { it in list() }?.let { File(dir, it) }

    /** Accepts a ROM file, or a .zip (how homebrew is usually distributed) containing one. */
    @Synchronized
    fun save(originalName: String, bytes: ByteArray): SaveResult {
        if (originalName.lowercase().endsWith(".zip")) {
            val (entryName, entryBytes) = extractRom(bytes)
                ?: return SaveResult.Rejected("The .zip has no .sfc or .smc file")
            return save(entryName, entryBytes)
        }
        val name = sanitize(originalName) ?: return SaveResult.Rejected("Only .sfc, .smc or .zip files are supported")
        if (bytes.isEmpty()) return SaveResult.Rejected("The file is empty")
        if (bytes.size > MAX_BYTES) return SaveResult.Rejected("The file is larger than 8 MB")

        dir.mkdirs()
        val partial = File(dir, "$name.part")
        partial.writeBytes(bytes)
        val target = File(dir, name)
        target.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            return SaveResult.Rejected("Could not store the file")
        }
        return SaveResult.Saved(name)
    }

    /** First ROM entry in the archive, read with a size cap so a zip bomb cannot exhaust memory. */
    private fun extractRom(zip: ByteArray): Pair<String, ByteArray>? = runCatching {
        ZipInputStream(zip.inputStream()).use { stream ->
            generateSequence { stream.nextEntry }
                .firstOrNull { !it.isDirectory && sanitize(it.name) != null }
                ?.let { entry ->
                    val bytes = stream.readNBytesCompat(MAX_BYTES + 1)
                    entry.name to bytes
                }
        }
    }.getOrNull()

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        private val EXTENSIONS = setOf("sfc", "smc")
        private val UNSAFE_CHARS = Regex("[^A-Za-z0-9 ._()-]")

        /** Strips any path, keeps a safe stem and a supported extension, or returns null. */
        fun sanitize(raw: String): String? {
            val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
            val extension = base.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            if (extension !in EXTENSIONS) return null
            val stem = base.substringBeforeLast('.')
                .replace(UNSAFE_CHARS, "_")
                .trim('.', ' ')
                .take(80)
            return if (stem.isEmpty()) null else "$stem.$extension"
        }
    }
}
