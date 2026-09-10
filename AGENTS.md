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

## Testing
- There is no permanent instrumentation, device or measurement suite. It grew to twenty-one tests taking seven and a half minutes, of which four minutes was the detector running over rendered performances and tests that play a tone through the speaker and record it in real time: too slow to run, so not run.
- For every change, write temporary tests that
  - exercise the existing functionality the change could have broken, and
  - verify the new behaviour works as intended.
  Put what they measured in the commit message, then remove them: they are a working tool, not a deliverable.
- Temporary instrumentation tests go in `app/src/androidTest` and run on the connected device with
  ```bash
  ./gradlew :app:connectedDebugAndroidTest
  ```
  or a single class with
  ```bash
  android-sdk/platform-tools/adb shell am instrument -w -e class <class> net.exent.perfectpitch.test/androidx.test.runner.AndroidJUnitRunner
  ```
  The dependencies and runner are configured in `app/build.gradle.kts` and stay there.
- Unit tests in `app/src/test` run in seconds and stay.

## Instructions
- If local uncommited changes, find out their purpose and commit them before starting a new task.
- Minimize comments in code, only comment code that isn't self-explanatory.
- All changes should be done on a new branch forked from the main branch unless we're continuing work on a task related to an existing branch
- Attempt to build, install, start and test the app on a connected device before committing.
