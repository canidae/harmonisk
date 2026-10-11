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

Versioning is date-based (UTC): `versionName` is `yyyy.MM.dd` and `versionCode` is `yyMMddnn`, where `nn` is `versionBuild` in `gradle.properties`. Bump `versionBuild` only when making a second release on the same day. Google Play requires `versionCode` to increase with every upload, so never release from an earlier date.

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

