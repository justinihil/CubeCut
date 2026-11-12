# ADR-0012: Android 11+ only, arm64-only, R8 off

- **Status:** Accepted (documented retroactively)
- **Date:** arm64-only in 2.0.2; `minSdk 30` and R8 off in the 3.0.0 review fixes

## Context
- On Android 7–9, exports went to an app-only folder that was erased on uninstall.
- MediaStore trash needs Android 11.
- OpenCV's native libraries dominate the APK size.
- OpenCV (JNI) and Media3 risk breaking under R8.

## Decision
- `minSdk 30`, and the Android 7–10 code paths were removed.
- Release builds contain only `arm64-v8a`.
- `optimization { enable = false }` for release.

## Consequences
- Don't add `SDK_INT` checks below 30.
- 32-bit phones can't install the app.
- The APK isn't shrunk. Turning R8 on needs a release-build test and probably keep rules in `app/src/main/keepRules/rules.keep`.

## Alternatives considered
- Moving `abiFilters` to `defaultConfig`: declined, and the Gradle setup stays as it is.
