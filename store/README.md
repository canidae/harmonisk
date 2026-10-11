# Google Play store assets

Everything needed to fill in the Play Console listing for Harmonisk.

## Listing text — `listing/en-US/`

| File | Play Console field | Limit |
| --- | --- | --- |
| `title.txt` | App name | 30 characters |
| `short_description.txt` | Short description | 80 characters |
| `full_description.txt` | Full description | 4000 characters |

The app is **in beta**, and all three files say so. `title.txt` contains
`Harmonisk (Beta)`; drop the `(Beta)` suffix once the app leaves beta and update
the beta paragraphs at the same time.

## Screenshots — `screenshots/phone/en-US/`

Seven phone screenshots, in the order they should appear in the listing:

1. `01-home.png` — the three modes
2. `02-free-play.png` — Free Play with an imported MIDI melody
3. `03-melody-list.png` — bundled melodies with difficulty
4. `04-sheet-music.png` — a melody ready to play (sheet music + note bars)
5. `05-practice-session.png` — a melody being played, with timing colours
6. `06-tuner.png` — the tuner gauge
7. `07-languages.png` — the in-app language picker

Specs: 1080x1920 (9:16), 24-bit PNG without alpha, 44-88 KB each. Play accepts
2-8 phone screenshots, minimum 320 px, maximum 3840 px, and no side longer than
twice the other. These were captured on a Pixel 7 Pro running app version
`2026.10.11` (versionCode `26101101`).

To regenerate them, build and install a debug APK, then capture at 9:16:

```bash
adb shell wm size 1080x1920
adb exec-out screencap -p > shot.png
adb shell wm size reset
```

## Still required by Google Play

- **App icon** 512x512 PNG (32-bit with alpha).
- **Feature graphic** 1024x500 PNG or JPEG, required for the store listing.
- **Localized listings** — the app itself ships 18 languages, but only the
  `en-US` listing text exists so far. Add `listing/<locale>/` directories to
  localize the store presence as well.
- Data safety form, content rating questionnaire, privacy policy URL, and
  target audience declaration, all filled in the Play Console.
