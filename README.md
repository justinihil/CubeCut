# CubeCut

**Cut a long speedcubing session video into one clip per solve, automatically, on your phone.**

CubeCut reads the timer display along the whole video. When a solve ends the timer freezes on its final time, and the **first frame that shows that time** is where the cut goes. Each solve becomes its own clip, named with the solve time, for example `Solve_03_11.263s.mp4`. A clip can be **just the solve** (found by counting back the time on the timer) or **include the scramble**, with adjustable buffers before and after.

* Runs **offline on the phone**. No cloud, no accounts.
* **No AI or machine learning.** Only classic OpenCV image processing and simple counting. No sound analysis.
* A full **review screen**: switch each solve on or off, choose per solve (or for all at once) whether the scramble is kept, fix its time, nudge the cut point, add a missed cut by hand, then export.
* Exports are **lossless** (no re-encoding), so they are fast and keep full quality.
* **Fast scan:** Find solves decodes the video with one running hardware decoder instead of seeking for every frame. On the developer's test session this took the scan from about 107 s to about 27 s. If the fast path can't be used on a phone or a video, it quietly falls back to the slower way.

Current version: **2.0.2** (first-frame cuts, plus clean-up and bug fixes). See [CHANGELOG.md](CHANGELOG.md).

> **Please read "Tested setup" below before you try it.** CubeCut is a hobby project that has only been tried in a narrow setup. It may work well outside it, but that hasn't been checked.

## How it works, in short

