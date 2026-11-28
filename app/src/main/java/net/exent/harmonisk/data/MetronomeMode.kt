package net.exent.harmonisk.data

enum class MetronomeMode {
    OFF,
    LEAD_IN,
    ON;

    companion object {
        fun fromName(name: String?): MetronomeMode = entries.firstOrNull { it.name == name } ?: ON
    }
}
