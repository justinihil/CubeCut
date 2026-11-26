# Roadmap

This page holds the planned work. The owner listed the items on 2026-11-12. On 2026-11-23 they were ordered into versions and phases, and the decisions marked **Decided** below were made then. Anything else is still open: it gets asked at the start of its phase.

Each phase lists:
- its items, numbered as in the owner's list (`#n`);
- what has been decided;
- the open questions to ask first;
- where to start in the code;
- the rules that limit it;
- its status: ⬜ to do, 🔶 in progress, ✅ done.

When a phase ships, its changes go into `CHANGELOG.md` and the phase is marked ✅ here. When a whole version is released, its section is deleted.

## How to run a phase

**One phase per thread.** Every phase starts in a new Claude Code thread, so each thread reads only what it needs. Also start a new thread:
- after about two fix rounds that didn't work (begin it with a short "where we are");
- for any visual glitch.

Use `/effort medium` for docs-only, release-prep and small phases.

**To start a version** (the first phase of it):
```
Start v3.3: create dev-v3.3.0 from dev, then Phase 3.3-A.
```
**To start a phase** (change the phase id and the branch):
```
Phase 3.3-A from docs/roadmap.md, branch dev-v3.3.0.
Read CLAUDE.md, then that phase section, then only the files it names.
Ask me the phase's open questions first. Then build it, update the owning docs + CHANGELOG in the same commit,
run assembleDebug + testDebugUnitTest, commit, and give me numbered phone test steps.
```
**During the phase:**
- `Phone test: step 4 failed — <what I saw>` (attach a screen recording for anything visual).
- `All steps pass — close the phase`: Claude marks the phase ✅ here and commits.

**To end a version** (its `-Z` phase):
```
Release prep for v3.3 (phase 3.3-Z).
```
This phase:
- bumps `versionCode` / `versionName`;
- turns `[Unreleased]` in the CHANGELOG into the version entry;
- tidies [known-issues](known-issues.md) and this page;
- lists a full phone test pass;
- opens the PR `dev-vX.Y.0 → dev`.

