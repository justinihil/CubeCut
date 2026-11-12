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

Entry point: `SolveAnalyzer.analyze`, which calls `scan`. The scan only needs a function `read(t)` that returns the digits shown at second `t` (for example `"12412"`), an all-zero string for 0.000, or `null` when unreadable. In the app, `read` is `memoReader` (memoised per millisecond) over a `GraySource` plus `TimerEngine.readDigitsGray`. That separation keeps the decision logic testable without a phone, though no such test exists in the repo today.

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

**Known weakness:** a held time read without its last digit (`1151` while `11517` is held) can become a separate solve. It is caught afterwards by the too-fast check (see [§ Checks](#checks-after-a-scan)), not prevented. See [roadmap](roadmap.md).

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

**Simulation (historical):** a Python prototype (`pc_prototype/first_frame_scan_prototype.py`) once validated the scan design on 40 simulated sessions × 22 solves with 12 % unreadable reads and 20 % 4/7→1 misreads. It found 876 of 880 solves with 0 false ones, every cut within 0.25 s. The prototype is **not in this repo**, so these results cannot be reproduced. Validate scan changes on real footage, or add Kotlin unit tests (`scan` only needs a `read(t)` function).
