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

Four phone screenshots, in the order they should appear in the listing:

1. `01-home.png` — the four modes
2. `02-learn-a-melody.png` — Ode To Joy being played, with timing colours
3. `03-metronome.png` — the metronome running with an accented beat
4. `04-tuner.png` — the tuner showing an in-tune A4

Specs: 1080x1920 (9:16), 24-bit PNG without alpha. Play accepts 2-8 phone
screenshots, minimum 320 px, maximum 3840 px, no side longer than twice the
other, and at most 8 MB per image. These were captured on a Pixel 7 Pro running
app version `2026.10.30` in English, with the status bar set to 09:41 and 100%
battery. The listing is English-only, so keep the app language set to English
when recapturing.

To regenerate them, install a release APK, set the app language to English, then
capture at 9:16:

```bash
adb shell wm size 1080x1920
adb exec-out screencap -p > shot.png
adb shell wm size reset
```

The status bar can be set up with Android demo mode:

```bash
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi hide -e mobile hide
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
```

Exit demo mode afterwards with `-e command exit` and
`settings put global sysui_demo_allowed 0`.

## Graphics — `graphics/`

| File | Play Console field | Spec |
| --- | --- | --- |
| `app-icon-512.png` | App icon | 512x512, 32-bit PNG with alpha |
| `feature-graphic-1024x500.png` | Feature graphic | 1024x500 PNG |

Both derive from the same artwork as the launcher icon
(`app/src/main/res/drawable/ic_launcher_background.xml` and
`ic_launcher_foreground.xml`): a purple-to-teal gradient with white beamed
eighth notes. The feature graphic also shows `02-learn-a-melody.png` in a phone
mockup, so regenerate it when the practice screen changes. Keep the icon in sync
if the launcher artwork changes.

## Still required by Google Play

- **Localized listings** — the app itself ships English and Norwegian (Bokmål),
  but only the `en-US` listing text exists so far. Add `listing/<locale>/`
  directories to localize the store presence as well.
- Data safety form, content rating questionnaire, privacy policy URL, and
  target audience declaration, all filled in the Play Console.
