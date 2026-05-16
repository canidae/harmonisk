## App Description
**Perfect Pitch** is an Android application designed for pitch detection and musical utility.

## Useful Commands

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
android-sdk/platform-tools/adb shell am start -n com.perfectpitch/.MainActivity
```
