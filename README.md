# CubeCut

**Cut a long speedcubing session video into one clip per solve, automatically, on your phone.**

CubeCut listens for the sharp sound of the timer being stopped, then reads the time on the timer display in the video to confirm it is a real solve. Each solve becomes its own clip, named with the solve time, for example `Solve_03_11.263s.mp4`.

* Runs **offline on the phone**. No cloud, no accounts.
* **No AI or machine learning.** Only sound-spike detection and classic OpenCV image processing.
* A full **review screen**: switch each solve on or off, fix its time, nudge the cut point, add a missed cut by hand, then export.
* Exports are **lossless** (no re-encoding), so they are fast and keep full quality.

Current version: **1.1.0**. See [CHANGELOG.md](CHANGELOG.md).

## How it works, in short

1. **Import** the session video.
2. **Set up the timer**: pause on a clear frame, tap the display, drag four dots onto its corners (a magnifier helps), type the number it shows. Do this again at every app start and every video import (the setup is not saved).
3. **Find solves**: for every loud sound the app reads the timer on the next ~2.7 s of video. A steady, non-zero time means a real solve. Anything doubtful is listed as "check this one" (switched off) so nothing is silently lost.
4. **Review** the list, step through with Prev / Next, fix anything.
5. **Export** to `Movies/CubeCut`.

## Requirements

| | |
|---|---|
| Phone | Android 7.0 or newer (`minSdk 24`) |
| Timer | A display with **seven-segment style digits**: Stackmat-style LCD timers (for example the QiYi Smart Timer) and screens such as **csTimer** on a PC or phone (turn off its "smaller font after the decimal point" option). Other fonts are not supported yet |
| Footage | A fixed camera works best. Tested with 1080x1920, 60 fps, HEVC |
| Build | Android Studio, Kotlin, Jetpack Compose, OpenCV 4.13.0, Media3 1.10.1 |

## Build and run

1. Open the project folder in Android Studio and let Gradle sync (internet needed the first time).
2. Connect your phone (USB debugging or wireless debugging) and press the green Run button.
3. Details, troubleshooting and the full technical write-up are in [docs/CUBECUT_PROJECT_GUIDE.md](docs/CUBECUT_PROJECT_GUIDE.md).

## Install without Android Studio

Download the `.apk` from the **Releases** page of this repository, open it on the phone and allow "Install unknown apps" when asked. Google Play Protect may show a warning for apps that do not come from the Play Store; this is expected.

## Documentation

| File | What is in it |
|---|---|
| [docs/CUBECUT_PROJECT_GUIDE.md](docs/CUBECUT_PROJECT_GUIDE.md) | Everything: algorithms, every file, UI map, decisions, test footage, debugging, restart kit for a new AI session |
| [docs/GIT_WORKFLOW.md](docs/GIT_WORKFLOW.md) | How to use Git and GitHub for this project, step by step |
| [docs/TIMER_READING_CHANGES.md](docs/TIMER_READING_CHANGES.md) | Detailed record and reasoning of the timer-reading changes (csTimer / PC screens, reader seam, setup screen) |
| [CHANGELOG.md](CHANGELOG.md) | What changed in each version |

## Privacy

The app has no internet permission and uploads nothing. Videos are read through Android's photo picker and clips are written to your own `Movies/CubeCut` folder.

## Licence

Not chosen yet. Until a licence file is added, all rights are reserved by the author. If the repository is made public, add a `LICENSE` file first. Third-party libraries (OpenCV, AndroidX Media3, Jetpack Compose) have their own licences.
