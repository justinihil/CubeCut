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

**Known weakness:** a held time read without its last digit (`1151` while `11517` is held) can become a separate solve. With a quarter of the held frames short, the simulator splits the hold in 132 of 200 runs. In 1 of them the short read also won the vote (a tie goes to the read that triggered the stop), so the real time was never reported. The too-fast check (see [§ Checks](#checks-after-a-scan)) flags it afterwards but doesn't prevent it. The ignored test `heldTimeMissingLastDigit_isTheSameHold` reproduces it. Not fixed: this scan is only the backup since 3.3-C and goes in 4.0-0 (3.3-B, which would have fixed it, was dropped).

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

The reader-specific part (steps 4–6) sits behind `DisplayReader` ([ADR-0014](adr/0014-display-reader-seam.md)). `SegmentReader` (id `"segment"`) is the only reader behind it.

**Solid digits** (csTimer, phone apps; `SolidDigits.kt`, 3.3-C). A separate reader that needs no display position. *Find solves* uses it first on every video (`analyzeByItself` in `AutoScan.kt`).
1. Black-and-white by comparing each pixel with the average around it (`SEARCH_BLOCKS`, three sizes), for light and dark digits.
2. Joined blobs of digit size are chained into rows: neighbours at most 0.75 digit heights apart, or 1.2 when a point or colon stands between them (fixed-width fonts); a row needs exactly one decimal point, and may have one colon before it (`1:04.42`).
   A **colon** is two dots of about the same size above each other, between 0.25 and 0.8 digit heights apart (`markBetween`). It may stand on the line or halfway up. Minutes have one or two digits, seconds after a colon two, below 60. A time with a colon but no decimals (a clock) isn't read. Hours aren't read.
3. The row's slope is undone, and each blob is scaled to 24 × 28 and compared with example digits. **All digits of a row lean the same way**, so the lean that suits the whole row counts.
4. `pickRow`: the reading most block sizes agree on.

The examples come from 12 open fonts, or only from the video itself once all ten digits were learned ([ADR-0016](adr/0016-find-the-timer-without-ml.md)). Measured on 150 whole frames of held times from the three csTimer videos, without csTimer's look-alike font and without a position: 150 right, about 80 ms per frame on a PC (`ReferenceFramesTest`).

**Screen fonts in seven segments** (2026-11-26). csTimer offers five digit fonts besides its default ("digital 1" to "digital 5"); none of them may ship with the app, so they were only drawn on the PC (`D:\CubeCutRef\work\fonts`, 20 times each). *Digital Numbers* (OFL), added for this, reads "digital 1": 18 of 20 drawn times (8 without it; the miss is `10.48` read as `0.48`), and its video, whose running time is hidden (in the table of timers found by their final times, below). "Digital 2" to "digital 5" read 0 of 20 with any font: their digits are separate strokes, and this reader compares joined blobs. **A digit in pieces** (two bars above each other, each 0.3 to 0.62 digit heights, together at least 0.8: `PIECE_MIN`) in a row, or within one digit height of it, makes that row unreadable. Without that, "digital 4" (a 1 and a 7 in two bars) was found by its final times with every 1 left out (`13.153` as `3.53`), five wrong times and nothing flagged; with it, the video gives *Couldn't find the timer*. Normal fonts don't have such pieces; all other reference videos give the same result as before. "Digital 4" and "digital 5" are read by the **seven-segment reader** instead, by their final times (below); "digital 2" and "digital 3" mostly not.

**Finding the timer** (`TimerFinder.kt`, 3.3-C). Pure logic over "the picture at second t":
1. **Search** places spread over the video, halving the gaps. A run is 4 pictures 0.5 s apart in which a number rises by 0.5 each time. After 3 runs of the same number it stops; the **biggest** running number is the timer. If a second number at least 0.6 times as tall ran next to it at two places, CubeCut asks (`FinderResult.choices`); the answer comes back as `near`. The whole picture is searched at half size first, then, if nothing runs, at full size at fewer places.
2. **Watch** those runs to their end: where the number freezes, and with how many decimals. This looks in a wider area (`WATCH_ACROSS`), because some timers show the stopped time elsewhere for a moment; the area then covers both places. A number near the area's edge (`EDGE`) isn't read at all, so `16.259` is never half-read as `6.259`.
3. **Learn:** the same runs again at 12 pictures a second. The exact start is the phase that makes the tenths' shapes most alike within each label, then the whole tenth that agrees best with the readings. The clock then names every digit. Shapes that look more like another digit are left out. Used only when all ten digits were seen at least 4 times.

On the PC, the app's own code found and learned csTimer in all three videos (15 to 19 places, 47 to 66 whole pictures, 273 to 344 area reads, 2.2 s without getting pictures), and the learned digits then read 150 of 150 held times (`ReferenceVideoTest`, without csTimer's look-alike font). On the owner's phone (debug build, before the speed-up below) video 01 gave the same result in 48 s: 6 s for pictures, 42 s for searching and reading, 36 ms per area read. Since then pictures are looked at at half size and fonts are compared roughly first, which makes it 3.3 times faster on the PC; the phone isn't measured again yet.

**Timers that show no running time with decimals** (`HeldFinder.kt`, `HeldScan.kt`, 3.3-C): hidden ("solve", "...") or whole seconds. Only when no number was seen running, before seven-segment timers are tried, and again at the end of the search.
1. **Finding by final times.** The numbers the search saw on its way (at least 3 digits, likeness 0.75) are grouped by place and size. The tallest group with 3 or more different times is the timer, if a time that went away never comes back, no time comes sooner after the one before it than it says, and 3 of them stand still (the same 0.15 s before or after). Averages and lists change like that too, but are smaller. Nothing is learned: it reads with the fonts.
2. **Scanning** every 0.5 s. A time with the stopped decimals must read the same in 3 of 6 pictures within 0.3 s, then 3 s are voted on; so 0.75 s on the screen can be enough. Readings one digit off, or without the first digits (a hand), are the same time; `8.000` next to `0.000` is the zero.
3. **Confirming:** the time must have been away for as long as it says (`SOON_NOTE`), or whole seconds between start and stop must agree (60 % of at least 3; else `CLOCK_NOTE`). An inspection that counts down never agrees. This is weaker than a running time with decimals: a misread hundredth, or any too small time of a hidden timer, isn't noticed. A time that neither confirms is kept, to be checked (owner, 2026-11-25).

| PC, the app's own code (2026-11-25) | Found | False | Wrong time | On the frame | Worst cut |
|---|---|---|---|---|---|
| `ref_flowtimer_hidden_01` ("...", inspection; 3:21) | 6 / 6 | 0 | 0 | 6 | 0.000 s |
| `ref_cstimer_seconds_01` (whole seconds; screen recording of a TikTok clip, times on screen for 0.75 to 1.5 s; 1:10) | 5 / 5 | 0 | 0 | 4 | 0.035 s (one frame) |
| `ref_cstimer_old_01` to `03`, running time thrown away (`findsTimersByTheirFinalTimes`) | 75 / 75 | 0 | 0 | 75 | 0.017 s |
| the same, running time cut to whole seconds | 75 / 75 | 0 | 0 | 75 | 0.017 s |
| `ref_cstimer_digital1_01` (csTimer "digital 1", running time hidden, light theme, 2:46; 2026-11-26) | 5 / 5 | 0 | 0 | 5 | 0.017 s |
| `ref_cstimer_digital4_01` (csTimer "digital 4", running time hidden, dark theme, 2:07; seven-segment, see below) | 4 / 4 | 0 | 0 | 3 | 0.033 s |

Nothing is flagged and there are no hints. The first two videos were used to develop it; the csTimer videos weren't (nothing was tuned after they were run), but "solve" isn't in their picture. No such timer is found in the three QiYi videos (192 places each). `ref_cstimer_digital1_01` isn't untouched: Digital Numbers was chosen on its stills (above). Its key was read from the video frame by frame and checked by the owner; the time already on screen at 0:00 is left out. `MissedSolvesTest` leaves it out, like the other hidden ones (nothing runs, so a missing solve mostly gets no hint). `ref_cstimer_digital4_01` ("digital 4") is the same: its key was read frame by frame and checked by the owner (2026-11-26), and it was used to develop the seven-segment final times. The time on screen at 0:00 (`13.262`) is listed, flagged, and left out of the key; the late cut (`13.881`) is 2 frames early, where the new time blends with the old.

**Seven-segment timers** (QiYi; `SegmentRows.kt`, `SegmentGrid.kt`, `SegmentFinder.kt`, 3.3-C). Pure logic, nothing is learned.
1. **Rows.** Black-and-white at four sizes (`BLOCKS`) against a limit that goes with the brightness; gaps between strokes are closed with circles of four sizes (`CLOSINGS`; the smallest, 0.1 of the block, for screen fonts whose thick strokes are close together, such as "digital 4", whose digits join at 0.2); digit-sized blobs are chained and compared with drawn digits for 23 leans. All of it along three directions, 0° and ±30° (`ROW_TURNS`): the blobs are turned, not the picture, which costs almost nothing. **Two blobs above each other** are also tried as one digit (`stacked`): two bars (a 1 whose strokes don't touch) or two halves of the same width (an 8), each 0.25 to 0.7 of their height together. Pairing any two blobs made the rows 7 to 14 times slower, nearly all of it noise; with these rules they take 15 to 25 % longer than with single blobs only. Places at either end of a row that aren't a digit are dropped from it (the last digit grown into the text beside it).
2. **Grid.** The right edges of 4 or more digits must be evenly spaced, with one wider gap at the decimal point. From then on every place is compared with the ten digits (13 shapes), so a missing stroke no longer breaks a digit. A place to the right that holds a sure digit, at least 0.6 times as strong as the others, is added as a decimal, up to three (`widenedIn`): a row's last digit can be lost in the first stage.
3. **Clock.** Seconds and tenths must rise with the clock in 6 pictures 0.5 s apart; the last two decimals blur while it runs and aren't used. The first grid that does is the timer, and is made to fit those pictures better.
4. **Final times** (csTimer's "digital" fonts with the running time hidden; 2026-11-26). Every grid the search checks also reads what it shows; a reading of 3 or more digits that fits well is a final time. They are grouped as for solid digits (`heldPlace`, above): 3 or more different times at one place, behaving like a timer, 3 of them standing still 0.15 s before and after. That grid is read every 0.5 s by the scan for timers found by their final times (`scanHeldTimer`).

**Order** (`otherKind` of `findTimer`): solid digits first. If nothing runs after 40 places at half size and 16 at full size, a timer is looked for by its final times (above), then seven-segment is tried; if that finds nothing either, the solid search goes on with the rest of its places. So a csTimer video with a QiYi in view stays csTimer. **Last**, when nothing was found and nothing is to be asked, the final times that seven-segment search read are looked at (step 4); so they cost no extra pictures. A QiYi lying still on a desk in a csTimer video shows final times too (csTimer 01 and 03), so this must stay last.

Measured on the PC with the app's own code (`ReferenceVideoTest.findsOnlyTheSevenSegmentTimers`, 2026-11-25): `ref_qiyi_old_01` found after 21 places, `ref_qiyi_old_02` after 34. In the other eight reference videos (csTimer, 4x4, flowtimer), 160 places each, only one thing was ever found: the real QiYi in view of `ref_4x4_02` (22 px digits, since close calls are looked at twice). csTimer is found first in that video, so it isn't used. 0.1 to 0.25 s per place on 12 threads, plus checking. Both QiYi videos were used while developing the method. On the owner's phone (2026-11-25): found after 7 places in 8.6 s and after 11 places in 5.3 s, and the numbers it saw agree with the answer keys within 0.02 s; with the solid search before it 46.9 s and 26.9 s, where giving up took 93.6 s and 116.8 s before. No false find in csTimer 01. The solid finder and scan give the results below, as before. **With two blobs as one digit and the extra closing** (2026-11-26): `ref_qiyi_old_01` found after 3 places, 02 after 34, 03 after 19 (25 before); no false find in the other twelve videos; rows took about 20 % longer, measured against the time for getting pictures in the same run. By final times (step 4) nothing is found in the twelve, except the QiYi lying still on the desk in csTimer 01 and 03 (21 px), where csTimer is found first.

**Following and reading it** (`SegmentTracker.kt`). A timer on a mat slides and jumps when it is hit (75 × 96 px in 01).
- **Rough track:** a picture every 10 s, outwards from where it was found. A place counts only where the digits fit at least 0.7, or a grid half a digit too low reads `0.000` as `4.444`. When nothing fits there, it is looked for 6 digit heights up and down and 10 digit widths sideways (`foundFarAway`), and believed from 0.8 on: in `ref_qiyi_old_03` a thumb on the reset button moved the timer by 1.7 heights, out of reach of `foundAgain`, and everything before that (a whole solve) was lost. There the right place fits 0.91, the best wrong one 0.66.
- **One grid for the whole video** is fitted to up to 24 pictures of that track. Where the tens show a sure digit, they count too: a grid fitted to the last four digits only is least exact at the first one, and dropped its `1`.
- **Every read** looks around the nearest known place, then further (10 px of the level picture), then, not too often, 1.5 digit heights around at half size (`foundAgain`). What fits under 0.66, or is cut off by the edge of the picture, isn't read.
- **Close calls** (7 and 1, 6 and 5 fit nearly alike): the place is also tried 2 px to all sides, and the digit that fits best anywhere is it (`CLOSE_CALL`, `NUDGE`).
- **The scan is the one below**, with three changes for displays whose last digits blur while running: running times come from the tenths (`tenths`); a time that seems to stay but goes on rising within 0.3 s of the clock was running; and a reading one digit off doesn't end the search for the first frame (a single `11.123` made the cut of `17.123` 0.27 s late).

| Video | Found | False | Wrong time | On the frame | Within 0.1 s | Worst cut | Reads |
|---|---|---|---|---|---|---|---|
| `ref_qiyi_old_01` | 55 / 55 | 0 | 0 | 53 | 55 | 0.067 s | 4161 |
| `ref_qiyi_old_02` (30 fps; the display turned 33°) | 30 / 30 | 0 | 0 | 21 | 30 | 0.033 s | 1932 |
| `ref_qiyi_old_03` (hidden and hurried stops) | 6 / 9; hints for the other 3 | 0 | 0 | 5 (4 when found at 123.5 s, 2026-11-26) | 6 | 0.067 s (0.084 s) | 721 |
| Phone, 01 (before close calls) | 55 / 55 | 0 | 0 | 52 | 53 | 0.266 s | 4252 |
| Phone, 02 (before close calls) | 30 / 30 | 0 | 0 | 20 | 30 | 0.034 s | 1876 |

On the owner's phone (2026-11-25, debug build 9972642): held frames 320 right, 3 wrong, 7 unreadable of 330, and 179 / 0 / 1 of 180 (by hand 279 / 36 / 15 and 110 / 32 / 38). Finding took 27.1 s and 30.1 s, following 17.5 s and 9.8 s, the scan 151.4 s and 71.7 s (13.5 ms per read). The late cut (`17.123`, flagged by the clock check) and the misreads were reproduced on the PC from the phone's starting place, which gave the rule for close calls; that cut is right on the PC now.

PC, the app's own code (`scansTheSevenSegmentTimers`, 2026-11-25); keys ±1 frame (slow LCD). 03 (2026-11-25, 7:20) was filmed by the owner to test the hints: three times were reset at once (0.2 to 1 s on show), two more solves were covered and have no time in the key (the key's nine times were checked by the owner, 2026-11-25); its worst cut (`14.546`) is 4 frames *before* the key, where a thumb still covers the last digit. Nothing is flagged but the time already on the timer when 02 began. Before the close calls: the same solves from three other starting places each (worst cut 0.067 s), and for 01 at 25 % brightness, turned ±20°, at 0.7 and 1.2 size (worst 0.1 s). At 1.5 size the timer left the picture, so that says nothing. Set up by hand on the phone: 55 and 28 found, 13 and 6 false, 3 and 2 wrong times, 45 and 23 within 0.1 s ([§ 8](#8-reference-measurements)). Compare with the **file**: the list's cuts were made by the old scan, so *Compare with the list* grades that scan against itself.

**Scanning a found timer** (`FinderScan.kt`, 3.3-C). The idea and the constants of [§ 1](#1-finding-solves), with these differences:
- A text with fewer decimals than a stopped time is a *running* time, and is kept.
- **Start to stop:** the running readings give the start, and stop minus start must be the time within 0.2 s. Not confirmed, or never seen running: the solve is kept with `CLOCK_NOTE` and needs a check. It exposes wrong whole numbers and tenths, not hundredths. The clock also chooses between competing readings in the vote.
- **The cut is searched frame by frame** in the last half second, with each frame's own time (`GraySource.lastFrameSec`). Halving the gap is misled by frames in which the phone blended two numbers.
- **A time that shows again without having run** is the same hold (the timer was covered).
- **A hidden stop** (first seen too late for the clock) is worked out from the start, with `HIDDEN_STOP_NOTE`. Not when the timer was seen running up to the first frame: then the timer itself changed the time (flowtimer with a smart cube), the cut stays, and the solve gets `CLOCK_NOTE`.
- **As many decimals running as stopped:** two blended running frames can read alike. A time that goes on rising with the clock in 3 readings (`STILL_RUNNING_READS`) was running.

**Hints where a solve may be missing** (`MissedSolves.kt`, 3.3-C). The scan keeps what it saw (`ScanTrace`); after the solves are known, three things give a hint:
1. **The timer ran, no final time:** at least 4 running readings over 2 s agree on a start, and no found solve lies there. *Go there* is the last running reading. Not when the video ends while the timer runs.
2. **It ran and was reset:** the same, and it shows 0.000 within 1.5 s of the last running reading.
3. **It couldn't be read:** for 15 s or more (up to the start of the next found solve), and that is 1.3 usual solves (stop to stop), or 0.5 when the gap between the solves around it is 1.6 times the usual one; 2 usual solves at the start or end of the video; 60 s when there are fewer than three solves to tell. Not when the same time shows before and after.

A long gap alone gives no hint (owner): it could be a break. On `ref_qiyi_old_03` (filmed afterwards; not used for the limits): the covered stop and the three quick resets are told, no false hint; the solve that was covered from start to reset isn't (21 s unreadable, too short; the owner is fine with that). The limits were chosen on the ten other reference videos (193 solves): no hint in any of them, where nothing is missing. With one final time after the other made unreadable, 191 of 193 were told; with a whole solve made unreadable, 177 of 193 (the first and last solves of a video mostly aren't). 10 s or 1.1 solves gave false hints (`MissedSolvesTest`).

Measured on the PC with the app's own code (`ReferenceVideoTest`, 2026-11-24, without csTimer's look-alike font):

| Video | Found | False | Wrong time | On the frame | Worst cut | Reads |
|---|---|---|---|---|---|---|
| `ref_cstimer_old_01` | 25 / 25 | 0 | 0 | 25 | 0.017 s | 3017 |
| `ref_cstimer_old_02` | 25 / 25 | 0 | 0 | 24 | 0.084 s | 2424 |
| `ref_cstimer_old_03` | 25 / 25 | 0 | 0 | 25 | 0.017 s | 2427 |
| `ref_4x4_01` (1:04.42, 30 fps) | 1 / 1 | 0 | 0 | 1 | 0.000 s | 170 |
| `ref_4x4_02` (1:01.39, 30 fps) | 1 / 1 | 0 | 0 | 1 | 0.000 s | 168 |
| `ref_4x4_03` (1:01.31) | 1 / 1 | 0 | 0 | 1 | 0.017 s | 178 |
| `ref_othertimer_01` (flowtimer.io, 7:56) | 16 / 16 | 0 | 0 | 16 | 0.017 s | 1365 |
| `ref_othertimer_02` (flowtimer.io, 6:02) | 12 / 12 | 0 | 0 | 12 | 0.017 s | 1021 |

The flowtimer videos (2026-11-25) have a fixed-width font with a marked zero, blue on dark blue, three decimals running and stopped, 51 px digits. Measured without Roboto, Roboto Mono and JetBrains Mono, the bundled fonts it resembles; the result with them is the same. **Before** the four rules for it above, they gave 3 / 16 and 2 / 12 (no false solves or wrong times). Those rules were made on these two videos, so there is no untouched flowtimer video. 7 of the 28 solves are flagged: the stopped time differs from the last running one, by 0.62 s in the one case looked at closely. The `7.480` the owner deleted on the screen is found and left out of the key.

The 4x4 videos (2026-11-25) are 75 to 79 s long with **one solve over a minute** each. Before the colon was read, none was found ("never seen to stop"); finding and learning already worked from the one solve. Real footage covers minute 1 only, csTimer's font only; other fonts, a colon halfway up and times to 10 minutes are covered by drawn pictures (`SolidDigitsTest`).

On 03 it also lists the `0.34` test tap, flagged, which the owner left out of the key. Scanning took 4 to 5 s per video without getting pictures.

On the owner's phone (2026-11-24, debug build 3ffa64e), video 01: found by itself, 25 / 25, 0 missed, 0 false, 0 wrong time; held frames 141 right, 0 wrong, 9 unreadable of 150. Finding took 10.4 s, scanning 106.8 s (86.7 s of it getting 3036 pictures, 6.5 ms per read) for 21 minutes of video. The report showed every cut 0.071 to 0.105 s late, because its key was on another clock (next paragraph). Corrected by calculation, not by a second run: 24 of 25 on the frame, worst 0.033 s.

**Camera moves** (`ReferenceVideoTest.smallCameraMoves`, video 01, the picture moved after a third and after two thirds): moves of up to 60 px with up to 3 % zoom and 2° of turning gave 25 / 25, no false solves or wrong times, every cut on the frame. A move of 150 to 460 px also did, but only because the search happened to see both places; nothing follows a camera.

**Two clocks.** Samsung videos start with an edit list: their first frame is shown at 0.05 to 0.07 s of the file's own time. Android (and so CubeCut's cuts and exports) uses that time; `ffmpeg` and `ffprobe` start the same frame at 0. A place measured with ffmpeg needs that offset (the first frame's composition offset minus the edit list's `media_time`) before it goes into a reference file. The `.places.txt` files in `D:\CubeCutRef` are in ffmpeg's time, because `ReferenceVideoTest` uses ffmpeg; the `.groundtruth.json` files are in Android's.

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

**Real-footage baseline.** Owner priority: csTimer on a PC, then phone timer apps, then Stackmat, then GAN / QiYi. One row per video, from its report; a timer that isn't found counts as 0 found. The csTimer rows are **not phone runs**: today's app can't search for a timer, and csTimer's font can't be set up by hand, so the result is known without running it. The QiYi rows are phone runs (2026-11-25).

| Video (timer) | Timer found | Reading: right / wrong / unreadable | Expected | Found | Missed | False | Wrong time | On the frame | Readable | Scan time | Build |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `ref_cstimer_old_01.mp4` (csTimer; 21:10, dim room, scramble PDF shown over it) | no | – | 25 | 0 | 25 | 0 | 0 | – | – | – | 89c9a3d, not run |
| `ref_cstimer_old_02.mp4` (csTimer; 19:12, brighter) | no | – | 25 | 0 | 25 | 0 | 0 | – | – | – | 89c9a3d, not run |
| `ref_cstimer_old_03.mp4` (csTimer; 18:45, brighter) | no | – | 25 | 0 | 25 | 0 | 0 | – | – | – | 89c9a3d, not run |
| `ref_qiyi_old_01.mp4` (QiYi; 28:35) | by hand | 279 / 36 / 15 | 55 | 55 | 0 | 13 | 3 | 26 (45 within 0.1 s, worst 1.348 s) | 76 % | 169.2 s | 6bd0cfc |
| `ref_qiyi_old_02.mp4` (QiYi; 17:03, steeper) | by hand | 110 / 32 / 38 | 30 | 28 | 2 | 6 | 2 | 7 (23 within 0.1 s, worst 0.755 s) | 66 % | 108.1 s | 6bd0cfc |
| Both QiYi videos | by itself: no | – | 55 + 30 | 0 | 85 | – | – | – | – | 93.6 s and 116.8 s to say so | 6bd0cfc |

What the QiYi rows show (checked against the keys, 2026-11-25):
- **All 19 false solves come after a real one, 1 to 20 s into its display:** 13 show exactly its time, 6 look like a misread of it. On 02, the two *missed* solves are two of these false ones: found, but 2.4 and 3.4 s late, more than the report's 2 s limit for the same solve. So no solve is lost completely, and 17 of the 19 are truly extra.
- **Wrong times are dropped or confused strokes:** a leading 1 lost (14.025 as 4.025, 12.393 as 2.393), 3 as 8 (13.704 as 8.704) and 7 as 1 (11.517 as 11.511, 15.837 as 15.831). 7 as 1 is the most common misread on 02.
- **Cuts are mostly late** (44 of the 50 off by more than 0.02 s; 6 early, all on 02). The key itself is ±1 frame, because the LCD is slow. Video 02 is 30 fps, so one frame (0.033 s) is already more than the report's 0.02 s: its 7 *on the frame* is too strict, and 22 of 28 are within 0.05 s. *Within 0.1 s* is the fairer number for QiYi.

**After 3.3** (PC, the app's own code, no setup; numbers from [§ 3](#3-reading-the-timer), 2026-11-24 to 2026-11-26). Before 3.3 none of the csTimer videos could be set up, and the QiYi was only read by hand (rows above).

| Video | Found | False | Wrong time | On the frame | Worst cut |
|---|---|---|---|---|---|
| `ref_cstimer_old_01` / `02` / `03` | 75 / 75 | 0 | 0 | 74 | 0.084 s |
| `ref_4x4_01` to `03` (over a minute) | 3 / 3 | 0 | 0 | 3 | 0.017 s |
| `ref_othertimer_01` / `02` (flowtimer.io) | 28 / 28 | 0 | 0 | 28 | 0.017 s |
| `ref_flowtimer_hidden_01` (running time hidden) | 6 / 6 | 0 | 0 | 6 | 0.000 s |
| `ref_cstimer_seconds_01` (whole seconds) | 5 / 5 | 0 | 0 | 4 | 0.035 s |
| `ref_cstimer_digital1_01` / `digital4_01` | 9 / 9 | 0 | 0 | 8 | 0.033 s |
| `ref_qiyi_old_01` / `02` | 85 / 85 | 0 | 0 | 74 | 0.033 s |
| `ref_qiyi_old_03` (hidden and hurried stops) | 6 / 9, hints for the other 3 | 0 | 0 | 4 | 0.084 s |
| **All** | **217 / 220** | **0** | **0** | **201** | 0.084 s |

Phone runs: csTimer 01 25 / 25 (2026-11-24); QiYi 01 and 02 55 / 55 and 30 / 30 (2026-11-25, before close calls); owner's checks of csTimer "solve", "digital 1", "digital 4" and a new flowtimer video (2026-11-26). Most of these videos were used to develop the readers, so these numbers are better than on footage never seen.

**How it works.** An answer key says what a video should give, and the debug *Scan report* (under *Find solves* in the editor) grades the app against it **without changing the list**. Each stage depends on the one before, so the report shows where a video fails:
1. **Timer:** was it found? CubeCut looks by itself (3.3-C), with no setup and nothing from the answer key. If the video also has a setup by hand, that is tried from scratch and graded first, the old way. *NOT FOUND* is a result, and the report still runs.
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
| 4 | **Edge cases** on any timer: a time already showing at the start, holds over 10 s, many 4s and 7s, a quick reset; glare; moiré / flicker | 3.3-B (dropped) |
| 5 | The display in different places and sizes in the frame; +2 / DNF marks on the display; a solve over a minute; a mixed-puzzle session | 3.3-C, 3.3-E (dropped) |

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

The report ends with *How it was found*: what the finder did and learned, how long each part took on this phone, and which solves were flagged. A list of times can't grade the cuts; for csTimer videos with known places, a reference file (JSON, in `D:\CubeCutRef`) can.

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
