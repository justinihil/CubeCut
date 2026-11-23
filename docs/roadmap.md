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

### 3.3-A Ground-truth harness 🔶
- The owner records reference videos for each timer:
  - csTimer on a PC;
  - phone timer apps;
  - Stackmat, GAN and QiYi physical timers.
- The videos stay on the phone and are **never committed**.
- The repo gets:
  - expected-solve files (JSON);
  - unit tests for `SolveAnalyzer.scan` with a fake `read(t)`;
  - a debug-only scan report that shows found, missed and false solves.
- Record the baseline in [algorithms §8](algorithms.md#8-reference-measurements).

**Decided** (2026-11-23):
- Timer priority: csTimer on a PC, then phone timer apps, then Stackmat, then GAN / QiYi.
- No reference videos are recorded yet. The harness was built first, and the real-footage baseline rows are filled in as the owner records them ([algorithms § 8](algorithms.md#ground-truth-harness-33-a)).
- This thread did 3.3-A only. B to E wait until reference videos exist, so each change can be measured.
- The ML question for 3.3-D is asked when D starts.

**Built:** `FakeDisplay` + `SolveAnalyzerScanTest` (the simulated baseline), `GroundTruth.kt` + `GroundTruthTest`, the debug *Scan report* in the editor (`ScanReport.kt`), and 1-frame buttons in the *Edit* window, so a hand-made answer key is frame-exact.

**Waiting on the owner (2026-11-23):** record reference videos (checklist in [algorithms § 8](algorithms.md#ground-truth-harness-33-a)), save their reference files, and send them with the *Compare* reports. Then fill in the real-footage baseline and close 3.3-A. Set 1 (two or three csTimer-LCD or Stackmat videos) is enough to start 3.3-B.

### 3.3-B Scan accuracy ⬜
- *Earlier note:* a held time misread without its last digit (`1151` while `11517` is held) should count as the same hold in `scan`. 3.3-A reproduced it: the short read can even win the vote. Un-ignore `heldTimeMissingLastDigit_isTheSameHold`.
- *From the 3.3-A simulator:* 71 of 880 cuts land 2 or more frames off (worst 0.18 s). A running frame just before the stop, read with one 4→1 error, passes `similar` for the final time. Raise *on the frame* without lowering the other numbers.
- Check whether readings like `0.004` of 0.000 occur.
- The too-fast check stays as a safety net.
- Start: [algorithms §1–4](algorithms.md); `SolveAnalyzer.kt`.

### 3.3-C Find the display automatically (no setup) ⬜
- Search sampled frames for display candidates, using `ShapeFinder` in `TimerReader.kt`.
- `TimerSetupScreen` stays as a manual fallback ("Set up manually"). Ask whether to fix its missing `BackHandler` here ([known issue](known-issues.md)).
- This replaces the need for the per-video timer columns, but keeps them for the fallback.

### 3.3-D More fonts ⬜
- **First, an ADR.** Does a bundled, offline digit classifier count as "ML" under [ADR-0001](adr/0001-offline-classic-cv-no-ml.md)?
- Then add readers behind the [`DisplayReader` seam](adr/0014-display-reader-seam.md), starting from the disabled `GlyphReader` ([recipe](../ARCHITECTURE.md#new-display-reader-another-font)).
- csTimer's default look has smaller digits after the point. The segment reader measures all digits in one height band ([known issue](known-issues.md)). A per-digit band might be a small fix inside `SegmentReader`; measure it on the default-font reference videos.

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
