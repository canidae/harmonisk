# Perfect Pitch — Implementation Plan

## Overview

**Perfect Pitch** is an Android app (Kotlin + Jetpack Compose) for training musicians to play with accurate pitch. Inspired by Trala and Yousician, it features three modes: Free Play, Play by Melody (Synthesia-style), and Tune Instrument. The app is instrument-agnostic and supports configurable concert pitch (A4 reference).

### Why Instrument-Agnostic Works

The YIN pitch detection algorithm works on the fundamental frequency of any monophonic sound. A pitch is a pitch — 440 Hz is A4 regardless of whether it comes from a violin, guitar, or voice. The only practical differences between instruments are:

- **Frequency range**: A bass guitar goes down to ~40 Hz while a piccolo is above 500 Hz. We handle this by using a buffer large enough for the lowest expected frequency (~80 Hz, covering all common instruments).
- **Overtone strength**: Some instruments have strong harmonics that could cause "octave errors" (detecting 880 Hz instead of 440 Hz). YIN's cumulative mean normalization handles this well without instrument-specific tuning.
- **Attack transients**: Percussive instruments (piano) have noisier attacks than bowed instruments (violin). We handle this with a brief settling period after note onset.

No instrument-specific configuration is needed. A single well-tuned YIN implementation with appropriate buffer size and threshold settings works for all monophonic instruments.

### Key Technical Decisions

| Decision | Choice | Rationale |
|---|---|---|
| UI Framework | Jetpack Compose | Modern, declarative, less boilerplate |
| Min SDK | API 29 (Android 10) | Simpler permissions, modern APIs |
| Pitch Detection | YIN algorithm (custom Kotlin impl) | Best accuracy/performance balance for monophonic pitch; no heavy JNI deps |
| Audio Capture | Android `AudioRecord` | Low-latency, direct PCM access |
| MIDI Parsing | Custom lightweight parser | MIDI format is simple; avoids `javax.sound.midi` (unavailable on Android) |
| Architecture | MVVM + Kotlin Coroutines/Flow | Standard modern Android pattern |
| DI | Hilt (Dagger) | Google-recommended for Android |
| Navigation | Jetpack Navigation Compose | Seamless Compose integration |
| Build System | Gradle (Kotlin DSL) | Android standard |
| Scrolling View | Custom Canvas Composable | Synthesia-style falling notes require custom rendering |
| Testing | Minimal — core pitch detection | Pitch detection correctness is critical; UI is visual/manual |

### Project Structure (Target)

```
app/
├── src/main/
│   ├── java/com/perfectpitch/
│   │   ├── PerfectPitchApp.kt              # Application class (Hilt)
│   │   ├── MainActivity.kt                 # Single-activity host
│   │   ├── audio/
│   │   │   ├── AudioCaptureService.kt       # AudioRecord wrapper
│   │   │   ├── PitchDetector.kt             # YIN algorithm
│   │   │   ├── PitchProcessor.kt            # Pipeline: capture → detect → emit
│   │   │   └── AudioUtils.kt               # Helpers (dB, RMS, windowing)
│   │   ├── midi/
│   │   │   ├── MidiParser.kt                # Parse .mid files
│   │   │   ├── MidiEvent.kt                 # Data classes for MIDI events
│   │   │   └── MelodyTrack.kt              # Processed melody representation
│   │   ├── music/
│   │   │   ├── NoteUtils.kt                 # Frequency ↔ note name, cents calc
│   │   │   ├── PitchResult.kt               # Data class for detection results
│   │   │   └── ScoringEngine.kt             # Accuracy scoring logic
│   │   ├── ui/
│   │   │   ├── theme/
│   │   │   │   ├── Theme.kt
│   │   │   │   ├── Color.kt
│   │   │   │   └── Type.kt
│   │   │   ├── navigation/
│   │   │   │   └── NavGraph.kt
│   │   │   ├── home/
│   │   │   │   ├── HomeScreen.kt
│   │   │   │   └── HomeViewModel.kt
│   │   │   ├── freeplay/
│   │   │   │   ├── FreePlayScreen.kt
│   │   │   │   └── FreePlayViewModel.kt
│   │   │   ├── melody/
│   │   │   │   ├── MelodyScreen.kt
│   │   │   │   ├── MelodyViewModel.kt
│   │   │   │   ├── SynthesiaCanvas.kt       # Scrolling note view
│   │   │   │   └── ScoreResultScreen.kt
│   │   │   ├── tuner/
│   │   │   │   ├── TunerScreen.kt
│   │   │   │   └── TunerViewModel.kt
│   │   │   ├── settings/
│   │   │   │   ├── SettingsScreen.kt
│   │   │   │   └── SettingsViewModel.kt
│   │   │   └── components/
│   │   │       ├── PitchGauge.kt            # Reusable pitch indicator
│   │   │       ├── NoteDisplay.kt           # Note name + octave display
│   │   │       ├── PermissionHandler.kt     # Mic permission UI
│   │   │       └── AccuracyIndicator.kt     # Color-coded accuracy feedback
│   │   └── data/
│   │       ├── SettingsRepository.kt        # DataStore preferences
│   │       └── MelodyRepository.kt          # MIDI file management
│   ├── res/
│   │   ├── values/
│   │   │   ├── strings.xml
│   │   │   └── themes.xml
│   │   ├── mipmap-*/                        # App icons
│   │   └── raw/                             # Bundled sample MIDI files
│   └── AndroidManifest.xml
├── src/test/
│   └── java/com/perfectpitch/
│       ├── audio/
│       │   └── PitchDetectorTest.kt         # YIN algorithm unit tests
│       └── music/
│           ├── NoteUtilsTest.kt             # Note conversion tests
│           └── ScoringEngineTest.kt         # Scoring logic tests
└── build.gradle.kts
```

