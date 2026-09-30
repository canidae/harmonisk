# Perfect Pitch

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
android-sdk/platform-tools/adb shell am start -n net.exent.perfectpitch/.MainActivity
```

### Run unit tests
```bash
./gradlew :app:test
```

Test audio fixtures live in `app/src/test/resources/audio`.