The owner then merges `dev → main` and tags the release by hand ([release steps](android.md#release-process)).

## Overview

| Version | Branch | Theme | DB |
|---|---|---|---|
| **3.3** | `dev-v3.3.0` | Better Find solves: no timer setup, more fonts, more accurate | v8 (missed-solve hints) |
| **4.0** | `dev-v4.0.0` | Find solves runs in the background after import | v9 |

The owner's list had three versions (v3.1, v3.2, v4.0). The first was split in two so that the quick wins, which need no database change, ship first. The owner's "v3.2" (Find solves) became 3.3.

---

## v3.3: better Find solves

**The owner's request:**
- more accurate;
- no timer setup: the app finds the timer on the frame by itself;
- more fonts, from popular speedcubing timers on PC, phone and physical timers.

The owner rule "change the reader only when asked" is lifted for this version. Every change is still measured against the harness from 3.3-A.

**Order (decided 2026-11-24): A → C → B → E → Z.** Finding the timer and reading any font are one phase (C). Splitting them (the old 3.3-D) made no sense: a timer is recognised by its digits, and digits are only worth reading once the timer is known.

### 3.3-A Ground-truth harness 🔶
- The owner records reference videos for each timer:
  - csTimer on a PC;
  - phone timer apps;
  - Stackmat, GAN and QiYi physical timers.
- The videos stay on the phone, with a copy in `D:\CubeCutRef` on the PC (outside the repo, decided 2026-11-24; moved from drive C the same day). They are **never committed**.
- The repo gets:
  - expected-solve files (JSON);
  - unit tests for `SolveAnalyzer.scan` with a fake `read(t)`;
  - a debug-only scan report that shows found, missed and false solves.
- Record the baseline in [algorithms §8](algorithms.md#8-reference-measurements).

**Decided** (2026-11-23):
- Timer priority: csTimer on a PC, then phone timer apps, then Stackmat, then GAN / QiYi.
- No reference videos are recorded yet. The harness was built first, and the real-footage baseline rows are filled in as the owner records them ([algorithms § 8](algorithms.md#ground-truth-harness-33-a)).
- The other phases wait until reference videos exist, so each change can be measured.
- (2026-11-24) Reference videos are filmed raw, as the timers are really used. A refused setup is a result, not a reason to change the font.
- (2026-11-24) A timer that can't be set up by hand (csTimer) needs **no work in the app**: the raw video and csTimer's own list of times are enough. A timer that can (QiYi, Stackmat) is also measured with the manual setup now, as the result 3.3-C must not fall below.
- The ML question is asked only if 3.3-C fails on the reference videos without it.

**Built:** `FakeDisplay` + `SolveAnalyzerScanTest` (the simulated baseline), `GroundTruth.kt` + `GroundTruthTest`, the debug *Scan report* in the editor (`ScanReport.kt`), and 1-frame buttons in the *Edit* window, so a hand-made answer key is frame-exact.

**csTimer set (2026-11-24):** three old csTimer sessions (`ref_cstimer_old_01` to `03`, 25 solves each) with a list of times, read from the video in two passes and checked against csTimer's ao5 / ao12. Their baseline is in [algorithms § 8](algorithms.md#ground-truth-harness-33-a). The videos, lists and places are in `D:\CubeCutRef`.

**Still missing to close 3.3-A:**
- csTimer videos of another setup: other font or light theme, camera further away, hands over some stops. The three above share one desk, font and theme;
- phone timer apps, Stackmat and GAN (sets 3 to 5) can follow later; they don't hold up csTimer.

**QiYi baseline (2026-11-25):** both old sessions with the setup by hand, 85 solves: 83 found, 2 missed, 19 false, 5 wrong times ([numbers](algorithms.md#ground-truth-harness-33-a)). 3.3-C must not fall below it, and it may now change things for seven-segment timers.

**The scan report works on a phone** (owner, 2026-11-25: both QiYi videos, against a reference file).

**Reworked (2026-11-24):** the report first needed a working timer setup, so it couldn't test the videos that fail there. It now grades finding the timer, reading and scanning separately, always runs, and also takes a plain list of times.

### 3.3-B Scan accuracy ⬜
- *Earlier note:* a held time misread without its last digit (`1151` while `11517` is held) should count as the same hold in `scan`. 3.3-A reproduced it: the hold often splits, and rarely the real time is lost. Un-ignore `heldTimeMissingLastDigit_isTheSameHold`.
- *From the 3.3-A simulator:* 71 of 880 cuts land 2 or more frames off (worst 0.18 s). 65 of them are late and come from unreadable frames, so look at what `firstAppearance` does when a read in the middle is empty; 6 are early ([numbers](algorithms.md#ground-truth-harness-33-a)). Raise *on the frame* without lowering the other numbers.
- Check whether readings like `0.004` of 0.000 occur.
- The too-fast check stays as a safety net.
- Start: [algorithms §1–4](algorithms.md); `SolveAnalyzer.kt`.

### 3.3-C Find and read the timer by itself 🔶
**Goal (owner, 2026-11-24):** import raw footage, and CubeCut finds the timer, learns how it looks and reads it. Borders, font, angle and place must not matter. No corners, no typed number. `TimerSetupScreen` stays only as a backup, until 4.0-0.

**Planned method:**
1. **Search:** find every number-like row in frames spread over the video.
2. **Watch:** the timer is the number that **runs, then freezes**. In one second of video it rises by 1.00; averages, session lists and scrambles don't.
3. **Learn:** remember its place and digit shapes in this video, and use them on harder frames.
4. **Follow:** search again when it moves or is covered. (Not built: a fixed camera is required, see step 5 below.)
5. **Check:** start to stop must match the final time, which catches misreads.

A timer that is hidden or shows no decimals while running weakens steps 2 and 5. Then use the number that changes once per solve.

**What is shared and what isn't.** Steps 1, 2, 4 and 5, the scan and the answer keys are meant for every timer. Only the digit shapes differ: solid digits (csTimer, phone apps) and seven-segment digits (QiYi, Stackmat, GAN), whose strokes are separate pieces. Seven-segment digits should go through the existing `SegmentReader` once the place is known.

**First prototype (2026-11-24)**, on one screenshot of csTimer's default font at a steep angle (only the digits 1, 2 and 5). It thresholds the picture, groups digit-like blobs into rows, levels them, and compares them with digits from 17 fonts. No ML:

| Test | Result |
|---|---|
| The picture as it is | `12.25`, and no other time-like number |
| 20 altered copies | Right number among the results in 16; nothing with the last digit covered (correct); nothing when nearly black or with heavy noise; misread as `12.15` when turned 25° |
| Wrong extra numbers | 3 copies (such as `1.19`, `19.25`). The ao12, `13.01`, was also read in 3: reading alone can't tell which number is the timer |
| Single digits in a font left out of the 17 | 3966 / 4080 (97.2 %); worst Courier New, 180 / 240 |
| Drawn seven-segment digits | Without gaps, 4 of 6 right; **with gaps between the segments, 0 of 18** (nothing, or a wrong `8.98`) |

**Second prototype (2026-11-24): all five steps, on the three csTimer videos.** Still no ML, and nothing from the answer key, no corner and no typed number. Developed on video 01, checked on 02, then frozen; 03 was run once after that. The fonts that look like csTimer's (Arial, Microsoft Sans Serif) were **left out**.

| Video | Timer | Found | Missed | False | Wrong time | Cut on the frame | Confirmed by the clock |
|---|---|---|---|---|---|---|---|
| 01 (developed on) | csTimer | 25 / 25 | 0 | 0 | 0 | 25 | 25 |
| 02 (checked on; changed the method) | csTimer | 25 / 25 | 0 | 0 | 0 | 24 (worst 0.067 s) | 25 |
| **03 (untouched)** | csTimer | **25 / 25** | 0 | 0 | 0 | 25 | 25 |

On 03 it also found the `0.34` test tap the owner left out of the key. The clock didn't confirm it, so it's listed here and not counted. The QiYi that shows the same times in the corner was never picked: its gapped digits weren't read. With both readable, the bigger number would win, which is untested.

**Altered copies of 01 and 02** (25 solves each; the same for both unless noted):

| Copy | 01 | 02 |
|---|---|---|
| Darker (25 %), washed out, inverted (light theme), noise 15, blur 3 px, quarter size (24 px digits), turned ±15° on top of the real tilt (about 15° by eye), steeper angle; on 01 only also half and third size (an earlier version of the method) | 25 | 25 |
| Last digit covered 3 s in every 10 | 25 (24 cuts within 0.1 s) | 25 |
| Camera moved far during the session | 25 | 24: the solve running while it moved is lost |
| Nearly black (12 %) | 25 | 23, 4 wrong times, **none confirmed by the clock** |
| Blur 6 px | 24 | 21 |
| Horizontal motion blur 15 px | 19 | 16 |
| 18 px digits | 24 | 21 |
| Turned +25° on top of the real tilt | timer not found | 22 |
| csTimer painted over, or no timer at all | not found (correct) | not found (correct) |

What each step taught:
- **Search** reads 4 frames at places spread over the video: 15 to 19 of them on the real videos, up to 109 on the altered copies. Only a number that rises with the clock counts; no other running number was ever found.
- **Learning must be complete.** A first version learned from three runs that held no `3` and then read `3` as `8`: 5 wrong times on 02. Digits are now learned at 12 frames a second, where the tenths go through 0 to 9. **The clock names the digit**, not the font, and learning isn't used unless all ten digits were seen. With learning off, hard angles give 5 to 8 wrong times.
- **One lean per row**: all digits of a row lean the same way, which fixed the tilted copies.
- **Frame-by-frame cut:** a PC port of the app's 5-read halving put 14 of 25 cuts on the frame on 01, because phone video blends two numbers in one frame. Reading every frame of the last half second gave 25 of 25.
- **A time that reappears is the same hold** unless the timer ran in between (17 times on 01, after the scramble PDF).
- **Start to stop:** on the real videos every solve agreed with the clock within 0.12 s (0.2 s in the blurred copies; more when the stop itself was covered). It exposes wrong whole numbers and tenths, not a wrong hundredth, and gives the stop when the first frame was covered (worst 0.14 s off).

**Not tested:** other fonts and themes in real footage, phone timer apps, times over a minute (no colon; read since step 6), timers without decimals while running, +2 / DNF marks, seven-segment digits, two readable timers, a hand over the digits in real footage.

**Special to these videos:** none of the rules are, but the numbers were tuned on one desk, one font and one person. A stopped time with two decimals and a running one with one is learned, not assumed. Only `0.00` counts as a reset.

**Speed (PC, one thread, Python):** 112 ms to search a full frame, 10 ms to read the known area. For video 03 that was about 1500 area reads at 0.5 s and 950 frame by frame; search 6 s, learning 13 s, scan 52 s, mostly starting ffmpeg per read. **The phone is not measured.** Video length × 0.07 s ([algorithms § 8](algorithms.md#8-reference-measurements)) suggests about 80 s to decode this 19-minute video, a guess.

The scripts are in `D:\CubeCutRef\prototype\v2` (`frozen_v2c` is the version measured).

**Rules:**
- **Good enough (owner, 2026-11-24):** it doesn't have to work in every scenario. It has to be reliable in a reasonable setting, and the user is responsible for keeping the timer reasonably visible for most of the footage. The extreme copies above are limits to document, not bugs to fix.
- Ask about ML ([ADR-0001](adr/0001-offline-classic-cv-no-ml.md)) only if this fails on the reference videos. New digit shapes go behind the [`DisplayReader` seam](adr/0014-display-reader-seam.md).
- Digit templates come from bundled open-licence fonts ([ADR-0016](adr/0016-find-the-timer-without-ml.md)). With 10 of them (without csTimer's look-alike) and 12 learned shapes per digit, the prototype gave 25 of 25 on all three videos, the same as with the Windows fonts.
- No reference video may get worse, including the ones measured with a manual setup.
- **Decided (owner, 2026-11-24):** a solve the clock doesn't confirm is kept and flagged for checking, never left out. Solves already found the old way stay as they are. `TimerSetupScreen`'s missing `BackHandler` is fixed in this phase, in its own commit.
- **Decided (owner, 2026-11-24):** the app asks which number is the timer, with a picture, only when it isn't sure. Re-scanning an old video works as today.

**Build steps**, each with its own commit and a phone test:
1. ✅ Solid-digit reader (`SolidDigits.kt`), not used by the app yet.
2. ✅ Finder and learner (`TimerFinder.kt`), shown only in the debug scan report. On the owner's phone, video 01 gave the same result as on the PC, in 48 s; made faster since.
3. ✅ Scan (`FinderScan.kt`): frame-by-frame cuts, the clock check, a time that reappears. Only for timers the finder found, and only in the debug scan report. Phone, video 01: 25 of 25, no false solves or wrong times, 2 min for 21 min of video ([numbers](algorithms.md#3-reading-the-timer)).
4. ✅ *Find solves* uses it; manual setup as the fallback; the `BackHandler` fix. Tested by the owner on the phone (2026-11-24). Decided (owner, 2026-11-24): the Setup box says CubeCut finds the timer, with *Set up by hand* as a small button; only the place of a found timer is remembered; *Read timer here* only after a setup by hand; *Find it by itself again* drops that setup; *Couldn't find the timer* shows what to try and a *Set up by hand* button.
5. ➖ Follow a camera that moves: not built (owner, 2026-11-24). A fixed camera is a requirement, told to the user; small moves are tolerated, measured in [algorithms](algorithms.md#3-reading-the-timer).
6. ✅ Times over a minute (the colon, 2026-11-25). The owner's three 4x4 videos: 1 of 1 each, right time, on the frame; the csTimer sessions unchanged ([numbers](algorithms.md#3-reading-the-timer)). Tested by the owner on the phone (2026-11-25).
7. ✅ A csTimer-like timer with another font (flowtimer.io, 2026-11-25): 28 of 28 solves in two videos, no false solves or wrong times, every cut on the frame; 7 flagged, because flowtimer changes the time when it stops ([numbers](algorithms.md#3-reading-the-timer)). Tested by the owner on the phone (2026-11-25).

**Seven-segment prototype (2026-11-25)**, outside the app, on the two old QiYi sessions; no corners, no typed number, no ML. Numbers against the answer keys (±1 frame), PC:

| Video | Found | Missed | False | Wrong time | Within 0.1 s | Worst cut | Set up by hand, on the phone |
|---|---|---|---|---|---|---|---|
| 01 (developed on) | 55 / 55 | 0 | 0 | 0 | 55 | 0.067 s | 55 found, 13 false, 3 wrong, 45 within 0.1 s |
| 02 (see below) | 30 / 30 | 0 | 0 | 0 | 30 | 0.033 s | 28 found, 6 false, 2 wrong, 23 within 0.1 s |

- **02 was not found by the version frozen before it was looked at.** Its display is turned about 33°, and the search only knew small slopes. One change fixed it: searching the picture turned by −30° and +30° too. So 02 is a clean test of reading and scanning, which didn't change, but not of the search.
- **How it reads:** joined strokes give a first row of digits; from it comes a **grid** of digit places (slope, lean, spacing, the point's gap). Then each place is compared with the ten seven-segment shapes, so a faint or missing stroke no longer breaks a digit. Seconds and tenths rising with the clock in 6 pictures make it the timer; the last two decimals blur while it runs.
- **The QiYi slides** (up to 73 × 92 px in 01) and jumps when it is hit, so its place is followed, only where the digits fit well. A grid half a digit too low read `0.000` as a held `4.444` before that rule. One grid for the whole video is refined from 24 pictures; starting from one place alone read 7 as 1.
- **No false finds** in csTimer 01 and 02, flowtimer 01 and 02 and 4x4 01 (160 places per search; the plain search was only run again on csTimer 01 and flowtimer 01 after the last change). The small QiYi in csTimer's videos isn't picked up.
- **Altered copies of 01**, all 55 found with nothing false or wrong: 30 fps, 25 % brightness, washed out, inverted, noise 10, blur 1.5 px, 0.7 and double size, turned ±15° and +25°, sheared, and five other starting places. Squeezed to 60 % height: 54 (a solve whose stop was covered). **Not found:** 12 % brightness, half size (19 px digits), blur 2.5 px, motion blur 9 px.
- **Not tested:** any other seven-segment timer (Stackmat, GAN), times over a minute on one, LED digits in real footage, two timers in view, the phone's speed.

The scripts are in `D:\CubeCutRef\prototype\qiyi` (`frozen_q1` before 02, `frozen_q2` the version measured).

**Steps in the app** (owner's go 2026-11-25; each with its own commit and phone test):
1. ✅ Reader and finder (`SegmentRows.kt`, `SegmentGrid.kt`, `SegmentFinder.kt`), only in the debug scan report ([how](algorithms.md#3-reading-the-timer)). Tested by the owner on the phone (2026-11-25): both QiYis found, in 8.6 s and 5.3 s.
2. ✅ Following and scanning (`SegmentTracker.kt`), only in the scan report. Tested by the owner on the phone (2026-11-25): 55 / 55 and 30 / 30, no false solves or wrong times, one cut 0.27 s late (fixed since).
3. ✅ *Find solves* uses it. Decided (owner, 2026-11-25, [ADR-0017](adr/0017-finder-first-setup-by-hand-as-backup.md)): the new way goes first on **every** video, also those set up by hand before; seven-segment is tried only when no solid number runs; a setup by hand is used only when no timer is found, and is a small *Backup* part of the Setup box. Tested by the owner on the phone (2026-11-25).
4. ✅ **Hints where a solve may be missing** (owner, 2026-11-25; brought forward from 4.0-D). Drawing approved; reasons 1 to 3 only, so a long gap alone gives no hint; saved with the video (DB v8); hints never become clips. Built ([how](algorithms.md#3-reading-the-timer)); tested by the owner on the phone (2026-11-25): "the hints work great"; a solve covered from start to reset gets none, which is fine. The test video showed a first solve lost to a push of the timer (fixed: a lost timer is looked for further away; tested by the owner on the phone, 2026-11-25). Between two solves in the editor's list: where, why, *Go there* (the playhead jumps to where the timer was last seen running; then *Add a missed solve*) and *Nothing here* (gone for good, also after a re-scan). Strongest reason first:
   1. the timer ran, but no final time was found;
   2. it ran and then showed 0.000;
   3. it couldn't be read for a long stretch;
   4. the gap is much longer than usual in this session.

   Built with 4.0 in mind: finding, saving and the card don't depend on the editor. Check it with the reference videos: no hint where nothing is missing, one where a solve is taken out.

**After QiYi: timers that don't show a running time with decimals** (owner's request, 2026-11-25). Built for both: found by their final times ([how, and numbers](algorithms.md#3-reading-the-timer)). Tested by the owner on the phone (2026-11-26): "works well", also a new flowtimer video and csTimer's own "solve" screen. Owner: a time that can't be confirmed is kept and marked *check*.
- ✅ **Whole seconds while running**, decimals only when stopped. Whole numbers still rise with the clock, and the moment each second changes gives the start, so the clock check can stay. The inspection countdown must not count (it doesn't agree with any time).
- ✅ **Running time hidden** ("solve", "..."). Nothing rises: look for a big time that appears, stays and changes after each solve. No clock check, so more solves to check; a tap to choose the timer may be needed.
- A QiYi in view of such a video must not be taken for the timer.
- Videos (owner, 2026-11-25): flowtimer with "..." (6 solves), and a friend's TikTok clip of csTimer with whole seconds (5 solves). The old csTimer videos with their running time ignored are the third test.

**csTimer's digit fonts** (owner's videos, 2026-11-26: "digital 1" on the light theme, "digital 4" on the dark one). None of csTimer's five fonts may ship, so they are drawn on the PC only ([numbers](algorithms.md#3-reading-the-timer)).
- ✅ **"Digital 1"**: Digital Numbers (OFL) added to the bundled fonts (owner's go). Its video: 5 of 5, every cut on the frame. Tested by the owner on the phone (2026-11-26): the light video works, the dark one says *Couldn't find the timer*, as expected.
- ✅ **A digit in pieces makes a row unreadable**, so "digital 4" gives *Couldn't find the timer* instead of five wrong times.
- ✅ **"Digital 4" and "digital 5"** (owner's choice B): read with the seven-segment reader, which now also finds a timer by its final times, last, when nothing else was found. The "digital 4" video: 4 of 4, 3 on the frame, and the time already on screen at the start flagged ([numbers](algorithms.md#3-reading-the-timer)). Tested by the owner on the phone (2026-11-26): the "digital 4" video works.
- ⬜ **"Digital 2" and "digital 3"** stay weak (drawn: 4 and 2 of 18): their digits break into many pieces. Only with a video of them.

- The per-video timer columns stay for the backup.

### 3.3-E Penalty marks and long times on the display ⬜
- Read +2 and DNF marks next to the time, and times of one hour or more.
- When a mark is read, fill in the 3.2 penalty automatically. The user can still change it.

### 3.3-Z Release prep 3.3 ⬜
Write the before and after numbers into [algorithms §8](algorithms.md#8-reference-measurements).

Also phone-test **backup and restore** from 3.2 in the full pass. The owner moved that test here (2026-11-23). Test *Back up now*, *Restore* after a reinstall, *Reconnect videos*, *Reconnect exported clips*, and a restore on a second phone if one is available ([known issue](known-issues.md#not-verified)).

---

## v4.0: Find solves in the background

**Prerequisite:** 3.3 has been measured as reliable on several fonts.

The app already opens on the Gallery (Clips). What changes is that **importing no longer opens the editor**.

### 4.0-0 Remove the setup by hand ⬜
Owner, 2026-11-25 ([ADR-0017](adr/0017-finder-first-setup-by-hand-as-backup.md)). First in 4.0: 4.0-A then has no engine to move.
- **Only when** timers that hide their running time are read (3.3-C), the hints of 3.3-C step 4 work, and another seven-segment timer than the owner's QiYi was tried if one can be had.
- Goes: `TimerSetupScreen`, `TimerReader`, `DisplayReaders`, the scan in `SolveAnalyzer` (its constants move to `FinderScan`), *Read timer here*, the *Backup* part of the Setup box, the "by hand" half of the scan report.
- Stays: the solves found with it; the timer columns, for the place of a found timer.
- Check whether OpenCV can go too. It is probably most of the app's size, and the new readers don't use it; `FrameSource` hands out its pictures.

### 4.0-A Background analysis (ADR and refactor) ⬜
- Move Find solves out of `EditorScreen` (`runFindSolves`) into an app-wide analysis queue: a Kotlin `object` whose work runs in the foreground service.
- This needs a new ADR that supersedes [ADR-0011](adr/0011-foreground-service-for-notification-only.md). It also touches [ADR-0003](adr/0003-single-module-plain-compose-state.md).
- Invariant 1 becomes a **per-video lock**: only actions on the video being scanned are disabled.
- Engine ownership (`startUsing` / `stopUsing` / `retireEngine`) moves with the scan.

### 4.0-B Import starts the scan ⬜
- Importing a video starts *Find solves* by itself, in the background. The editor doesn't open, and the app stays navigable (owner, 2026-11-25).
- **Progress** (owner, 2026-11-25): a discreet section somewhere in the app that the user can expand to see how each imported video is doing. Collapsed, it is a slim bar. Several videos queue.
- Only the actions that would disturb the scan are disabled.
- A notification says when the scan is done.

### 4.0-C "New" badge ⬜
DB v9 (`cuts.seen_ms`). Recommended, to confirm:
- The badge goes away when the clip is opened in the viewer or exported, or with *Mark all as seen*.
- Add a *New* filter chip.

### 4.0-D Review possible missed solves ⬜
The hints themselves come earlier, in 3.3-C step 4: they are found without the editor and saved with the video, and a hint's card is its own composable. Left for 4.0, where importing no longer opens the editor:
- When a background scan has hints, they appear as a **popup** (owner, 2026-11-25). Only if the user takes one up does the editor open, at that place. *Nothing here* works from the popup too.
- To decide: how the popup waits when the user is busy elsewhere in the app, and where hints the user didn't take up can be found again (for example "Might have missed N" on the footage card).

### 4.0-E The editor as manual mode ⬜
- What CubeCut can't read is added here by hand (owner, 2026-11-25): no setup by hand any more.
- The editor stays, for clipping by hand. It is reached from Footage ⋮ *Open in editor*.
- It is offered when a scan finds nothing, or when the timer isn't visible.

### 4.0-Z Release 4.0 ⬜

---

## Deferred earlier (not planned)
- Keep every export copy. Both delete buttons would then need to delete all copies.
- A "too short" time check. Rejected, because some solvers get very short times.
- Remember deleted cuts, so a re-scan can't bring them back.
- Permanent cut ids.
- Large-gallery speed: `canOpen` runs on every resume, and the editor list isn't lazy.
- Splitting `Gallery.kt` and `EditorScreen`.
- TalkBack labels and 48 dp touch targets.
- Remove `TimerStore.clear`.
- An exact (re-encoding) export option.
- A cropped ORB search near the last position (declined).
