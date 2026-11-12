# Android platform, build and release

The **sources of truth** are [app/build.gradle.kts](../app/build.gradle.kts), [gradle/libs.versions.toml](../gradle/libs.versions.toml) and [AndroidManifest.xml](../app/src/main/AndroidManifest.xml). This page explains *why* they are set the way they are.

## Build configuration

| Setting | Value | Why |
|---|---|---|
| `namespace` / `applicationId` | `com.example.cubecut` | Fine for sideloading. Google Play would refuse `com.example.*`, and changing it later installs as a separate app. TODO(owner): decide on a final id before any store release |
| `minSdk` | 30 (Android 11) | MediaStore trash and scoped export need 11+. Android 7–10 code was removed ([ADR-0012](adr/0012-min-sdk-30-arm64-only.md)) |
| `targetSdk` / `compileSdk` | 37 | Current platform |
| Release `ndk.abiFilters` | `arm64-v8a` only | OpenCV's native libraries make up most of the APK size, so other ABIs are dropped |
| Release `optimization.enable` | `false` (R8 off) | OpenCV (JNI) and Media3 can break under shrinking. `app/src/main/keepRules/rules.keep` is the unchanged template and has no effect while R8 is off |
| Java target | 11 | Android Studio template default |
| Gradle / AGP / Kotlin | Gradle 9.6.0 wrapper, AGP 9.4.1, Kotlin 2.2.10, JVM toolchain 25 (`gradle/gradle-daemon-jvm.properties`) | — |
| Build types | `debug`, `release` (no flavors) | — |
| Signing | **No `signingConfigs` in Gradle.** Release APKs are signed by hand with the Android Studio wizard | Keeps the keystore out of the repo (`.gitignore` excludes `*.jks`, `*.keystore`, `keystore.properties`) |

The 16 KB page-size alignment of the `arm64-v8a` libraries was checked with Analyze APK for 3.0.0.

## Permissions

These are the 7 permissions in the merged debug manifest (`processDebugManifest`), checked 2026-11-12:

| Permission | Declared by | Why |
|---|---|---|
| `FOREGROUND_SERVICE` | app | `AnalysisService` keeps Find solves alive while the user switches apps |
| `FOREGROUND_SERVICE_MEDIA_PROCESSING` | app | The service type on Android 15+, which is the accurate label and is checked by Google Play |
| `FOREGROUND_SERVICE_DATA_SYNC` | app | The service type on Android 11–14, where `mediaProcessing` doesn't exist |
| `POST_NOTIFICATIONS` | app | The progress and "finished" notifications on 13+. Asked the first time *Continue* is pressed on Find solves. If refused, the scan still runs |
| `ACCESS_NETWORK_STATE` | Media3 | Library default. Kept on purpose; it does not allow internet access |
| `WAKE_LOCK` | Media3 | Library default for playback. Kept on purpose |
| `com.example.cubecut.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX core | Internal signature permission for non-exported dynamic receivers |

**No storage permission is needed.** Videos come from `OpenDocument` with a persisted read grant, and exports go through MediaStore. **There is no INTERNET permission**, by design ([ADR-0001](adr/0001-offline-classic-cv-no-ml.md)). After changing dependencies, check the merged manifest with `./gradlew :app:processDebugManifest` and inspect `app/build/intermediates/merged_manifest/debug/`.

## Manifest choices

- **`allowBackup="false"` + `res/xml/data_extraction_rules.xml`** exclude every domain from cloud backup **and** device transfer. On 12+, `allowBackup` alone does not stop device transfer. See [ADR-0009](adr/0009-no-backup-or-transfer.md).
- **`screenOrientation="portrait"` + a long `configChanges` list** mean the activity is never recreated for rotation, dark-mode switches, font scale, density, locale or split screen. The app has no `rememberSaveable` or ViewModel, so a recreation would close the editor and kill a running scan or export. See [ADR-0010](adr/0010-portrait-lock-config-changes.md).
- **`AnalysisService`** is `exported="false"` with `foregroundServiceType="dataSync|mediaProcessing"`. It picks the type at runtime by SDK level, returns `START_NOT_STICKY`, and stops itself in `onTimeout`. Android 15+ allows about 6 h per day. See [ADR-0011](adr/0011-foreground-service-for-notification-only.md).
- **`windowSoftInputMode="adjustResize"`.**
- **Window background** comes from `res/values(-night)/themes.xml` and `colors.xml` (`window_background`), so dark mode never flashes white before Compose draws.
- **`res/layout/video_player_view.xml`** is a `PlayerView` with `surface_type="texture_view"`. The editor inflates it because pinch zoom via `graphicsLayer` needs a TextureView, and a `PlayerView` created in code can't choose its surface type. **The build breaks if it is renamed.**

## Release process

What git history and the old guide show. TODO(owner): confirm or correct.

1. Finish work on a feature branch and open a PR into `dev`, then a PR from `dev` into `main`. See [CONTRIBUTING](../CONTRIBUTING.md).
2. In `app/build.gradle.kts`, set `versionName` to the release (`"X.Y.Z"`) and raise `versionCode` by 1 for every shared release. Android refuses a lower code; an equal code still installs over the old one, but stores and update checkers can't see it as new.
   - **Currently out of step:** v3.0.1 was tagged with `versionCode 3` / `versionName "3.0.0"`. The plan was 4 / "3.0.1". TODO(owner): was the 3.0.1 APK built from an uncommitted bump? Fix in the next code change.
3. In `CHANGELOG.md`, rename `[Unreleased]` to `[X.Y.Z] - YYYY-MM-DD` and add a new empty `[Unreleased]`.
4. Build the signed APK: **Build → Generate Signed App Bundle or APK → APK**, choose the keystore, then the `release` build type. The output goes to `app/release/` (git-ignored).
   - **The keystore and its passwords must be backed up outside the repo.** Updates signed with a different key force users to uninstall first.
   - TODO(owner): where the keystore is kept and how it is backed up.
5. Test the release APK on the phone before sharing. R8 is off, but release differs from debug in ABI filtering.
6. Tag the merge commit on `main` as `vX.Y.Z` and push the tag. Existing tags are lightweight. TODO(owner): confirm lightweight tags are intended.
7. Create a GitHub Release from the tag and attach the APK. APKs are never committed.

**Installing:** open the APK and allow "Install unknown apps". Play Protect may warn. The phone must run Android 11+ on arm64.

## Building and testing locally

Commands are in [CLAUDE.md](../CLAUDE.md#commands). Debug builds land in `app/build/outputs/apk/debug/`. Install on a USB- or Wi-Fi-debugging phone with Android Studio's Run button or `./gradlew installDebug`. The emulator is too slow for real video work. Logcat filter: tag `CubeCut` (frame-source decisions).
