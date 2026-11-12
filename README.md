# CubeCut

**Cut a long speedcubing session video into one clip per solve, automatically, on your phone.**

CubeCut reads the timer display along the whole video. When a solve ends, the timer freezes on its final time, and the **first frame that shows that time** is where the cut goes. Each solve becomes its own clip named with its time, for example `Sunday practice_Solve_03_11.263s.mp4`. A clip can be **just the solve** or **include the scramble**, with adjustable buffers before and after.

- Runs **offline on the phone**: no cloud, no accounts, no internet permission.
- **No AI or machine learning.** It uses classic OpenCV image processing and counting.
- A **review screen** lets you fix times, move cuts, delete false solves (with Undo), add missed ones and work through flagged *checks*.
- A **Gallery** remembers every video and solve. It has a full-screen viewer, sorting, filtering, multi-select export and dark mode.
- **Lossless, fast exports** to `Movies/CubeCut`. Nothing is ever deleted permanently; deletes go to the phone's trash.

Current version: **3.0.1**. See the [CHANGELOG](CHANGELOG.md). How to use it: [docs/user-guide.md](docs/user-guide.md).

> **Please read "Tested setup" before you try it.** CubeCut is a hobby project that has only been tried in a narrow setup.

## Tested setup

Anything not listed under "Tested" is unknown, not "probably fine".

**Tested**

| | |
|---|---|
| Phone | **Samsung Galaxy S25 Ultra**, and only that phone |
| Footage | **1080p at 30 fps and 60 fps** |
| Camera | **Fixed on a tripod**, with the timer's display in view |
| Timers | **QiYi Bluetooth Timer** (LCD display) and **csTimer in the "Digital 2" font**, with csTimer's "use small font after decimal point" option **turned off** |

**Not tested** (it may work, it may not)

* Any other phone or Android version
* Head-mounted, handheld or otherwise moving cameras
* 4K, HDR / 10-bit video, and frame rates other than 30 and 60 fps
* Other timers and fonts. **Only seven-segment style digits can be read.**
* csTimer with the small-decimals option on, penalty marks (+2 / DNF) next to the time, and times of an hour or more
* A timer that blanks or resets its display right after the solve. The final time has to stay on screen for about 1.5 s.

## Requirements

| | |
|---|---|
| Phone | **Android 11 or newer**, **64-bit (arm64)** |
| Timer | **Seven-segment style digits**: Stackmat-style LCD timers (e.g. QiYi Smart Timer) or screens such as csTimer (small-decimals option off) |
| Footage | A fixed camera works best, with the display in view and the final time held for about 1.5 s or more |

## Install

Download the `.apk` from this repository's **Releases** page, open it on the phone, and allow "Install unknown apps". Google Play Protect may warn about apps from outside the Play Store; this is expected.

## Privacy

The app has no internet permission and uploads nothing. Its saved list of sessions is left out of Android backup and phone-to-phone transfer, so nothing leaves the phone. The extra permissions added by the video player and AndroidX libraries don't allow internet access; the full list is in [docs/android.md](docs/android.md#permissions). Videos are opened with Android's file picker and only read. Clips are written to your own `Movies/CubeCut` folder.

## Support

CubeCut is shared as-is. **I can't offer tech support or promise fixes**, and I may not reply to messages or issues. Most problems come from something outside the tested setup above. The code is open, so you are welcome to fork it. The part most worth improving is the timer reader, which sits behind a small interface (`DisplayReaders.kt`) so that other fonts can be added.

## For developers

```sh
./gradlew assembleDebug         # build → app/build/outputs/apk/debug/
./gradlew testDebugUnitTest     # unit tests
./gradlew installDebug          # install on a connected phone (USB or wireless debugging)
```

Or open the folder in Android Studio, let Gradle sync, and press Run. A physical phone is recommended because the emulator is too slow for video work.

| Read | For |
|---|---|
| [CLAUDE.md](CLAUDE.md) | Commands, conventions and hard rules (start here, human or AI) |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Diagrams, data flow, invariants, where to look, how to add things |
| [app/README.md](app/README.md) | Every source file: responsibility, entry points, gotchas |
| [docs/](docs/) | [algorithms](docs/algorithms.md) · [data model](docs/data-model.md) · [Android, permissions & release](docs/android.md) · [glossary](docs/glossary.md) · [known issues](docs/known-issues.md) · [roadmap](docs/roadmap.md) · [decisions (ADRs)](docs/adr/README.md) · [user guide](docs/user-guide.md) |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Branches, commit messages, the PR checklist |

## Licence

CubeCut is free software under the **GNU General Public License, version 3** (GPL-3.0), with **additional terms** that protect the author credit. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).

Copyright (C) 2026 DadCuber

In plain words (the licence text is what counts): you may use, study, change and share CubeCut. If you share it or a modified version, you must:

* release the full source under the **same licence**;
* **keep the author credit**: the copyright notices in the files and the "CubeCut by DadCuber" credit in the app, which opens the About window. You may add your own name next to it for your changes;
* **say clearly that your version is changed** (for example "Modified version of CubeCut by DadCuber") and not present it as the original.

Using the app and exporting clips carries no obligations. There is **no warranty**. Third-party libraries (OpenCV, AndroidX Media3, Jetpack Compose) are used under the Apache License 2.0, which is compatible with GPL-3.0.
