# Changes to song list & title after selecting a song
We're currently not showing the current level for a song in the song list, just the cumulative score. We should also show the current level.
The preview song button in the song list is currently a triangle. This is a bit misleading, replace it with a note similar to the one we can press to preview the song after selecting a song.
After selecting a song, display the song title to the left in the top panel, followed by level and current score. We don't need to show tempo or time signature (we display this in alphaTab). Neither do we need to show percentage of song completion nor how many notes we've played of the total amount of notes.

# MIDI notes aren't converted to clean notes in alphaTab
The MIDI files have a small gap between each notes. This leads to alphaTab rendering a whole note as a half or quarter note with dots and/or slurs. The tools exporting MIDI files tend to make the note 95% the length of its actual length (files are exported from Musescore). For the falling bars this is not an issue, but the notation becomes unreadable.
