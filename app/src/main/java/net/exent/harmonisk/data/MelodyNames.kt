package net.exent.harmonisk.data

object MelodyNames {
    val extensions = listOf(".mid", ".midi", ".xml", ".musicxml", ".abc")

    fun baseName(name: String): String =
        extensions.firstOrNull { name.endsWith(it, true) }
            ?.let { name.dropLast(it.length) }
            ?: name

    fun displayName(name: String): String? =
        baseName(name).replace('_', ' ').trim().ifEmpty { null }

    fun safeFileName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifEmpty { "songs" }
}