---

## Phase 1: Environment Setup & Project Scaffolding

**Goal:** Install all required tooling and create a buildable Android project that runs on a physical device.

### 1.1 — Install Android SDK & Command-Line Tools
- Download and install Android command-line tools (cmdline-tools)
- Install platform SDK (API 35 — latest stable for compilation, min SDK 29)
- Install build-tools
- Accept all SDK licenses
- Set `ANDROID_HOME` environment variable
- Verify `sdkmanager`, `avdmanager` are accessible

### 1.2 — Install ADB for Device Deployment
- Install platform-tools (includes `adb`)
- Verify `adb devices` can detect connected phone (user will plug in phone)
- Enable USB debugging instructions (note for user)

### 1.3 — Create Android Project Structure
- Create root `build.gradle.kts` and `settings.gradle.kts`
- Create `app/build.gradle.kts` with:
  - Kotlin 2.0+ and Compose compiler plugin
  - `minSdk = 29`, `targetSdk = 35`, `compileSdk = 35`
  - Dependencies: Compose BOM, Material 3, Navigation Compose, Hilt, Coroutines, DataStore, Activity Compose
  - Test dependencies: JUnit 5, Mockk
- Create `gradle.properties` and `local.properties` (sdk path)
- Create Gradle wrapper (`gradlew`, `gradle-wrapper.jar`, `gradle-wrapper.properties`)
- Create `AndroidManifest.xml` with `RECORD_AUDIO` permission
- Create minimal `MainActivity.kt` with empty Compose content
- Create `PerfectPitchApp.kt` (Hilt Application class)

### 1.4 — Verify Build & Deploy
- Run `./gradlew assembleDebug` and verify APK is produced
- (Optional) If device is connected: `adb install` the debug APK
- Confirm app launches (blank screen is fine at this stage)

---

## Phase 2: Core Audio Engine — Pitch Detection

**Goal:** Build the real-time audio capture and pitch detection pipeline that all three modes depend on.

### 2.1 — Audio Capture Service
- Implement `AudioCaptureService` using `AudioRecord`
  - Sample rate: 44100 Hz
  - Channel: MONO
  - Encoding: PCM 16-bit
  - Buffer size: at least 4096 samples (for YIN at lowest guitar frequencies ~80 Hz)
  - Expose a Kotlin `Flow<FloatArray>` of audio chunks
  - Handle lifecycle (start/stop recording)
  - Proper resource cleanup