1. **Import** the session video.
2. **Set up the timer** (this comes first, the app depends on it): pause on a frame that shows **an actual time after a solve** (the timer has stopped and shows its final time, **not 0.000**), tap the display, drag four dots onto its corners (a magnifier helps), type the number it shows. Do this again at every app start and every video import (the setup is not saved).
3. **Find solves**: the app reads the timer along the whole video (about half a minute for the developer's test session on a recent phone, longer on slower phones or longer videos; it can be cancelled). One progress card shows how far it is and has the **Cancel** button, and the Setup box folds away while it runs; importing a video and changing the timer are blocked until it is done. A non-zero time that stays on the display is a finished solve, and the first frame showing it is the cut. A few solves may need a look; the app lists them with a note.
4. **Review** the list, step through with Prev / Next (the list scrolls to the solve), fix anything. Check that every solve was captured: if one is missing, put the playhead on the first frame that shows its final time and tap **+ Cut at playhead**.
5. **Choose what each clip contains**: tick *Include scramble* per solve, or use the switch for all clips. Without the scramble the clip is the solve only, plus a **before buffer** and an **after buffer** (2.5 s each by default, adjustable in the Setup box).
6. **Export** to `Movies/CubeCut`.

If the timer can't be read, nothing is blocked: the setup saves with a warning, and cuts can always be added by hand.

## Tested setup

Everything below describes what has **actually been tried**. Anything not listed under "Tested" should be treated as unknown, not as "probably fine".

**Tested**

| | |
|---|---|
| Phone | **Samsung Galaxy S25 Ultra**, and only that phone |
| Footage | **1080p at 30 fps and 60 fps** |
| Camera | **Fixed on a tripod**, with the timer's display in view |
| Timers | **QiYi Bluetooth Timer** (LCD display) and **csTimer in the "Digital 2" font**, with csTimer's "use small font after decimal point" option **turned off** |

**Not tested** (it may work, it may not)

* Any other phone or phone brand, and other Android versions (the app is built for Android 7.0 and newer, but has not been run on anything but the phone above)
* Head-mounted or handheld action cameras, or any camera that moves a lot during the video (the timer finder copes with small movements, but this has not been tried)
* 4K video, HDR / 10-bit video, and frame rates other than 30 and 60 fps
* Other timers and other fonts. **Only seven-segment style digits can be read.** Other fonts are not supported
* csTimer with the small-decimals option on, penalty marks (+2 / DNF) shown next to the time, and times of an hour or more
* A timer that blanks or resets its display immediately after the solve (the final time has to stay on screen for at least about 1.5 s)

## Requirements

| | |
|---|---|
| Phone | Android 7.0 or newer (`minSdk 24`) **on a 64-bit (arm64) phone**. Since version 2.0.2 the app only includes the arm64 version of OpenCV to keep the download small, so very old 32-bit phones can't install it |
| Timer | A display with **seven-segment style digits**: Stackmat-style LCD timers (for example the QiYi Smart Timer) and screens such as **csTimer** on a PC or phone (turn off its "use small font after decimal point" option). Other fonts are not supported yet |
| Footage | A fixed camera works best, with the timer's display in view. The timer must keep showing the final time for at least about 1.5 s after a solve. The main test footage is 1080x1920, 60 fps, HEVC. Video the phone's hardware decoder can't handle (for example some HDR / 10-bit footage) should still be read, but through a slower fallback (not tested) |
| Build | Android Studio, Kotlin, Jetpack Compose, OpenCV 4.13.0, Media3 1.10.1 |

## Things to know

* **Long videos can make the phone warm and use a lot of battery** while Find solves runs. Plug it in for a long session.
* **Exported clips are full-quality copies**, so a whole session can take several GB. Check you have free space before exporting.
* **Your original video is never changed.** The app only reads it and writes new files to `Movies/CubeCut`.
* **The timer must be set up again** every time the app starts and every time you import a video.
* Re-running Find solves replaces the list, so manual edits are lost.

## Build and run

1. Open the project folder in Android Studio and let Gradle sync (internet needed the first time).
2. Connect your phone (USB debugging or wireless debugging) and press the green Run button.
3. Details, troubleshooting and the full technical write-up are in [docs/CUBECUT_PROJECT_GUIDE.md](docs/CUBECUT_PROJECT_GUIDE.md).

## Install without Android Studio

Download the `.apk` from the **Releases** page of this repository, open it on the phone and allow "Install unknown apps" when asked. Google Play Protect may show a warning for apps that do not come from the Play Store; this is expected.

## Support

CubeCut is a hobby project, shared as-is. **I can't offer tech support or promise fixes**, and I may not reply to messages, comments or issues. If it doesn't work for you, first check the "Tested setup" section above: most problems come from something that hasn't been tried yet (a different phone, timer, font or camera setup).

The code is open, so you are welcome to fork it and fix or extend it. The part most worth improving is the timer reader: it sits behind a small interface in `DisplayReaders.kt` so that a reader for other fonts can be added without touching the rest of the app (see section 2.3b and section 7 of the project guide).

## Documentation

| File | What is in it |
|---|---|
| [docs/CUBECUT_PROJECT_GUIDE.md](docs/CUBECUT_PROJECT_GUIDE.md) | Everything: algorithms, every file, UI map, decisions, test footage, debugging, restart kit for a new AI session |
| [docs/GIT_WORKFLOW.md](docs/GIT_WORKFLOW.md) | How to use Git and GitHub for this project, step by step |
| [docs/TIMER_READING_CHANGES.md](docs/TIMER_READING_CHANGES.md) | Detailed record and reasoning of the timer-reading changes (csTimer / PC screens, reader seam, setup screen) |
| [docs/FIRST_FRAME_CUTS_CHANGES.md](docs/FIRST_FRAME_CUTS_CHANGES.md) | Record of the 2.0.0 change: cuts from the first frame that shows the time, solve-only clips, buffers, the new screen, tuning constants |
| [pc_prototype/first_frame_scan_prototype.py](pc_prototype/first_frame_scan_prototype.py) | The 2.0.0 scan and clip logic in Python with a noisy-timer simulator and tests (`python first_frame_scan_prototype.py`) |
| [CHANGELOG.md](CHANGELOG.md) | What changed in each version (1.0.0 to 2.0.2) |
| [LICENSE](LICENSE) | The full GNU General Public License, version 3 |
| [NOTICE.md](NOTICE.md) | The extra terms added to the licence: keep the author credit, mark changed versions |

## Privacy

The app has no internet permission and uploads nothing. Videos are read through Android's photo picker and clips are written to your own `Movies/CubeCut` folder.

## Licence

CubeCut is free software, released under the **GNU General Public License, version 3** (GPL-3.0), with a few **additional terms** that protect the author credit. The full licence is in the [LICENSE](LICENSE) file and the extra terms are in [NOTICE.md](NOTICE.md).

Copyright (C) 2026 DadCuber

In plain words (the licence text is what counts): you may use, study, change and share CubeCut, including for your own videos and channel. If you share it or a modified version, you must:

* release the full source code of your version under the **same licence** (GPL-3.0), so it stays open source;
* **keep the author credit**: the copyright notices in the files, and the "CubeCut by DadCuber" credit in the app (a small line at the bottom of the main screen that opens the About window). You can add your own name next to it for your changes, but not replace mine;
* **say clearly that your version is changed** (for example "Modified version of CubeCut by DadCuber" in its About window), and not present it as the original.

Using the app and exporting clips carries no obligations. If you mention CubeCut in videos you publish, thank you, but that is a request and not a condition. There is **no warranty** of any kind, as the licence explains.

Third-party libraries (OpenCV, AndroidX Media3, Jetpack Compose) are used under their own licences (Apache License 2.0), which are compatible with GPL-3.0.
