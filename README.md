# Harmonisk

An Android application for pitch detection and musical practice.

## SoundFont Attribution

This app uses the **GeneralUser GS** SoundFont by **S. Christian Collins**.

- Website: https://schristiancollins.com/generaluser.php
- License: Creative Commons (see website for details)

The GeneralUser GS SoundFont provides the instrument sounds used for melody
playback and note previews.

## Building

```bash
./gradlew assembleDebug
```

### Install the app to a connected device
```bash
android-sdk/platform-tools/adb install app/build/outputs/apk/debug/app-debug.apk
```

### Start the app on the device
```bash
android-sdk/platform-tools/adb shell am start -n net.exent.harmonisk/.MainActivity
```

### Run unit tests
```bash
./gradlew :app:test
```

Test audio fixtures live in `app/src/test/resources/audio`.

## Release

Versioning is derived from the build time (UTC). `versionName` is `yyyy.MM.dd` and `versionCode` appends the hour and minute to the date:

```
versionCode = ((year - 2000) * 1000 + dayOfYear) * 10000 + hour * 100 + minute
```

For 2026-10-11 19:30 UTC that is `262841930`: `26` is the year, `284` is the day of the year, and `1930` is the time. There is no counter to bump and nothing resets daily — the number always grows with the clock. A plain `yyMMddHHmm` would be over Google Play's 2,100,000,000 limit, so the date is packed as year plus day-of-year instead; this stays valid until roughly 2209. The only collision case is two builds in the same minute, so don't cut two releases back to back.

Release signing reads `keystore.properties` in the project root (gitignored):

```properties
storeFile=harmonisk-upload.jks
storePassword=...
keyAlias=harmonisk
keyPassword=...
```

Build the signed App Bundle for Google Play:

```bash
./gradlew :app:bundleRelease
```

The bundle is written to `app/build/outputs/bundle/release/app-release.aab`. The upload keystore and `keystore.properties` are not tracked by git, so back them up somewhere safe; losing them means you can no longer update the app under this key.

