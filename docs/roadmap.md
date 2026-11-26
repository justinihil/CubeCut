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
Start v4.0: create dev-v4.0.0 from dev, then Phase 4.0-0.
```
**To start a phase** (change the phase id and the branch):
```
Phase 4.0-0 from docs/roadmap.md, branch dev-v4.0.0.
Read CLAUDE.md, then that phase section, then only the files it names.
Ask me the phase's open questions first. Then build it, update the owning docs + CHANGELOG in the same commit,
run assembleDebug + testDebugUnitTest, commit, and give me numbered phone test steps.
```
**During the phase:**
- `Phone test: step 4 failed — <what I saw>` (attach a screen recording for anything visual).
- `All steps pass — close the phase`: Claude marks the phase ✅ here and commits.

**To end a version** (its `-Z` phase):
```
Release prep for v4.0 (phase 4.0-Z).
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
| **4.0** | `dev-v4.0.0` | Find solves runs in the background after import | v9 |

The owner's list had three versions (v3.1, v3.2, v4.0). The first was split in two so that the quick wins, which need no database change, ship first. The owner's "v3.2" (Find solves) became 3.3, released 2026-11-26.

---

## v4.0: Find solves in the background

**Prerequisite:** 3.3 has been measured as reliable on several fonts.

The app already opens on the Gallery (Clips). What changes is that **importing no longer opens the editor**.

### 4.0-0 Remove the setup by hand ⬜
Owner, 2026-11-25 ([ADR-0017](adr/0017-finder-first-setup-by-hand-as-backup.md)). First in 4.0: 4.0-A then has no engine to move.
- **Only when** timers that hide their running time are read (3.3-C), the hints of 3.3 work, and another seven-segment timer than the owner's QiYi was tried if one can be had.
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
The hints themselves came in 3.3: they are found without the editor and saved with the video, and a hint's card is its own composable. Left for 4.0, where importing no longer opens the editor:
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