### 2.2 — YIN Pitch Detection Algorithm
- Implement the YIN algorithm in pure Kotlin:
  1. **Difference function** — compute the squared difference for each lag τ
  2. **Cumulative mean normalized difference** — normalize to remove bias
  3. **Absolute threshold** — find first dip below threshold (0.10–0.15)
  4. **Parabolic interpolation** — refine the lag estimate for sub-sample accuracy
  5. Convert lag to frequency: `f = sampleRate / lag`
- Parameters: threshold (configurable), buffer size
- Return `null` when no clear pitch is detected (noise/silence)
- Handle edge cases: silence detection via RMS threshold, very high/low frequencies

### 2.3 — Note Utilities
- Implement `NoteUtils`:
  - `frequencyToNote(freq: Float, refA4: Float = 440f)` → `NoteInfo(name, octave, centsOff)`
  - `noteToFrequency(noteName: String, octave: Int, refA4: Float)` → `Float`
  - `frequencyToCents(detected: Float, reference: Float)` → `Float`
  - Note names array: C, C♯, D, D♯, E, F, F♯, G, G♯, A, A♯, B
  - MIDI note number ↔ frequency conversion
- Support configurable A4 reference (default 440 Hz)

### 2.4 — Pitch Processor Pipeline
- Implement `PitchProcessor` that combines capture + detection:
  - Collect audio chunks from `AudioCaptureService`
  - Run YIN on each chunk (on `Dispatchers.Default`)
  - Emit `PitchResult(frequency, noteName, octave, centsOff, confidence, rmsDb)` as a `Flow`
  - Apply basic smoothing (moving median of last 3 readings to reduce jitter)
  - Filter out results below minimum RMS (silence)

### 2.5 — Unit Tests for Pitch Detection
- Test YIN with synthetic sine waves at known frequencies (A4=440, C4=261.63, etc.)
- Test `NoteUtils` conversions (frequency → note → frequency round-trip)
- Test cents calculation accuracy
- Test silence detection (zero/low-amplitude input)
- Test edge cases: very low (50 Hz) and very high (4000 Hz) frequencies

---

## Phase 3: App Architecture, Navigation & Theming

**Goal:** Set up the app shell — navigation between screens, theming, and microphone permission handling.

### 3.1 — Theme & Styling
- Define color palette in `Color.kt`:
  - Primary: vibrant accent for in-tune indicators
  - Error/Warn: red/orange for out-of-tune
  - Success: green for good pitch
  - Background: dark theme (easier on eyes during practice)
- Define typography in `Type.kt` (large note display, score numbers)
- Create `Theme.kt` with Material 3 dark theme
- Support both dark and light themes

### 3.2 — Navigation Graph
- Implement `NavGraph.kt` with routes:
  - `home` — main menu
  - `freeplay` — free play mode
  - `melody` — melody mode (MIDI file selection)
  - `melody/play/{fileUri}` — active melody playing
  - `melody/results` — score results after playing
  - `tuner` — instrument tuner
  - `settings` — app settings
- Set up `NavHost` in `MainActivity`

### 3.3 — Home Screen
- Create `HomeScreen.kt`:
  - App title/logo
  - Three large mode selection cards/buttons:
    1. 🎵 Free Play
    2. 🎼 Play by Melody
    3. 🎸 Tune Instrument
  - Settings gear icon in top bar
- Create `HomeViewModel.kt` (minimal, may store last-used mode)

### 3.4 — Microphone Permission Handling
- Create `PermissionHandler.kt` composable:
  - Request `RECORD_AUDIO` permission using Accompanist or Activity Result API
  - Show rationale dialog explaining why mic is needed
  - Handle permanent denial (direct user to app settings)
  - Each audio-using screen wraps content in permission check

### 3.5 — Hilt DI Setup
- Create Hilt modules:
  - `AudioModule` — provide `AudioCaptureService`, `PitchDetector`, `PitchProcessor` as singletons
  - `DataModule` — provide `SettingsRepository`, `MelodyRepository`
- Annotate ViewModels with `@HiltViewModel`

---

## Phase 4: Tune Instrument Mode

**Goal:** Implement the simplest audio-reactive mode as a proof-of-concept for the full pipeline.

