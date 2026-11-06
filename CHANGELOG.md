# Changelog

All notable changes to CubeCut are listed here, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

> **About the older entries.** This file was created with version 2.0.1. The entries for 1.0.0, 1.1.0 and 2.0.0 were reconstructed afterwards from `docs/CUBECUT_PROJECT_GUIDE.md` and the two change records in `docs/`, so they describe the milestones rather than every small edit, and their release dates were not recorded. From 2.0.1 on, add to **Unreleased** as you work and rename it to the version number when you release.

## [Unreleased]

Nothing yet.

## [2.0.2] - 2026-11-03

A clean-up and bug-fix release on top of 2.0.0. How solves are found, how digits are read and how clips are calculated did **not** change.

### Changed
- Limited OpenCV to only arm64 devices resulting in lower storage requirement.

### Fixed
- Fixing a bug with exported videos where audio is not synced when using some video editors.

### Licence, author credit and documentation (2026-11-06)
Added later the same version, with no version bump. How solves are found, how digits are read and how clips are calculated did **not** change.

**Added**
- **GPL-3.0 licence** (`LICENSE`): anyone may use and modify CubeCut, but shared versions must stay open source under the same licence and keep the copyright notice.
- **Additional licence terms** (`NOTICE.md`, GPL section 7): the author credit must stay in the app's About window, and changed versions must be marked as changed. Using the app and the exported clips is not restricted.
- **Author credit in the app:** a small line "CubeCut by DadCuber" at the bottom of the main screen. Tapping it opens an **About window** with the credit, where to find the author, a one-line description and the licence notice (`AUTHOR_NAME`, `AUTHOR_FIND_ME` and the `showAbout` dialog in `MainActivity.kt`).
- `add_license_headers.py`: adds the licence header to every `.kt` file (run once from the project root).

