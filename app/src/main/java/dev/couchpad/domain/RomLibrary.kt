package dev.couchpad.domain

import java.io.File

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

    @Synchronized
    fun save(originalName: String, bytes: ByteArray): SaveResult {
        val name = sanitize(originalName) ?: return SaveResult.Rejected("Only .sfc or .smc files are supported")
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