### 4.1 — Tuner ViewModel
- Create `TunerViewModel`:
  - Inject `PitchProcessor` and `SettingsRepository`
  - Collect pitch results as `StateFlow<TunerState>`
  - `TunerState`: detected note, octave, cents off, frequency, is-in-tune flag
  - Define "in-tune" threshold (e.g., ±5 cents)
  - Start/stop processing tied to screen lifecycle

### 4.2 — Pitch Gauge Component
- Create `PitchGauge.kt` — a reusable visual component:
  - Circular or semi-circular gauge showing cents offset (-50 to +50)
  - Needle/indicator moves smoothly (animated)
  - Color transitions: red (far off) → yellow (close) → green (in tune)
  - Center marker for "perfect"
- Use Compose Canvas for custom drawing

### 4.3 — Tuner Screen
- Create `TunerScreen.kt`:
  - Large note name display (e.g., "A4") at top
  - `PitchGauge` component showing cents offset
  - Frequency display (e.g., "441.2 Hz")
  - Flat/Sharp text indicators
  - Visual "in-tune" celebration (green glow when within threshold)
  - Reference pitch display from settings

---

## Phase 5: Free Play Mode

**Goal:** Let users play freely while getting real-time feedback on pitch accuracy.

### 5.1 — Free Play ViewModel
- Create `FreePlayViewModel`:
  - Collect pitch results from `PitchProcessor`
  - Maintain current note state and accuracy
  - Track a rolling history of last N notes played (for display)
  - Calculate running accuracy stats (% of time in tune, average cents off)
  - `FreePlayState`: currentNote, centsOff, accuracy, noteHistory, isPlaying

### 5.2 — Accuracy Indicator Component
- Create `AccuracyIndicator.kt`:
  - Visual bar or ring that fills based on pitch accuracy
  - Color-coded: green (±10 cents), yellow (±25 cents), red (>25 cents)
  - Smooth animations between states
  - Text overlay showing cents offset

### 5.3 — Note Display Component
- Create `NoteDisplay.kt`:
  - Large, prominent current note name + octave
  - Sharp/flat indicator arrows
  - Animated transitions when note changes
  - "No sound detected" state

### 5.4 — Free Play Screen
- Create `FreePlayScreen.kt`:
  - Top: session stats (time played, average accuracy, notes hit)
  - Center: large `NoteDisplay` + `AccuracyIndicator`
  - Bottom: scrolling history of recent notes with their accuracy
  - Play/stop button to control audio capture
  - Reuse `PitchGauge` from tuner mode (smaller version)

---

## Phase 6: MIDI Parser

**Goal:** Parse standard MIDI files to extract melody note sequences for the Play by Melody mode.

### 6.1 — MIDI File Parser
- Implement `MidiParser.kt`:
  - Parse Standard MIDI File format (SMF Type 0 and Type 1)
  - Read header chunk (MThd): format, number of tracks, time division
  - Read track chunks (MTrk): parse delta-time + events
  - Handle variable-length quantities
  - Parse relevant events:
    - Note On (0x9n) — extract note number, velocity
    - Note Off (0x8n) — or Note On with velocity 0
    - Tempo change (meta event 0x51) — microseconds per quarter note
    - Time signature (meta event 0x58)
    - End of track (meta event 0x2F)
  - Ignore: SysEx, text events, other meta events

### 6.2 — MIDI Data Model
- Create `MidiEvent.kt`:
  - `NoteEvent(noteNumber: Int, velocity: Int, startTick: Long, durationTicks: Long)`
  - `TempoEvent(tick: Long, microsecondsPerBeat: Long)`
  - `TimeSignatureEvent(tick: Long, numerator: Int, denominator: Int)`
- Create `MelodyTrack.kt`:
  - `notes: List<NoteEvent>` sorted by start time
  - `tempoChanges: List<TempoEvent>`
  - `ticksPerQuarterNote: Int`
  - Method: `tickToSeconds(tick: Long): Double` — convert tick to wall-clock time accounting for tempo changes
  - Method: `getNoteAtTime(seconds: Double): NoteEvent?`
  - Computed: total duration in seconds, note range (lowest to highest)

