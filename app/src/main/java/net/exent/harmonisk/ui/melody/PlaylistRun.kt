package net.exent.harmonisk.ui.melody

data class PlaylistSong(
    val id: String,
    val name: String,
    val level: Int
)

data class PlaylistResult(
    val id: String,
    val name: String,
    val level: Int,
    val score: Float,
    val newLevel: Int? = null
)

data class PlaylistRun(
    val playlistId: String,
    val name: String,
    val songs: List<PlaylistSong>,
    val index: Int = 0,
    val results: List<PlaylistResult> = emptyList(),
    val previous: PlaylistResult? = null,
    val pending: Boolean = false,
    val finished: Boolean = false,
    val retrySongIndex: Int? = null
) {
    val next: PlaylistSong? get() = songs.getOrNull(index)
}
