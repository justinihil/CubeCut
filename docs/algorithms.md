# Algorithms

The behaviour that is hard to see from reading the code alone, plus the numbers it was tuned against. Constants are named so you can find them; their **values live in the code**, and the code wins if this page disagrees with it.

Contents:
1. Finding solves
2. Where frames come from
3. Reading the timer
4. Learning a timer (setup)
5. Clips
6. Viewer still-to-video handover
7. Tuning map
8. Reference measurements

---

## 1. Finding solves

Entry point: `SolveAnalyzer.analyze`, which calls `scan`. The scan only needs a function `read(t)` that returns the digits shown at second `t` (for example `"12412"`), an all-zero string for 0.000, or `null` when unreadable. In the app, `read` is `memoReader` (memoised per millisecond) over a `GraySource` plus `TimerEngine.readDigitsGray`. That separation keeps the decision logic testable without a phone: `SolveAnalyzerScanTest` runs it on a simulated display (`FakeDisplay`, see [§ 8](#8-reference-measurements)).

1. **Coarse scan.** Read every `SCAN_STEP` (0.5 s). The step widens to `ZERO_STEP` (2 s) while the display shows zero, and to `HOLD_STEP` (2 s) while it still shows an already-found time. An unreadable frame keeps the 0.5 s step.
2. **Stopped timer.** The same non-zero time on at least `MIN_AGREE` (2) of the last `RECENT_N` (6) readable reads, within `AGREE_WINDOW_SEC` (6 s), with **no clearly different reading in between**. A running timer never agrees with itself. *Look-alikes* (same length, one digit differs by 1↔4 or 1↔7, `confusable`) do not break a run. These are the reader's two known errors.
3. **Vote.** Read `LOCK_SEC` (3 s) more at 0.5 s steps and count. `pickWinner` / `beats` count a version containing 4 or 7 **double** against its "1" look-alike, because the reader turns 4/7 into 1 but never the reverse. Under 60 % agreement (inline in `scan`) adds the note "time not steady … check it".
4. **First frame** (`firstAppearance`). From the earliest read of the winner, walk back in `COARSE_BACK_STEP` (0.5 s) steps to the nearest frame showing something else. Then `bisect` down to one frame (`FINE_STEP` = 1/60 s), which takes about 5 reads.
   - If a middle frame is unreadable, it falls back to `walkBack` at `BACK_STEP` (0.1 s), then frame by frame within `FINE_WINDOW`.
   - Two readable contradicting frames in a row end a walk; a single odd reading is forgiven.
   - It never goes back more than `BACK_MAX_SEC` (4 s).
   - The result is `stopSec`: **the cut**.
5. **Move on.** Look-alikes of the found time count as the same hold for up to `HOLD_MERGE_SEC` (12 s), so a misread of one hold never becomes a second solve. A zero or a clearly different time ends the hold.
6. **Left-over.** A time already on the display when the video starts (no earlier frame differs, within the first second) is switched off with a note starting `LEFTOVER_NOTE_START`. `isLeftover()` identifies it. It counts as deleted in the clip maths while it stays off.

Cancellation: `isCancelled` is polled before each coarse read and each vote read, and throws `ScanCancelled`. The first-frame search of the current solve finishes first. `ScanResult.readableFraction` below 25 % triggers the amber "timer hard to read" note.

**Known weakness:** a held time read without its last digit (`1151` while `11517` is held) can become a separate solve. With a quarter of the held frames short, the simulator splits the hold in 132 of 200 runs. In 1 of them the short read also won the vote (a tie goes to the read that triggered the stop), so the real time was never reported. The too-fast check (see [§ Checks](#checks-after-a-scan)) flags it afterwards but doesn't prevent it. The ignored test `heldTimeMissingLastDigit_isTheSameHold` reproduces it for [3.3-B](roadmap.md).

### Checks after a scan

`checkCuts` (`RescanMerge.kt`) looks at the real solves in time order, skipping left-overs. It flags **both** solves of a pair:

| Check | Condition |
|---|---|
| **too close** | `computeClips` made no clip for the solve, because it ends less than 1 s after the previous clip |
| **overlap** | `stop − time < previous stop − SLACK_SEC` |
| **too fast** | Ends less than `MIN_SOLVE_CYCLE_SEC` (6 s) after the previous solve. Only reported when the pair has no other reason. |

A cut marked `reviewed` is not flagged again, except for "no clip". `CutItem.noteNeedsCheck()` and the warnings together decide "needs a check" everywhere: editor filter, status count, Gallery tag and timeline amber marker.

### Re-scan merge
`mergeRescan` matches found solves against saved cuts by overlapping **solve spans** (`stop − time` to `stop`). The slack is `SLACK_SEC` (0.3 s), or `SLACK_UNKNOWN_SEC` (1.5 s) when a time is unknown. The scramble is never used, because a missed solve can sit inside the next solve's scramble.

| Saved cut | What happens on re-scan |
|---|---|
| `edited` or exported | Kept as it is |
| Untouched | Takes the found values |
| Unmatched found solve | Added |
| Untouched and not found again | Kept, with `NOT_FOUND_AGAIN_NOTE` |

Deleted cuts leave no trace, so a re-scan can bring one back. That was a deliberate choice. Rationale: [ADR-0013](adr/0013-rescan-merge-keeps-user-edits.md).

## 2. Where frames come from

`GraySource.gray(t)` returns the whole upright grey picture as an OpenCV `Mat`. **The caller releases it.** `openFrameSource` returns an `AutoSource`:

- **`CodecSource`**: one `MediaCodec` decoder that keeps running, with no surface. It reads the Y plane via `getOutputImage`, honouring the crop and stride, with no Bitmap.
  - It only jumps when the target is behind the decoder or more than `SEEK_PAYS_OFF_US` (0.3 s) ahead. A second extractor (`probe`) finds the keyframe.
  - Limited-range video (16–235) is stretched to 0–255.
  - The rotation tag is not applied by the decoder, so the picture is rotated here.
- **`RetrieverSource`**: `MediaMetadataRetriever.getFrameAtTime(OPTION_CLOSEST)`. It works everywhere but takes about 250 ms per read. It is the fallback and is also used by `calibrate`.
- **`calibrate`**: at 50 %, then 30 %, then 70 % of the video, it takes the first frame with detail (std-dev ≥ 10) and compares the two sources.
  - Both frames are resized to 96×96 and compared by normalised correlation, at each of the 4 rotations.
  - It needs a match of at least 0.85, which also settles the rotation.
  - If the check fails, the decoder throws, or it stalls for about 4 s, the scan switches to `RetrieverSource` for good.
  - Logcat tag `CubeCut`, lines starting `Frame source:`, say which source is used and why.

`TimerEngine.readDigitsGray` first tries the display's previous position (`lastQuad`). It re-runs the shape finder every `REFIND_EVERY` (20) quick reads, or whenever a quick read fails. This is not used in fixed-camera mode. `forgetPosition()` is called at the start of each scan.

**Tried and removed:** scanning in 2–3 parallel pieces gave no gain (105 s vs 107 s), because the hardware decoder is the bottleneck. **Declined by the owner:** a cropped ORB search near the last position.

## 3. Reading the timer

For each frame:
1. **Find the display.**
   - **Shape tracking** (`ShapeFinder`): ORB features plus a RANSAC homography against a reference picture of the timer body, with the display masked out. It needs at least 14 matches and a reference with at least 30 features. Tested to cope with roughly 0.8×–1.4× zoom and mild side angles.
   - **Fixed-camera mode** (`fixedPosition`): used when the reference has under 30 features, as with a plain PC or phone screen. The saved corners are used as-is, so the camera and the window must not move.
2. **Straighten** the four corners into a canvas 380 px wide (`CANVAS_W`), 80–260 px tall. `readCorners` (a snug box) is read; `corners` (the box the user marked) is tracked.
3. **Prep** (`TimerVision.prep`): contrast stretch (1st–99th percentile), optional invert, shear to undo italic, blur, then an adaptive threshold.
4. **Digit columns** (`groups`):
   - A column counts if it is at least 11.5 % lit and its lit height is at least 35 % of the tallest column. This drops icons, text, the decimal point and colons.
   - Neighbouring columns (gap ≤ 2 px) are grouped, and groups under 8 px are dropped.
   - `refineBand` trims the row band to the rows the digits cover.
5. **Cells.** The offsets learned at setup, measured from the rightmost digit, are stretched to `MAX_SLOTS` (8) using the median pitch. So one setup reads `9.87`, `12.34` and `1:02.34`. At least `max(3, decimals + 1)` digits are required.
6. **Classify.** Seven-segment fill fractions decide the digit. Narrow cells are 1, 7 (thin top bar) or a 4 with faded left strokes. A missing inner digit gets one more, more sensitive look.
7. If any digit is unclear, the whole frame returns `null`.

The reader-specific part (steps 4–6) sits behind `DisplayReader` ([ADR-0014](adr/0014-display-reader-seam.md)). `SegmentReader` (id `"segment"`) is the only working reader. `GlyphReader` (id `"glyph"`, `available = false`) is a placeholder for any-font reading.

**Solid digits** (csTimer, phone apps; `SolidDigits.kt`, 3.3-C). A separate reader that needs no display position. It isn't used by *Find solves* yet.
1. Black-and-white by comparing each pixel with the average around it (`SEARCH_BLOCKS`, three sizes), for light and dark digits.
2. Joined blobs of digit size are chained into rows; a row needs exactly one decimal point.
3. The row's slope is undone, and each blob is scaled to 24 × 28 and compared with example digits. **All digits of a row lean the same way**, so the lean that suits the whole row counts.
4. `pickRow`: the reading most block sizes agree on.

The examples come from 11 open fonts, or only from the video itself once all ten digits were learned ([ADR-0016](adr/0016-find-the-timer-without-ml.md)). Measured on 150 whole frames of held times from the three csTimer videos, without csTimer's look-alike font and without a position: 150 right, about 80 ms per frame on a PC (`ReferenceFramesTest`).

**Debugging tip:** before blaming a font, look at the straightened black-and-white picture that *Read timer here* shows. An earlier "italic touching font" theory turned out wrong when that picture was measured.

## 4. Learning a timer (setup)

`TimerVision.createSetup(frame, corners, typed)`:
1. Cuts a reference picture around the display (about 5.2 × 2.6 display widths) and builds a `ShapeFinder`. If the reference has under 30 features, it uses fixed-camera mode.
2. Tries each available reader in `DisplayReaders.all`. The first strict `learn()` that **reproduces the typed number exactly** wins. For segments, it tries:
   - light and dark;
   - the two sharpest shear values in `SHEARS`;
   - every split of the stroke groups into the typed digit count.

   It keeps only layouts that look like a real timer (`plausibleLayout`): even spacing within 22 %, decimal gap 0.95–1.9× pitch, colon gap 0.95–2.4×, digit width ≤ 16 % of the canvas, digits inside 10.5–91.8 % of it.
3. If nothing reproduces the number, `learnUnverified` saves the closest layout **with a warning**. Setup fails outright only when no digit-like shapes fit at all.
4. `tightenQuad` (verified layouts only) builds a snug box: digits take 78 % of the width, with 12 % / 10 % side margins and an 18 % vertical margin. The snug box is kept only if the typed number is still reproduced.
5. A test read of the setup frame is shown in the message.

Typing convention: the number box has no colon, so `102.34` means 1:02.34 **in the setup screen only**. Elsewhere `parseTimeSeconds` reads `102.34` as 102.34 s (see [known-issues](known-issues.md)).

**Rule for users:** set up on a frame that shows a **real final time**, not 0.000. Zeros give the learner nothing to tell digits apart, and an all-zero typed number adds a warning to the message.

**Persistence:** since 3.0.0 only the frame time, the corners and the typed number are saved per video (`SessionStore.saveTimerSetup`). Opening the video grabs that frame again and re-runs `createSetup`. The learned `DigitLayout` itself is never stored.

## 5. Clips

`computeClips(cuts, beforePad, afterPad, durationSec)` (`ClipExporter.kt`) walks the cuts in time order. Clips are never stored ([ADR-0006](adr/0006-lossless-export-to-mediastore.md)).

| Part | Rule |
|---|---|
| **End** | `stopSec + afterPad`, clamped to the video end |
| **Solve-only start** | `stopSec − time − beforePad`, clamped to 0. The time comes from `parseTimeSeconds(timeText)`: `12.112` → 12.112 s, `1:02.345` → 62.345 s |
| **With-scramble start** | Where the **previous cut** ended (0:00 for the first), but never later than the solve-only start |
| **No time known** | Treated as with-scramble and flagged `noTime` |

- **The boundary rule:** "previous cut" means the previous cut whether included or not. Left-over cuts are skipped like deleted ones. This was the owner's fix for an earlier bug that dragged a left-out solve into the next clip.
- A cut ending less than 1 s after the previous clip gets **no clip**, which is the *too close* check.
- **Lossless export:** a clip must start on a keyframe, so it can begin up to about 1 s early. Audio is re-aligned to the keyframe the picture really starts on (`AUDIO_SLACK_US`), which fixed audio drift in some editors in 2.0.2. One `SampleBuffer` per `export()` call grows from 4 MB to 64 MB. A keyframe over 64 MB fails.
- **File name:** `fileName(clip, prefix)` gives `<safeTitle(prefix, ≤40 chars)>_Solve_<NN>_<time>s.mp4`. `:` becomes `m`, an unknown time becomes `unknown`, and `Locale.ROOT` is used. `NN` = `cutIndex + 1`, which matches the Gallery's "Solve N".

## 6. Viewer still-to-video handover

This is the black-flash bug that took five rounds to fix. In `ClipViewer`:
- Each pager page has **one** `Thumb` still that is never removed. It stays at full alpha over the player until `onRenderedFirstFrame` for that clip, then fades after 0.15 s.
- The still is the clip's **own first picture** (`stillMsFor` = clip start).
- `activePage` follows the pager only once scrolling has stopped.
- `PlayerView` has a transparent shutter and `setKeepContentOnPlayerReset(true)`.
- `ThumbCache.peek` lets the first composition draw a picture that is already in memory.
- The still's **background is black, also on the playing page**, and it fades with the still. A player that has never played (the viewer just opened from the Gallery) doesn't know the video's size when it draws its first frame. For 1–2 frames (about 17 ms at 120 fps) the `TextureView` fills the whole page, so the picture is stretched into the top and bottom bands. A transparent background let that show as a flash at the top and bottom on about 6 of 8 opens. Swiping is not affected, because the player already knows the size. Fixed in 3.1-C (2026-11-23).

**Method:** if you touch this, record the screen at 120 fps and step through every frame (ffmpeg `-fps_mode passthrough`). A single black frame is invisible when sampled at normal speed. The real cause was found only this way: the still was being re-created on the page that became active.

## 7. Tuning map

| Area | Where |
|---|---|
| Scan speed vs. catching short holds | Top of `SolveAnalyzer.kt` (`SCAN_STEP`, `ZERO_STEP`, `HOLD_STEP`, `MIN_AGREE`, `RECENT_N`, `AGREE_WINDOW_SEC`, `LOCK_SEC`, `HOLD_MERGE_SEC`, first-frame steps); "not steady" 60 % inline in `scan` |
| Vote bias, look-alikes | `beats` / `pickWinner` / `confusable` in `SolveAnalyzer.kt` |
| Frame source | `SEEK_PAYS_OFF_US`, the 0.85 threshold in `calibrate` (`FrameSource.kt`); `REFIND_EVERY` in `TimerEngine` |
| Digit thresholds | Inline numbers in `TimerVision` (`TimerReader.kt`); `SHEARS`, `plausibleLayout` |
| Shape finder | `ShapeFinder`: `minMatches` 14, ORB 2500 features, scale 0.5 |
| Checks and merge | `SLACK_SEC`, `SLACK_UNKNOWN_SEC`, `MIN_SOLVE_CYCLE_SEC` (`RescanMerge.kt`); `SAME_SOLVE_SEC` (`ClipExporter.kt`) |
| Clip maths and export | `computeClips`; `START_BUFFER` / `MAX_BUFFER` / `AUDIO_SLACK_US` (`ClipExporter`) |
| Defaults | `DEFAULT_INCLUDE_SCRAMBLE` (`MainActivity.kt`); buffer defaults 2.5 s in the schema and editor state |
| Find-solves estimate | `estMin` = 0.3 × video length (`MainActivity.kt`); overestimates since the speed-up |
| Editor layout | Video height `0.27f` (`MainActivity.kt`); timeline `64.dp` / film strip `38.dp`, `CROWDED_DP` / `ROOMY_DP` (`Timeline.kt`) |
| Viewer taps | `DOUBLE_TAP_MS`, `SKIP_STREAK_MS`, `SKIP_HINT_MS` (top of `Gallery.kt`) |
| Gallery and thumbnails | `VIEWER_PICTURE_HEIGHT`, `MIN_COLUMNS` / `MAX_COLUMNS` (`Gallery.kt`); `TARGET_HEIGHT`, `DISK_LIMIT` / `DISK_TRIM_TO` / `TRIM_EVERY`, the `Semaphore(2)` (`Thumbnails.kt`) |
| Video identity | Duration tolerances in `openOrCreate` (1000 ms) and `relink` (1500 ms) (`SessionStore.kt`) |

## 8. Reference measurements

These were measured on the owner's Galaxy S25 Ultra with a 1080×1920, 60 fps HEVC session of about 22 solves.

| Frame source | Whole scan | Getting frames | Shape finder | Digits |
|---|---|---|---|---|
| `MediaMetadataRetriever` + last-position shortcut | 107.2 s | 92.0 s (368 reads) | 11.6 s | 1.3 s |
| Two parallel workers (removed) | 105.2 s | 87.4 + 95.6 s | 6.3 + 7.3 s | 0.7 + 0.8 s |
| `MediaCodec` (current) | 26.8 s | 15.0 s (358 reads) | 10.2 s | 1.3 s |

**Known-good clips** (regression references; the videos are not in the repo):
- `My_movie_7.mp4` (212 s, dim, hands often cover the display; set up at about 0:35 showing 12.412) should give exactly six solves: `12.412`, `11.435`, `11.263`, `12.151`, `20.343`, `19.316`. The 12.412 solve sometimes reads `12.112`, and the 4-bias vote should settle it.
- `20261005_131422_1.mp4` (69 s) should give `11.108` and `14.338`.
- TODO(owner): record the expected first-frame times and the `Done in …s` line for these, as the guide asked.

### Ground-truth harness (3.3-A)

Every scan change is measured twice: on a simulated display in the unit tests, and on the owner's reference videos with the debug scan report.

**Simulated baseline** (`SolveAnalyzerScanTest.noisySessions_baseline`; `FakeDisplay` in `src/test`). The setup is 40 sessions × 22 solves, 60 fps, 12 % unreadable frames, and 20 % of frames with one 4/7 read as 1. These are the old Python prototype's settings; the prototype isn't in the repo and claimed 876/880 found, 0 false, every cut within 0.25 s.

| Measured | Found | False | Wrong time | Cut on the frame (≤ 0.02 s) | Within 0.1 s | Worst cut |
|---|---|---|---|---|---|---|
| 3.3-A (2026-11-23) | 880 / 880 | 0 | 2 | 809 | 871 | 0.183 s |

The test fails if a change lowers *found*, *on the frame* or *within 0.1 s*, or raises *false* or *wrong time*; update its floors and this table when a change improves them. The off-frame cuts by cause (same 880 solves, measured 2026-11-24):

| Noise | On the frame | Early | Late | Worst early / late |
|---|---|---|---|---|
| none | 875 | 5 | 0 | 0.067 s / – |
| 20 % misreads only | 876 | 4 | 0 | 0.033 s / – |
| 12 % unreadable only | 809 | 6 | 65 | 0.067 s / 0.183 s |
| both | 809 | 6 | 65 | 0.067 s / 0.183 s |

So the late cuts come from unreadable frames, and misreads add none. The few early ones also happen without noise: a running frame shortly before the stop can differ from the final time by one 1↔4 or 1↔7, which `similar` accepts. Part of the late error can't be avoided when the first frame itself is unreadable. (An earlier version of this page blamed misreads for all of them. That was never measured and was wrong.)

**Real-footage baseline.** Owner priority: csTimer on a PC, then phone timer apps, then Stackmat, then GAN / QiYi. One row per video, from its report; a timer that isn't found counts as 0 found. The csTimer rows are **not phone runs**: today's app can't search for a timer, and csTimer's font can't be set up by hand, so the result is known without running it. TODO(owner): QiYi baseline (not recorded yet, 2026-11-24).

| Video (timer) | Timer found | Reading: right / wrong / unreadable | Expected | Found | Missed | False | Wrong time | On the frame | Readable | Scan time | Build |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `ref_cstimer_old_01.mp4` (csTimer; 21:10, dim room, scramble PDF shown over it) | no | – | 25 | 0 | 25 | 0 | 0 | – | – | – | 89c9a3d, not run |
| `ref_cstimer_old_02.mp4` (csTimer; 19:12, brighter) | no | – | 25 | 0 | 25 | 0 | 0 | – | – | – | 89c9a3d, not run |
| `ref_cstimer_old_03.mp4` (csTimer; 18:45, brighter) | no | – | 25 | 0 | 25 | 0 | 0 | – | – | – | 89c9a3d, not run |

**How it works.** An answer key says what a video should give, and the debug *Scan report* (under *Find solves* in the editor) grades the app against it **without changing the list**. Each stage depends on the one before, so the report shows where a video fails:
1. **Timer:** was it found? Until 3.3-C the app can't search by itself, so this is the manual setup, tried again from scratch. None, or a refused one, is *NOT FOUND*, and the report still runs.
2. **Reading:** the display is read 6 times in the 1.1 s after every expected stop (`gradeReading`): right, wrong or unreadable. Reset displays are left out.
3. **Scan:** Find solves from scratch: found, missed, false, wrong times, off-frame cuts (`compareScan`).

**Two kinds of answer key:**

| | List of times (`compareTimes`) | Reference file (`compareScan`) |
|---|---|---|
| Comes from | csTimer's CSV export, or a text file with one time per line | The editor's list, every solve checked by hand |
| Work for you | None | Minutes to an hour |
| Grades | Found, missed, false, wrong time | The same, plus reading and the exact cut frame |
| Use it for | Timers that can't be set up (csTimer) | Timers that can (QiYi, Stackmat); csTimer after 3.3-C |

A list of times has no places, so it is lined up in order. A missed solve right next to a false one counts as one wrong time. Nothing CubeCut found goes into it, so it also catches solves the scan always misses.

**Recording reference videos.** Film the timers **as they are really used**. Don't change a font or a display so that CubeCut can read it: what fails is what we need to know.
- 1080p at 60 fps, with the phone on a stand for most videos;
- raw and unedited, 10–15 solves (5–10 min), with normal pauses, scrambles and fumbles;
- for csTimer: start a new session first, so its list holds only the filmed solves;
- **old footage is fine too**, as long as there is a list of times (from csTimer, or read from the video and checked by a second reading);
- what's special about it in the file name, e.g. `ref_cstimer_default_dim_01.mp4`.

**Where the videos live.** On the phone (don't delete them: every change is re-measured there), plus a copy in a PC folder outside the repo, `D:\CubeCutRef`, with the lists of times. **Never committed.**

| Set | Videos | For |
|---|---|---|
| **1 (first)** | csTimer as you use it: **easy** (good light, straight on) and **hard** (dim, steep angle, hands over some stops) | 3.3-C |
| **2** | QiYi as you use it: easy and hard, with the manual setup | The result 3.3-C must not fall below; seven-segment digits |
| 3 | A phone timer app (note its name), Stackmat, GAN; one video with the camera moved mid-session | 3.3-C |
| 4 | **Edge cases** on any timer: a time already showing at the start, holds over 10 s, many 4s and 7s, a quick reset; glare; moiré / flicker | 3.3-B |
| 5 | The display in different places and sizes in the frame; +2 / DNF marks on the display; a solve over a minute; a mixed-puzzle session | 3.3-C, 3.3-E |

**Timer that can't be set up by hand (csTimer):** nothing to do in the app.
1. Export the list: csTimer's session manager → `...` on the session → *Export CSV*. The whole session is exported.
2. Copy the video and the CSV to `D:\CubeCutRef`, with the same name. Its "before" row is *not found*, 0 found.

**Timer that can be set up by hand (QiYi, Stackmat):** debug build (run from Android Studio, or `./gradlew installDebug`).
1. Import the video as *A session*, set up the timer with the four dots as usual, and run *Find solves*.
2. Make the list exactly right. Check **every** solve, not only the flagged ones, and the gaps between them:
   - delete false solves; add missed ones (*Add a missed solve* at the first frame of the final time);
   - correct times in *Edit*;
   - put each cut on the **first frame that shows the final time**: in *Edit*, use the 0.5 s / 0.1 s buttons, then finish with **◂ 1 frame / 1 frame ▸**. Leave cuts that are already on the right frame alone.
3. *Scan report (debug)* → pick the timer → *Save file…*. Copy the file to `app/src/test/resources/groundtruth/`; `groundTruthFiles_areValid` checks it on every test run.
4. *Scan report (debug)* → *Compare with the list* → *Copy*. This is the video's "before" row in the table above. Don't change the video's list afterwards.

The answer key stays valid however the timer is found. The dots become a fallback after 3.3-C, and show whether the app found the right place by itself.

**After every scan or reader change:** install the new debug build, open each reference video, run *Scan report (debug)* → *Compare with the list* (or *Compare with a file…* for a list of times, after a reinstall, or on another phone) → *Copy*. A change ships only if no video gets worse. 3.3-Z writes the before and after numbers here.

**File format** (`GroundTruth.kt`; `format` 1). `stopSec` is the cut to the millisecond and `time` is what the timer showed (`""` = not checked). Left-overs are never listed.
```json
{ "format": 1, "video": "My_movie_7.mp4", "timer": "csTimer (PC)", "durationSec": 212.3, "note": "",
  "solves": [ { "stopSec": 41.233, "time": "12.412" } ],
  "setup": { "frameMs": 35000, "typed": "12.412", "corners": [ 25.0, 444.0, 810.0, 388.0, 796.0, 600.0, 70.0, 704.0 ] } }
```
`setup` is the timer setup as it was tried, also when it was refused (left out if none was): the picture's time, the typed number, and the corners in frame pixels (top-left, top-right, bottom-right, bottom-left). A refused one is kept per video in the debug build's `scan_report_setups` preferences, not in the database.
`timer` is one of `TIMER_KINDS`. A found solve within 2 s of an expected one is matched to it (closest pairs first, each used once): further than that, it's a miss plus a false solve. The report lists every miss, false solve, wrong time and cut more than one frame off, with its place in the video.