### 6.3 — Melody Repository
- Implement `MelodyRepository.kt`:
  - Load MIDI from `ContentResolver` URI (user-selected files)
  - Load bundled sample MIDI files from `res/raw/`
  - Cache parsed `MelodyTrack` objects
  - List available melodies (bundled + user-imported)
  - If a MIDI file has multiple tracks, use a heuristic to pick the melody track (highest average pitch, or let user choose)

### 6.4 — Bundle Sample MIDI Files
- Include 2–3 simple, well-known melodies as bundled files:
  - A simple scale (C major ascending/descending)
  - "Twinkle Twinkle Little Star"
  - "Ode to Joy"
- These serve as default content so the user doesn't need to import files immediately

---

## Phase 7: Play by Melody Mode — Synthesia-Style View

**Goal:** Build the scrolling note visualization and real-time play-along engine.

### 7.1 — Synthesia Canvas Renderer
- Create `SynthesiaCanvas.kt` — custom Compose Canvas:
  - **Vertical scrolling** (notes fall from top to bottom, like Synthesia/Piano Tiles)
  - **Piano-roll layout**: X-axis = pitch (note), Y-axis = time (scrolling)
  - Note rectangles: width = pitch lane, height = duration
  - Color coding:
    - Upcoming notes: blue/purple
    - Currently active note (in the "hit zone"): highlighted/white
    - Hit correctly: green
    - Missed/wrong pitch: red
    - Partially correct (right note, wrong duration): yellow
  - **Hit zone**: a horizontal bar/line near the bottom where notes should be played
  - Smooth scrolling animation synced to playback time
  - Note labels on each rectangle (e.g., "C4", "D4")
  - Left sidebar: note name reference (optional piano keyboard visual)

### 7.2 — Melody Playback Engine
- Create playback state management in `MelodyViewModel`:
  - `MelodyPlayState`: notStarted, countdown, playing, paused, finished
  - Track current playback time (using `System.nanoTime()` or `SystemClock.elapsedRealtime()`)
  - Determine which note(s) are currently expected
  - Compare detected pitch against expected note
  - Two sub-modes:
    - **Continuous**: notes scroll at fixed tempo regardless of player
    - **Wait mode**: pause scrolling until player hits the correct note, then advance
  - Record per-note results: `NoteResult(expected, detected, pitchAccuracy, durationAccuracy, timingOffset)`

### 7.3 — Melody Selection Screen
- Create file selection UI in `MelodyScreen.kt`:
  - List bundled sample melodies with names and difficulty indicators
  - "Import MIDI file" button using Android file picker (`ACTION_OPEN_DOCUMENT`, MIME type `audio/midi`)
  - Preview info for selected melody: duration, note count, range
  - Toggle: "Wait for correct note" (on/off)
  - Tempo adjustment slider (50%–150% of original tempo)
  - "Start" button → navigate to play screen

### 7.4 — Active Play Screen
- Layout for active melody playing:
  - Full-screen `SynthesiaCanvas` as main view
  - Thin overlay bar at top: current score, progress percentage, elapsed time
  - Detected note indicator at bottom (what mic is hearing)
  - Pause button (top corner)
  - Countdown (3-2-1) before melody starts

---

## Phase 8: Play by Melody Mode — Scoring & Results

**Goal:** Implement the scoring algorithm and results/summary screen.

### 8.1 — Scoring Engine
- Implement `ScoringEngine.kt`:
  - **Pitch accuracy** (per note):
    - Perfect: within ±10 cents → 100 points
    - Good: within ±25 cents → 75 points
    - Fair: within ±50 cents → 50 points
    - Miss: wrong note or >50 cents off → 0 points
  - **Duration accuracy** (per note):
    - Compare held duration vs expected duration
    - Score as percentage: `min(actual, expected) / max(actual, expected)`
    - Weight: 40% pitch, 40% duration, 20% timing (when note started relative to expected)
  - **Overall score**: weighted average of all note scores, normalized to 0–100
  - **Letter grade**: S (≥95), A (≥85), B (≥75), C (≥60), D (≥40), F (<40)
  - Track streaks (consecutive perfect/good notes)

