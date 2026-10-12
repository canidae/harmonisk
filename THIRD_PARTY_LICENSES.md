# Third-party licenses

Harmonisk itself is licensed under the GNU Affero General Public License v3.0
(see [LICENSE](LICENSE)). This file lists the third-party components that are
distributed in the app, the ones used only while building or testing, and the
assets that are bundled with the app.

All components that end up in the APK/AAB are compatible with the AGPL-3.0:
permissive licenses (Apache-2.0, MIT, BSD-3-Clause, ISC, CC0, OFL-1.1) and the
file-level copyleft of MPL-2.0 can all be combined into a larger AGPL-3.0 work.
The only incompatible license in the project is JUnit's EPL-1.0, and JUnit is a
test-only dependency that is never distributed.

The list of runtime dependencies was produced with:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

## Distributed with the app

### AndroidX / Jetpack (93 artifacts)

| Component | Version | License |
| --- | --- | --- |
| AndroidX Core, Activity, Lifecycle, Navigation, DataStore, Hilt extensions | see `app/build.gradle.kts` | Apache-2.0 |
| Jetpack Compose UI, Foundation, Material 3, Material Icons Extended | Compose BOM 2026.06.00 | Apache-2.0 |

Most of these artifacts are pulled in transitively; `app/build.gradle.kts` lists
the ones the app uses directly.

### Other libraries

| Component | Version | License | Notes |
| --- | --- | --- | --- |
| Kotlin standard library | 2.3.21 | Apache-2.0 | |
| kotlinx.coroutines | 1.11.0 | Apache-2.0 | |
| kotlinx.serialization | 1.11.0 | Apache-2.0 | |
| Dagger / Hilt | 2.59.2 | Apache-2.0 | |
| TensorFlow Lite | 2.17.0 | Apache-2.0 | |
| LiteRT | 1.0.1 | Apache-2.0 | runtime behind TensorFlow Lite |
| Okio | 3.9.1 | Apache-2.0 | |
| JSR-305 (`com.google.code.findbugs:jsr305`) | 3.0.2 | Apache-2.0 | annotations only |
| JSpecify | 1.0.0 | Apache-2.0 | annotations only |
| javax.inject / jakarta.inject | 1 / 2.0.1 | Apache-2.0 | the `javax.inject` POM omits the license field |
| Guava listenablefuture | 1.0 | Apache-2.0 | the POM omits the license field |
| JetBrains annotations | 23.0.0 | Apache-2.0 | |
| alphaTab | 1.6.1 | MPL-2.0 | sheet music rendering; see bundled assets below |
| alphaSkia | 3.5.147 | BSD-3-Clause | native rendering backend for alphaTab |

alphaTab is MPL-2.0, a file-level copyleft: the library is used unmodified, and
if it is ever patched, the patched alphaTab files have to stay available under
MPL-2.0. The MPL explicitly allows distributing a larger work under a different
license, so this does not affect the AGPL-3.0 licensing of Harmonisk.

### Konfetti

| Component | Version | License |
| --- | --- | --- |
| Konfetti Core / Compose | 2.0.5 | ISC |

### Bundled assets

| Asset | License | Attribution |
| --- | --- | --- |
| `app/src/main/assets/GeneralUser-GS.sf2` | GeneralUser GS License v2.0 | GeneralUser GS by S. Christian Collins, <https://schristiancollins.com/generaluser.php> |
| `app/src/main/assets/crepe_small_f16.tflite` | MIT | CREPE, Kim et al. (2018), <https://github.com/marl/crepe> |
| `app/src/main/jni/tsf.h` | MIT | TinySoundFont by Bernhard Schelling (based on SFZero by Steve Folta), <https://github.com/schellingb/TinySoundFont> |
| Bravura and BravuraText fonts, shipped inside alphaTab | SIL OFL-1.1 | Bravura by Steinberg Media Technologies GmbH, <https://github.com/steinbergmedia/bravura> |
| Sonic Network Inc. samples, shipped inside alphaTab | Apache-2.0 | Copyright (c) 2004-2006 Sonic Network Inc. |
| `app/src/main/assets/melodies/**` | public domain | traditional tunes, arrangements made for this project |

GeneralUser GS is distributed free of charge and its license explicitly allows
use in software projects, including commercial ones. Bravura is bundled
unmodified, which is all the OFL-1.1 requires beyond keeping the copyright
notice.

## Build and test only

These are not part of the APK/AAB and are never distributed.

| Component | Version | License |
| --- | --- | --- |
| Gradle and the Gradle wrapper | see `gradle/wrapper` | Apache-2.0 |
| Android Gradle Plugin | 9.2.1 | Apache-2.0 |
| Kotlin, Compose and serialization Gradle plugins | 2.3.21 | Apache-2.0 |
| KSP | 2.3.9 | Apache-2.0 |
| Hilt Gradle plugin | 2.59.2 | Apache-2.0 |
| JUnit | 4.13.2 | EPL-1.0 |
| MockK | 1.14.11 | Apache-2.0 |

JUnit 4 is licensed under the Eclipse Public License 1.0, which is not
compatible with the AGPL-3.0. It is used for unit tests only and is not linked
into or distributed with the app, so it does not affect the license of the
released product.

## License texts

Full license texts are available from the links above and are bundled by Maven
where the artifact provides them. The app does not ship a license screen; the
attributions in [README.md](README.md) and this file are the required notices.
