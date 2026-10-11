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

Versioning is derived from the build time (UTC). `versionName` is `yyyy.MM.dd` and `versionCode` is `yyMMdd` followed by a three-digit slot that splits the day into 1000 segments:

```
versionCode = yyMMdd * 1000 + (secondsSinceMidnight * 1000 / 86400)
```

For 2026-10-11 19:22 UTC that is `261011806`: `261011` is the date and `806` is the time slot. Slots are about 86 seconds long, so the number always grows with the clock, needs no counter, and nothing resets daily. The only collision case is two builds inside the same ~86-second slot, so leave a couple of minutes between releases. The largest possible value this century is `991231999`, well under Google Play's 2,100,000,000 limit.

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

