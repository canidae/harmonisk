# Mismatching score?
Is the score displayed in the modal after a playthrough identical to the score shown in the list of songs in "Play by Melody"?
I suspect a rounding error, as I'm certain I saw a score of 88 in the modal after a playthrough, then a score of 87 in the list of songs.

# Wrong list of songs when exiting a melody in "Play by Melody"
When user exits a song it will list the directories rather than the directory the user was in before selecting a song to play. Exiting a song should take the user back to the directory where they selected which song to play.

# Better display when pitch is off
If the user plays a pitch too high or too low, it will only display a red/orange color on the string they were supposed to play. This doesn't give the user any input on whether they played a too high or too low pitch.
Can we instead put a mark between the strings, corresponding to how off they are? Perhaps they are even playing a G string when they're supposed to play a D? If so, then we put the marker on top of the G string. Essentially just creating 6 "slots" between each string.
