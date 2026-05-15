# AGENTS.md

## App Description
**Perfect Pitch** is an Android application designed for pitch detection and musical utility.

## Notable Files
- `app/src/main/AndroidManifest.xml`: Android manifest containing app configurations and permissions (e.g., `RECORD_AUDIO`).
- `app/src/main/java/com/example/perfect_pitch/perfect_pitch/MainActivity.kt`: The main entry point of the application.
- `app/src/main/java/com/example/perfect_pitch/perfect_pitch/Pitch_component.control/Pitch_component.control.kt`: Core component for pitch processing/control.
- `app/src/main/java/com/example/perfect_pitch/perfect_pitch/ui/ui.kt`: UI implementation and layout logic.
- `gradlew`: Gradle wrapper script for managing builds.
- `android-sdk/platform-tools/adb`: Path to the Android Debug Bridge (ADB) used for deployment.

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
