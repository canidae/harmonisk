## App Description
**Perfect Pitch** is an Android application designed for pitch detection and musical utility.

## Useful commands:
### Build the app
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

### Run Android instrumentation tests (requires connected device)
```bash
./gradlew :app:connectedDebugAndroidTest
```

### View pitch accuracy test results
```bash
android-sdk/platform-tools/adb logcat -d -s PitchAccTest:I
```

## Instructions
- If local uncommited changes, find out their purpose and commit them before starting a new task.
- Minimize comments in code, only comment code that isn't self-explanatory.
- All changes should be done on a new branch forked from the default branch. Split changes into logically grouped commits.
- Attempt to build, install, start and test the app on a connected device before committing.
