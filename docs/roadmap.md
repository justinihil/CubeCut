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
| **3.3** | `dev-v3.3.0` | Better Find solves: no timer setup, more fonts, more accurate | — |
| **4.0** | `dev-v4.0.0` | Find solves runs in the background after import | v8 |

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
- the phone test of the scan report (build 89c9a3d), so the report itself is proven on a phone;
- the QiYi baseline (easy and hard, with the manual setup). It must exist before 3.3-C changes anything for seven-segment timers;
- csTimer videos of another setup: other font or light theme, camera further away, hands over some stops. The three above share one desk, font and theme;
- phone timer apps, Stackmat and GAN (sets 3 to 5) can follow later; they don't hold up csTimer.

**Reworked (2026-11-24):** the report first needed a working timer setup, so it couldn't test the videos that fail there. It now grades finding the timer, reading and scanning separately, always runs, and also takes a plain list of times.

### 3.3-B Scan accuracy ⬜
- *Earlier note:* a held time misread without its last digit (`1151` while `11517` is held) should count as the same hold in `scan`. 3.3-A reproduced it: the hold often splits, and rarely the real time is lost. Un-ignore `heldTimeMissingLastDigit_isTheSameHold`.
- *From the 3.3-A simulator:* 71 of 880 cuts land 2 or more frames off (worst 0.18 s). 65 of them are late and come from unreadable frames, so look at what `firstAppearance` does when a read in the middle is empty; 6 are early ([numbers](algorithms.md#ground-truth-harness-33-a)). Raise *on the frame* without lowering the other numbers.
- Check whether readings like `0.004` of 0.000 occur.
- The too-fast check stays as a safety net.
- Start: [algorithms §1–4](algorithms.md); `SolveAnalyzer.kt`.

### 3.3-C Find and read the timer by itself 🔶
**Goal (owner, 2026-11-24):** import raw footage, and CubeCut finds the timer, learns how it looks and reads it. Borders, font, angle and place must not matter. No corners, no typed number. `TimerSetupScreen` stays only as a manual fallback.

**Planned method:**
1. **Search:** find every number-like row in frames spread over the video.
2. **Watch:** the timer is the number that **runs, then freezes**. In one second of video it rises by 1.00; averages, session lists and scrambles don't.
3. **Learn:** remember its place and digit shapes in this video, and use them on harder frames.
4. **Follow:** search again when it moves or is covered.
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

**Not tested:** other fonts and themes in real footage, phone timer apps, times over a minute (no colon), timers without decimals while running, +2 / DNF marks, seven-segment digits, two readable timers, a hand over the digits in real footage.

**Special to these videos:** none of the rules are, but the numbers were tuned on one desk, one font and one person. A stopped time with two decimals and a running one with one is learned, not assumed. Only `0.00` counts as a reset.

**Speed (PC, one thread, Python):** 112 ms to search a full frame, 10 ms to read the known area. For video 03 that was about 1500 area reads at 0.5 s and 950 frame by frame; search 6 s, learning 13 s, scan 52 s, mostly starting ffmpeg per read. **The phone is not measured.** Video length × 0.07 s ([algorithms § 8](algorithms.md#8-reference-measurements)) suggests about 80 s to decode this 19-minute video, a guess.

The scripts are in `D:\CubeCutRef\prototype\v2` (`frozen_v2c` is the version measured).

**Rules:**
- **Good enough (owner, 2026-11-24):** it doesn't have to work in every scenario. It has to be reliable in a reasonable setting, and the user is responsible for keeping the timer reasonably visible for most of the footage. The extreme copies above are limits to document, not bugs to fix.
- Ask about ML ([ADR-0001](adr/0001-offline-classic-cv-no-ml.md)) only if this fails on the reference videos. New digit shapes go behind the [`DisplayReader` seam](adr/0014-display-reader-seam.md).
- Digit templates come from bundled open-licence fonts ([ADR-0016](adr/0016-find-the-timer-without-ml.md)). With 10 of them (without csTimer's look-alike) and 12 learned shapes per digit, the prototype gave 25 of 25 on all three videos, the same as with the Windows fonts.
- No reference video may get worse, including the ones measured with a manual setup.
- **Decided (owner, 2026-11-24):** a solve the clock doesn't confirm is kept and flagged for checking, never left out. Solves already found the old way stay as they are. `TimerSetupScreen`'s missing `BackHandler` ([known issue](known-issues.md)) is fixed in this phase, in its own commit.
- **Decided (owner, 2026-11-24):** the app asks which number is the timer, with a picture, only when it isn't sure. Re-scanning an old video works as today.

**Build steps**, each with its own commit and a phone test:
1. ✅ Solid-digit reader (`SolidDigits.kt`), not used by the app yet.
2. ✅ Finder and learner (`TimerFinder.kt`), shown only in the debug scan report. On the owner's phone, video 01 gave the same result as on the PC, in 48 s; made faster since.
3. ✅ Scan (`FinderScan.kt`): frame-by-frame cuts, the clock check, a time that reappears. Only for timers the finder found, and only in the debug scan report. Phone, video 01: 25 of 25, no false solves or wrong times, 2 min for 21 min of video ([numbers](algorithms.md#3-reading-the-timer)).
4. ⬜ *Find solves* uses it; manual setup as the fallback; the `BackHandler` fix.
5. ⬜ Follow a camera that moves.

Seven-segment timers come after the QiYi baseline.
- The per-video timer columns stay for the fallback.

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

### 4.0-A Background analysis (ADR and refactor) ⬜
- Move Find solves out of `EditorScreen` (`runFindSolves`) into an app-wide analysis queue: a Kotlin `object` whose work runs in the foreground service.
- This needs a new ADR that supersedes [ADR-0011](adr/0011-foreground-service-for-notification-only.md). It also touches [ADR-0003](adr/0003-single-module-plain-compose-state.md).
- Invariant 1 becomes a **per-video lock**: only actions on the video being scanned are disabled.
- Engine ownership (`startUsing` / `stopUsing` / `retireEngine`) moves with the scan.

### 4.0-B Import starts the scan ⬜
- Importing queues a scan, and the app stays on Clips.
- A slim progress bar shows on the Gallery while the rest of the app stays usable.
- Only the actions that would disturb the scan are disabled.
- A notification says when the scan is done.

### 4.0-C "New" badge ⬜
DB v8 (`cuts.seen_ms`). Recommended, to confirm:
- The badge goes away when the clip is opened in the viewer or exported, or with *Mark all as seen*.
- Add a *New* filter chip.

### 4.0-D Review possible missed solves ⬜
- `ScanResult` keeps the stretches that may hide a solve:
  - a running timer seen but no stop found;
  - long unreadable stretches;
  - gaps much longer than the session's usual rhythm.
- The footage card says "Might have missed N".
- Its review list shows a still for each stretch, with two choices:
  - *Add here* opens the editor at that time;
  - *Not a solve* dismisses it.
- This changes the scan, so check it with the 3.3 harness.

### 4.0-E The editor as manual mode ⬜
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