**Documentation**
- **README:** new "Tested setup" section listing what has actually been tried (Samsung Galaxy S25 Ultra only; 1080p at 30 and 60 fps; fixed tripod; QiYi Bluetooth Timer LCD and csTimer's "Digital 2" font with the small-decimals option off) and what has not (other phones, head-mounted or moving cameras, other fonts, 4K / HDR). Also: the arm64-only requirement from 2.0.2, a "Things to know" list (heat, storage, originals untouched), a "Support" section (shared as-is, no tech support), and the licence section. Version line brought up to 2.0.2.
- **Project guide:** version, tested phone / footage / timer font (sections 0, 1, 7), the repository file list (3.3, 3.5), installation needs (4.4), the status section (9) and the restart prompt (10.2) updated to 2.0.2.

## [2.0.1] - 2026-11-03

A clean-up and bug-fix release on top of 2.0.0. How solves are found, how digits are read and how clips are calculated did **not** change.

### Changed
- **Find solves shows one progress display.** The card in the scrolling area (percentage, thick bar, "you can switch to another app") is kept and now has the **Cancel** button. The thin second progress bar with its own Cancel, under the Find solves button, is gone.
- **The Setup box folds away as soon as Find solves starts**, instead of when it finishes. It still opens again if the scan finds no solves, so the timer can be set up again.
- The solves list looks up each row's clip by cut number instead of searching the clip list for every row.

### Fixed
- **Importing a video during a scan** is no longer possible. Before, the scan carried on with the old video and, when it finished, filled the new video's list with the old video's solves. *Import video* is also disabled while an export runs.
- **Set up timer, Set up again and Read timer here are disabled during a scan.** They share non-thread-safe OpenCV objects with the scan and could crash it.
- **The scan stops when its coroutine is cancelled**, not only when Cancel is pressed. Before, a scan whose screen had been torn down kept running to the end with its decoder open.
- **Export of clips with a very large keyframe** (high-bitrate or 4K footage) failed because of a fixed 4 MB buffer. The buffer now doubles when a sample does not fit, up to 64 MB. *(Relies on `readSampleData` throwing `IllegalArgumentException` for a sample that is too large; not yet verified on a device.)*

### Performance and memory
- The clips are recomputed only when the cuts, the buffers or the video length change, not about 30 times a second while the video plays.
- `ShapeFinder` and `TimerEngine` got a `release()`. The shape finder used during setup is freed right after use, and the old timer engine is freed when a new setup replaces it or a new video is imported.
- `TimerVision.prep` always releases its temporary images, even when something fails part-way. `bitmapToGray` recycles the bitmap copy it makes.
- The scan's frame source (`CodecSource`) no longer asks the second extractor for the keyframe position when the next frame is just ahead of the decoder (the answer would always be "keep decoding").
- All exported clips reuse one sample buffer instead of allocating 4 MB per clip.
- The remembered row positions of the solves list are cleared whenever the list is cleared or a cut is deleted.

### Removed
- Unused code: the `LazyColumn`, `LazyRow` and `itemsIndexed` imports; `TimerStore.save()` and `TimerStore.load()` (the timer setup has not been saved since 1.1.0; `TimerStore.clear()` stays to delete files left by 1.x); the unreadable-stretch tracking in the scan (`UnreadableSpan`, `UNREADABLE_MIN_SEC`, `ScanResult.unreadable`, `ScanResult.framesRead`), which only fed the "check these parts" list removed in 2.0.0; `CutItem.manual` and `FoundSolve.digits`, `votes` and `reads`, which were never read.

### Notes
- Written without a compiler and not yet run on a phone at the time of writing (the app has since been built and run on the developer's phone); see section 9 of the project guide for what is still unconfirmed.
- Documentation updated: project guide (sections 0, 2.1, 2.1b, 2.3, 2.5, 2.6, 2.7, 3, 5, 7, 8, 9, 10, 11), README.
- Version bump still to do in `app/build.gradle.kts`: `versionName "2.0.1"` and a higher `versionCode`.

## [2.0.0] - date not recorded (developed on the `dev` branch)

The "first-frame cuts" version. The app no longer listens to sound at all.

### Added
- **Cuts come from the timer, not from sound.** The app reads the timer along the whole video. A non-zero time that stays on the display (the same time on at least two reads, nothing clearly different in between) is a finished solve, and the **first frame that shows that time** is the cut. The exact digits are decided by a vote over a few more reads, with a bias that counts a 4 or 7 double against its "1" look-alike (the reader's two known mistakes).
- **First-frame search**: walk back in 0.5 s steps to the nearest frame that showed something else, then a binary search down to one frame (1/60 s). Unreadable frames are skipped; a single odd reading is forgiven.
- **Solve-only clips.** Per solve you choose *solve only* (from the time on the timer plus a **before buffer** before the first frame) or *with scramble* (from where the previous cut ended). Every clip ends an **after buffer** after the first frame. Both buffers are sliders in the Setup box (default 2.5 s).
- **Two scramble controls:** a tick box per row and one pinned "Include scramble in all clips" switch (shows on / off / mixed).
- **Boundary rule:** the scramble of a clip starts at the end of the previous cut whether or not that cut is switched on, so a solve you left out is never dragged into the next clip. *Delete cut* removes a cut from the maths; a cut that the scan switched off because its time was already on the timer when the video began counts as deleted.
- **Cancel** for Find solves, and a status line with the time taken ("Done in 27s: 22 solves found, 1 to check").
- **Fast scan.** Find solves takes its pictures from one running `MediaCodec` decoder (`FrameSource.kt`) instead of asking `MediaMetadataRetriever` for every frame, with a startup check against the old picture (which also settles the rotation) and an automatic fall-back to the old way if anything is off. The test session went from about **107 s to about 27 s**. Also a wider step while the display shows zero (2 s), and "last position first" in the timer engine.
- **Review improvements:** *Prev / Next* scroll the solves list to the selected row; the Edit window moves the cut point in 0.5 s and 0.1 s steps and jumps to that frame; **+ Cut at playhead** snaps to the first frame that shows the time (up to 30 s back); a reminder after every scan to check that every solve was captured.
- Setup guidance: a bold instruction to set the timer up on a frame that shows an actual time after a solve, **not 0.000**, and a warning when a setup is saved from an all-zero number.
- Python prototype `pc_prototype/first_frame_scan_prototype.py` (scan, clip maths, noisy-timer simulator and tests) and the record `docs/FIRST_FRAME_CUTS_CHANGES.md`.

### Changed
- The screen was reorganised so the solves list gets as much room as possible: only the header, video, timeline, Prev/Next bar, the two main buttons and the all-clips switch stay pinned; the Setup box, progress, status and list scroll together; Export sits at the end of the list. Video height went from 30 % to 27 % of the screen.
- The timer setup is now the first thing on the screen (one *Set up timer* button inside the Setup box) because the whole scan depends on it.
- The Find solves confirmation estimates `0.3 × video length` (it was 6 s per sound).
- The timeline shows one marker per cut, on the first frame that shows the time, plus a strip that draws what each exported clip contains (dim = scramble, bright = solve with buffers).

### Removed
- All sound analysis (audio decoding, waveform, spike detection) and the **sensitivity slider**.
- The "check these parts of the video" list (replaced by the reminder after a scan) and the note "couldn't see exactly when this time appeared" (meaningless to a person).
- The second *Set up timer* button.
- Scanning the video in parallel pieces was built, measured (105 s with two workers against 107 s with one) and removed again.

## [1.1.0] - date not recorded

The timer-reading expansion (record: `docs/TIMER_READING_CHANGES.md`). The digit reading itself stayed as tuned for the QiYi timer.

### Added
- **csTimer and other seven-segment screens** (PC, phone, tablet), next to Stackmat-style LCD timers such as the QiYi Smart Timer.
- **The reader seam** (`DisplayReaders.kt`): the part that turns a straightened picture into digits sits behind a `DisplayReader` interface, so a reader for other fonts can be added later without touching the rest. `GlyphReader` is a disabled placeholder for it.
- **Fixed-camera mode** for plain screens that give the shape finder nothing to lock onto (the camera must stay still).
- **Snug read box** around the digits after learning, kept only if the typed number is still reproduced.
- **Variable-length numbers** (8 digit slots): one setup reads `9.87`, `12.34` and `1:02.34` (type `102.34` for 1:02.34 in the setup screen).
- **Setup continues with a warning** when the typed number cannot be reproduced exactly, instead of stopping.
- **A new full-screen setup** (`TimerSetupScreen.kt`): the whole frame in both steps, pinch zoom, two-finger pan, a **Fit** button, auto-zoom on the tapped display and a round magnifier while a corner dot is dragged.

### Changed
- Digits are found by column height (short shapes such as icons, text, the decimal point and a colon are ignored) and the row band is trimmed to the rows the digits cover, which fixed setups on csTimer screens with a camera icon in the box.
- **The timer setup is no longer saved.** It lasts until the app closes or another video is imported, so it is set up at every app start and every video import (the camera and window position change between sessions). Files left by 1.0.0 are deleted at start-up.

## [1.0.0] - date not recorded

The first version with the full phone app and the UI-polish milestone. Cuts were found by **sound** (a loud sound marked a candidate stop) and **confirmed by reading the timer**. Runs offline, with no AI and no internet permission.

### Added
- Import a session video with the system photo picker (no storage permission), a film strip and a progress window while it is read.
- **Find solves**: spikes in the sound, each checked by reading the QiYi-style timer (found by shape with ORB features and RANSAC, digits read by 7-segment classification, majority vote over about 27 frames); a **sensitivity** slider (default 4.0 on the phone) and a clip-length setting.
- **Set up timer**: mark the display's four corners and type the number it shows, so other seven-segment timers can work too; the setup was saved on the phone.
- A **review screen**: switch each solve on or off, edit its time, nudge its cut point, add a missed cut by hand, with a timeline of markers and a red playhead.
- **Lossless export** (no re-encoding) to `Movies/CubeCut`, one file per solve named with its time (`Solve_03_11.263s.mp4`); a solve with no known time exports as `unknown` and can be given a time with *Add time*.
- Polish: logo and launcher icon; collapsible Setup box; pinned video and timeline with a separately scrolling list; a *Prev / Next* bar; Export at the end of the list; a warning window before Find solves; keyword highlighting of notes that need a look; an "export finished" window; pinch-to-zoom and double-tap-to-reset on the video (a TextureView from an XML layout); Find solves keeps running while you use another app (foreground service with a progress notification and a "finished" notification; Android 13+ notification permission asked once); the screen stays awake during long jobs.
- A Python prototype of the sound method, `pc_prototype/cubecut.py`, and `generic_reader_prototype.py`, which mirrors the Kotlin digit reader.
