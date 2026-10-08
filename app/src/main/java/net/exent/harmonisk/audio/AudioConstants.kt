package net.exent.harmonisk.audio

object AudioConstants {
    const val SAMPLE_RATE = 44100
    const val BUFFER_SIZE_SAMPLES = 512
    /** Duration of one audio buffer in milliseconds (BUFFER_SIZE_SAMPLES / SAMPLE_RATE). */
    const val FRAME_DURATION_MS = BUFFER_SIZE_SAMPLES * 1000L / SAMPLE_RATE
}
