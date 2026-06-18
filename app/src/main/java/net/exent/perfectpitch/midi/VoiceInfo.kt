package net.exent.perfectpitch.midi

data class VoiceInfo(
    val globalIndex: Int,
    val trackOrigin: Int,
    val melodyTrack: MelodyTrack
)
