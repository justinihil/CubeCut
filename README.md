# CubeCut

**Cut a long speedcubing session video into one clip per solve, automatically, on your phone.**

CubeCut reads the timer display along the whole video. When a solve ends the timer freezes on its final time, and the **first frame that shows that time** is where the cut goes. Each solve becomes its own clip, named with the solve time, for example `Solve_03_11.263s.mp4`. A clip can be **just the solve** (found by counting back the time on the timer) or **include the scramble**, with adjustable buffers before and after.

* Runs **offline on the phone**. No cloud, no accounts.
* **No AI or machine learning.** Only classic OpenCV image processing and simple counting. No sound analysis.
* A full **review screen**: switch each solve on or off, choose per solve (or for all at once) whether the scramble is kept, fix its time, nudge the cut point, add a missed cut by hand, then export.
* Exports are **lossless** (no re-encoding), so they are fast and keep full quality.
* **Fast scan:** Find solves decodes the video with one running hardware decoder instead of seeking for every frame. On the developer's test session this took the scan from about 107 s to about 27 s. If the fast path can't be used on a phone or a video, it quietly falls back to the slower way.

Current version: **2.0.0, in development** on the `dev` branch (first-frame cuts); the last released version is 1.1.0. See [CHANGELOG.md](CHANGELOG.md).

## How it works, in short

1. **Import** the session video.
2. **Set up the timer** (this comes first, the app depends on it): pause on a frame that shows **an actual time after a solve** (the timer has stopped and shows its final time, **not 0.000**), tap the display, drag four dots onto its corners (a magnifier helps), type the number it shows. Do this again at every app start and every video import (the setup is not saved).
3. **Find solves**: the app reads the timer along the whole video (about half a minute for the developer's test session on a recent phone, longer on slower phones or longer videos; it can be cancelled). A non-zero time that stays on the display is a finished solve, and the first frame showing it is the cut. A few solves may need a look; the app lists them with a note.
4. **Review** the list, step through with Prev / Next (the list scrolls to the solve), fix anything. Check that every solve was captured: if one is missing, put the playhead on the first frame that shows its final time and tap **+ Cut at playhead**.
5. **Choose what each clip contains**: tick *Include scramble* per solve, or use the switch for all clips. Without the scramble the clip is the solve only, plus a **before buffer** and an **after buffer** (2.5 s each by default, adjustable in the Setup box).
6. **Export** to `Movies/CubeCut`.

If the timer can't be read, nothing is blocked: the setup saves with a warning, and cuts can always be added by hand.

## Requirements

| | |
|---|---|
| Phone | Android 7.0 or newer (`minSdk 24`) |
| Timer | A display with **seven-segment style digits**: Stackmat-style LCD timers (for example the QiYi Smart Timer) and screens such as **csTimer** on a PC or phone (turn off its "smaller font after the decimal point" option). Other fonts are not supported yet |
| Footage | A fixed camera works best, with the timer's display in view. The timer must keep showing the final time for at least about 1.5 s after a solve. Tested with 1080x1920, 60 fps, HEVC. Video the phone's hardware decoder can't handle (for example some HDR / 10-bit footage) is still read, but through a slower fallback |
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
| [docs/FIRST_FRAME_CUTS_CHANGES.md](docs/FIRST_FRAME_CUTS_CHANGES.md) | Record of the 2.0.0 change: cuts from the first frame that shows the time, solve-only clips, buffers, the new screen, tuning constants |
| [pc_prototype/first_frame_scan_prototype.py](pc_prototype/first_frame_scan_prototype.py) | The 2.0.0 scan and clip logic in Python with a noisy-timer simulator and tests (`python first_frame_scan_prototype.py`) |
| [CHANGELOG.md](CHANGELOG.md) | What changed in each version |

## Privacy

The app has no internet permission and uploads nothing. Videos are read through Android's photo picker and clips are written to your own `Movies/CubeCut` folder.

## Licence

Not chosen yet. Until a licence file is added, all rights are reserved by the author. If the repository is made public, add a `LICENSE` file first. Third-party libraries (OpenCV, AndroidX Media3, Jetpack Compose) have their own licences.