# Changelog

All notable user-visible changes to CubeCut. The format follows [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

While you work, add lines under **Unreleased**. On release, rename it to the version and date ([release steps](docs/android.md#release-process)).

Notes on history:
- 1.0.0, 1.1.0 and 2.0.0 were reconstructed after the fact and their dates were not recorded.
- Tag `v1.0` actually holds the 1.1.0 code (it already contains `DisplayReaders.kt`). There is no `v2.0.0` tag.
- `versionCode` was 2 for 2.0–2.0.2 and is 3 at both `v3.0.0` and `v3.0.1` (`versionName "3.0.0"` at both). It isn't known whether the shared 3.0.1 APK had code 3 or 4 (from a bump that was never committed). 3.1.0 uses 5, which is higher than either.
- 3.0.0 was built in phases (saved sessions, Gallery, re-scan merge, editor/Gallery split, new look, review fixes, test-pass fixes). Its entry describes the end result, not each phase. The phase-by-phase notes are in the git history of this file.

## [Unreleased]

### Added
- **Puzzle tags.** A video gets a puzzle (3x3 to 7x7, Megaminx, Pyraminx, Skewb, Square-1, Clock, FTO, or *Other* with your own name) and a type (Normal, OH, BLD, Relay, PLL attack, or *Other*). Its clips follow it.
- A new video asks **Which puzzle is this?** at import (*Decide later* skips it). Change it in the editor's Setup box or in a video's ⋮ menu.
- Mixed footage: each solve card in the editor has a tick for the video's puzzle. Untick a solve that is a different puzzle; it is then not tagged.
- Tag clips in the Gallery: **Set puzzle…** while ticking clips, and **Change** next to *Puzzle* in the viewer's details.
- **Filter** by puzzle and by type, including *Not tagged*. Clip and video cards show the tag.
- Exported files of a tagged clip carry the tag after the title: `Sunday practice_3x3 OH_Solve_03_11.263s.mp4`.
- **Penalties (+2 / DNF)** in the editor's *Edit* window and in the viewer's details. Tap *+2* again for another +2. Times show as `13.263 (+2)`, `15.263 (+2×2)` or `DNF(11.263)`. The solve-only clip still starts from the time on the timer.
- Sorting by solve time and a video's *best* count the penalty; a DNF sorts last and is never *best*.
- **Buffers for one clip:** *Follow video* or *Custom* (own before / after sliders), in the editor's *Edit* window and in the viewer's details. Its card says *own buffers*.
- Changing an exported clip's buffers in the viewer asks **Re-export now?** After the new file is saved, the old one can go to the phone's trash (after a warning) or stay.
- A penalty on an exported clip **renames its file** (`…_Solve_03_13.263s_plus2.mp4`, `…_Solve_03_DNF_11.263s.mp4`). The clip stays linked to it. After a reinstall Android asks first.

- **Shared on:** the viewer's platform buttons now mark where a clip was shared, separately from the note. *Filter* → *Shared on: TikTok* (or any platform) or *Not shared yet*.

### Changed
- The platform buttons no longer add words to your note. Notes you wrote before keep their text; where they named a platform, the clip already counts as shared there.
- The saved data is upgraded to database v7, ready for 3.2's tags, penalties, per-clip buffers and *Shared on*. Nothing looks different yet: solves, notes, exports and clips stay exactly as they were. *Shared on* starts from the platform names already in each note.

## [3.1.0] - 2026-11-23

Polish and quick wins. How solves are found, how digits are read and how clips are calculated did **not** change. The saved data is unchanged (database v6), so everything from 3.0 carries over.

### Added
- **Add a missed solve at the playhead** shows as soon as a video is loaded, so solves can be added by hand before Find solves or without a timer setup. The two switches still wait until there are solves.
- A solve added by hand is selected, the list scrolls to its card, and the card blinks a few times. If *Only solves to check* would hide it, the filter turns off.
- **Replay** in the clip viewer (next to *Skip*): clips loop while it is on. The choice is remembered.
- **Double-tap to skip** in the clip viewer: the left half skips back, the right half forward, by the *Skip* length. Further quick taps keep skipping, and a hint shows the total (−5 s, −10 s…). A single tap now shows or hides the controls about 0.3 s later.
- **Delete exported files from the Gallery selection:** a red bin next to *Export* moves the files of the ticked clips to the phone's trash, after a warning and one Android confirmation. Solves and notes stay; restore from *Recently deleted*.
- The **Edit window** zooms its picture onto the timer when a timer setup is saved. **Whole frame** shows the full picture.
- Importing a video CubeCut has seen before asks first: **Imported before** shows its saved solves and last scan, with *Open saved* / *Cancel*.

### Changed
- **Only solves to check** keeps its list while it is on. A solve that no longer needs a check stays, marked **✓ Fixed** or **✓ Checked**, instead of vanishing. Switch the filter off and on to tidy the list.
- **It's fine** keeps the card in place and jumps to the next solve that still needs a check.
- After a delete, the Undo bar says which solve is now selected: *Deleted the 12.34 s solve · now on Solve 7 (9.87 s)*.
- *Export only* and *Solve deleted* clips can now be ticked in the Gallery (for the bin). *Export* skips them and says so.
- The Gallery's scramble switch, with every clip of a video ticked, now works like the editor's *Scramble in all clips*: a later *Find solves* still updates those solves and new ones follow the setting.
- Documentation baseline: `CLAUDE.md`, `ARCHITECTURE.md`, `CONTRIBUTING.md`, `app/README.md`, and `docs/` (algorithms, data model, Android/release, glossary, user guide, known issues, roadmap, ADRs 0001–0014).

### Fixed
- Opening a clip from the Gallery no longer sometimes flashes the top and bottom of the screen for a moment.
- *Find solves* no longer says *again* (and the result no longer says *Re-scan*) when only solves added by hand exist and it has never run on the video.

### Removed
- `CUBECUT_PROJECT_GUIDE.md`. Its content was moved into the files above; personal notes and edition history were dropped.

## [3.0.1] - 2026-11-11

How solves are found, how digits are read and how clips are calculated did **not** change.

### Changed
- Playing the video no longer changes the selected solve in the editor, and the list no longer scrolls by itself. The arrows step from the selected solve.
- Tapping the timeline selects the solve there and scrolls the list to its card, playing or paused. A drag selects where the finger is let go. Tapping a spot with no solve keeps the selection.
- The timeline has a new look:
  - markers size themselves to the room available;
  - solves that need a check get **amber** markers;
  - clip bars are rounded, on a faint lane;
  - the selected clip is outlined in white;
  - the playhead is red with a triangle head;
  - the panel is rounded and stays dark in both themes.

## [3.0.0] - 2026-11-10

Saved sessions and the Gallery. How solves are found, how digits are read and how clips are calculated did **not** change. **Requires Android 11 or newer.**

### Added
- **Saved sessions** (SQLite, `SessionStore.kt`). Every imported video, its cuts, buffers, scramble choice, export records and **timer setup** are remembered. Changes save about 0.4 s after they happen. Re-importing a known video brings everything back. Videos are recognised by link, then by name, size and length, then by size and length.
- **Gallery as the start screen**:
  - **Clips** tab: grid or list, solve time on each picture, pinch for 2–6 columns.
  - Sort by up to three keys (date, fastest, slowest, session, order in video, needs check, exported or not).
  - Filter by video and export state, with chips.
  - **Footage** tab: raw videos with date, length, solve count, best time and checks. Tap to open in the editor; ⋮ for options.
  - Refreshes on every return to the app.
- **Full-screen clip viewer**:
  - autoplays; swipe left and right between clips; CubeCut's own icon controls with adjustable skip seconds;
  - a swipe-up sheet with the scramble switch, *Export* / *Editor* / *Delete file*, a note with social-media buttons, and the video it came from.
- **Choosing and exporting in the Gallery**:
  - hold and drag to tick clips;
  - preview while choosing;
  - set the scramble for all ticked clips;
  - batch export.

  Clips exported before are pointed out, and *Export only the N new* is offered. File names start with the video's title, e.g. `Sunday practice_Solve_03_11.263s.mp4`.
- **Exported tracking:** *Exported*, *Export only* (raw video gone) and *Solve deleted* (solve deleted, file kept) tags.
- **Re-scans keep your edits.** Find solves on a saved video merges with it: edited and exported solves stay, untouched ones update, new ones are added, and solves not found again are flagged.
- **Timing checks** on both solves of a pair: *too close* (no clip), *overlap*, and *too fast* (under 6 s apart). *Only show solves to check*, *It's fine* and *Mark all N as checked* go with them.
- **Delete with Undo** in the editor. Deleting an **exported** solve asks whether to keep or trash its file.
- **Add a missed solve at the playhead** asks for the time, and refuses spots inside or overlapping another solve.
- **Edit window** shows the frame the cut is on and moves the cut in 0.1 s and 0.5 s steps. An exported solve shows *Already exported*.
- **Move to trash / Restore / Recently deleted** for videos and exported clips, each behind a visual warning. *Reconnect video*, *Rename*, *Forget this session*.
- **Dark mode** (System / Light / Dark) and a logo-blue and grey theme (`Theme.kt`).
- Shared note style (`Notes.kt`), with "i" buttons for longer explanations.
- A "you were just here" highlight when returning to the Gallery.
- The **author credit and About window** in the Gallery too.

### Changed
- The editor only identifies solves. Choosing, the per-clip scramble and export moved to the Gallery. Each solve card has a scramble button, **Edit**, **View** and a red delete button. Solves switched off in earlier versions become normal solves; only *left over* cuts stay out.
- *Include the scramble in all clips* sits above the solve list and sets every clip.
- Editor layout:
  - Pinned: video, timeline, a review bar with arrow buttons, the Undo bar, the add-solve button and the two switches (after Find solves has run).
  - Scrolling: the Setup box, Find solves and the list.
- *+ Cut at playhead* was renamed **Add a missed solve at the playhead** and no longer reads the video.
- Import uses the system file picker (`OpenDocument`) with persistent access, instead of the photo picker.
- Video and audio behave like other players: CubeCut takes audio focus, unplugging headphones pauses playback, and leaving the app pauses playback.
- Find solves' background notification is labelled *media processing* on Android 15+ and stops cleanly when Android's daily limit runs out.
- **Portrait only.** Dark-mode, font-size and split-screen changes no longer restart the editor or a running scan.
- **No backups:** CubeCut's data is excluded from Google backup and phone-to-phone transfer.
- **Android 11+ (`minSdk 30`, was 24).**
- Shorter wording throughout the app.

### Fixed
- Saving a new timer setup or importing while *Read timer here* was still reading could free memory in use (a rare crash).
- Exported file names use ASCII digits whatever the phone's language.
- The recorded file name now matches the one Android actually gave (" (1)" suffixes).
- An export Android couldn't finish is reported as failed and removed.
- No white flash before the first screen in dark mode.
- Video audio kept playing after leaving the app.

### Removed
- The editor's export switch, per-row *Include scramble* box and Export button.
- The automatic reading behind *+ Cut at playhead* (`SolveAnalyzer.locate`, `analyzeOne`, `ManualCut`).
- Android 7–10 code paths, including the app-only export folder.
- `res/xml/backup_rules.xml`.

## [2.0.2] - 2026-11-03

### Changed
- OpenCV is limited to `arm64-v8a`, which makes the APK much smaller. 32-bit phones are no longer supported.
- Licensed under **GPL-3.0** with additional terms that keep the author credit (`LICENSE`, `NOTICE.md`). The app shows "CubeCut by DadCuber" with an About window. These were added on 2026-11-06 without a version bump.

### Fixed
- Exported clips no longer go out of sync in some video editors. Audio now starts at the keyframe the picture really starts on.

## [2.0.1] - 2026-11-03

How solves are found, how digits are read and how clips are calculated did **not** change.

### Changed
- Find solves shows one progress card, which has the Cancel button. The Setup box folds as soon as a scan starts.
- Clips are recomputed only when cuts or buffers change, not on every playhead tick.

### Fixed
- Importing a video during a scan no longer fills the new video with the old one's solves. Import is disabled during scans and exports.
- *Set up timer* and *Read timer here* are disabled during a scan, because they share non-thread-safe OpenCV objects.
- The scan stops when its screen is torn down, not only on Cancel.
- Clips with very large keyframes export. The sample buffer grows from 4 MB to 64 MB.
- Native OpenCV memory is released explicitly (`ShapeFinder.release`, `TimerEngine.release`, `prep`).

### Removed
- Unused code: `TimerStore.save` / `load`, the unreadable-stretch tracking, `CutItem.manual`, and unused `FoundSolve` fields.

## [2.0.0] - date not recorded

"First-frame cuts": the app no longer analyses sound.

### Added
- Cuts come from reading the timer along the whole video. The first frame showing a held, non-zero time is the cut. A vote biased toward 4 and 7 over 1 decides the digits.
- Solve-only clips, counted back from the time, plus before and after buffers (default 2.5 s). With-scramble clips start where the previous cut ended, whether or not that cut is exported.
- A per-row scramble tick and an all-clips switch.
- Cancel for Find solves, and a reminder to check that every solve was captured.
- A fast scan: one running `MediaCodec` decoder with an automatic fallback. The test session went from about 107 s to 27 s.

### Changed
- Timer setup comes first and asks for a frame showing a real final time, not 0.000.
- The screen was reorganised so the solve list gets the most room.

### Removed
- Sound analysis and the sensitivity slider.
- The "check these parts of the video" list.
- The second *Set up timer* button.

## [1.1.0] - date not recorded

### Added
- csTimer and other seven-segment screens, alongside Stackmat-style LCD timers.
- The `DisplayReader` seam for other fonts later (`GlyphReader` placeholder).
- Fixed-camera mode for plain screens.
- A snug read box.
- Variable-length numbers (up to 8 digits; type `102.34` for 1:02.34 in setup).
- Setup saves with a warning instead of failing.
- A new zoomable setup screen with a magnifier.

### Changed
- Digits are found by column height, so icons, the decimal point and colons are ignored.
- The timer setup is no longer saved between app starts. This was reverted in 3.0.0, which saves it per video.

## [1.0.0] - date not recorded

### Added
- The first full phone app. Cuts were found by **sound** and confirmed by reading the timer: ORB shape finding plus seven-segment reading.
- A review screen.
- Lossless export to `Movies/CubeCut`, with the time in each file name.
- A background notification during Find solves.
- Pinch-zoom video.
- Runs offline with no internet permission.

[Unreleased]: https://github.com/justinihil/CubeCut/compare/v3.1.0...HEAD
[3.1.0]: https://github.com/justinihil/CubeCut/compare/v3.0.1...v3.1.0
[3.0.1]: https://github.com/justinihil/CubeCut/compare/v3.0.0...v3.0.1
[3.0.0]: https://github.com/justinihil/CubeCut/compare/v2.0.2...v3.0.0
[2.0.2]: https://github.com/justinihil/CubeCut/compare/v2.0.1...v2.0.2
[2.0.1]: https://github.com/justinihil/CubeCut/compare/v1.0...v2.0.1
[1.1.0]: https://github.com/justinihil/CubeCut/tree/v1.0