### 8.2 — Results Screen
- Create `ScoreResultScreen.kt`:
  - Large overall score display (animated count-up)
  - Letter grade with star rating
  - Stats breakdown:
    - Notes hit / total notes
    - Average pitch accuracy
    - Average duration accuracy
    - Longest perfect streak
    - Best/worst sections
  - Per-note breakdown (scrollable list):
    - Expected note, detected note, pitch error, duration error, score
  - "Play Again" and "Back to Menu" buttons
  - Optional: share score (stretch goal)

---

## Phase 9: Settings & Data Persistence

**Goal:** Implement app settings and persistent preferences.

### 9.1 — Settings Repository (DataStore)
- Implement `SettingsRepository.kt` using Jetpack DataStore:
  - `concertPitch: Float` (default 440.0, range 420–460)
  - `pitchDetectionThreshold: Float` (YIN threshold, default 0.15)
  - `inTuneCentsThreshold: Int` (default 10)
  - `darkTheme: Boolean` (default true)
  - `silenceThresholdDb: Float` (default -40.0)
  - Expose as `Flow<Settings>` for reactive UI updates

### 9.2 — Settings Screen
- Create `SettingsScreen.kt`:
  - Concert pitch slider (420–460 Hz) with current value display
  - In-tune sensitivity selector (Strict ±5 / Normal ±10 / Relaxed ±20 cents)
  - Microphone sensitivity / silence threshold
  - Theme toggle (dark/light)
  - "About" section with app version
  - Reset to defaults button

---

## Phase 10: Polish, Testing & Deployment

**Goal:** Final polish, bug fixes, and deployment to physical device.

### 10.1 — App Icon & Branding
- Create app icon (vector drawable, adaptive icon)
- Splash screen (if desired, using Android 12+ Splash Screen API)
- Finalize color scheme consistency across all screens

### 10.2 — Performance Optimization
- Profile audio processing pipeline latency
- Ensure pitch detection runs under 20ms per frame
- Optimize Canvas rendering for Synthesia view (avoid allocations in draw loop)
- Minimize garbage collection during real-time audio processing
- Test with actual instrument sounds (not just synthetic)

### 10.3 — Edge Cases & Error Handling
- Handle microphone disconnection/unavailability gracefully
- Handle corrupted/unsupported MIDI files with user-friendly errors
- Handle audio focus (pause when phone call comes in)
- Handle screen rotation (lock to portrait or handle configuration changes)
- Handle very noisy environments (display "too noisy" warning)

### 10.4 — Build Signed APK & Deploy
- Generate debug/release APK
- Install on physical device via ADB
- Manual testing of all three modes with real instruments
- Performance testing on physical device

---

## Dependencies Summary

```kotlin
// build.gradle.kts (app)
dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.04.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity & Lifecycle
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.54")
    kapt("com.google.dagger:hilt-android-compiler:2.54")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.mockk:mockk:1.13.16")
}
```

## Risk Factors & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| YIN accuracy on real instruments | Core feature broken | Test with synthetic signals first, tune threshold; fall back to McLeod Pitch Method if needed |
| Audio latency on Android | Poor user experience | Use smallest viable buffer size; process on dedicated thread |
| MIDI parsing edge cases | Melody mode broken | Start with simple Type 0 files; add Type 1 later; validate against known files |
| Canvas performance for Synthesia view | Jank during playback | Profile early; limit drawn notes to visible window; avoid allocations in draw |
| Android SDK installation on this system | Can't build | Use cmdline-tools only (no Android Studio required); well-documented process |

## Phase Dependency Graph

```
Phase 1 (Setup)
    ↓
Phase 2 (Audio Engine) → Phase 3 (Architecture/Nav)
    ↓                        ↓
Phase 4 (Tuner)          Phase 6 (MIDI Parser)
    ↓                        ↓
Phase 5 (Free Play)      Phase 7 (Melody View)
                             ↓
                         Phase 8 (Scoring)
                             ↓
Phase 9 (Settings) ← depends on all modes existing
    ↓
Phase 10 (Polish & Deploy)
```

Note: Phases 2 and 3 can be worked on in parallel. Phases 4/5 and 6/7 are on separate tracks and can also be parallelized once their prerequisites are met.
