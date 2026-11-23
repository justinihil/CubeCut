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
Start v3.2: create dev-v3.2.0 from dev, then Phase 3.2-A.
```
**To start a phase** (change the phase id and the branch):
```
Phase 3.1-A from docs/roadmap.md, branch dev-v3.1.0.
Read CLAUDE.md, then that phase section, then only the files it names.
Ask me the phase's open questions first. Then build it, update the owning docs + CHANGELOG in the same commit,
run assembleDebug + testDebugUnitTest, commit, and give me numbered phone test steps.
```
**During the phase:**
- `Phone test: step 4 failed — <what I saw>` (attach a screen recording for anything visual).
- `All steps pass — close the phase`: Claude marks the phase ✅ here and commits.

**To end a version** (its `-Z` phase):
```
Release prep for v3.1 (phase 3.1-Z).
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
| **3.1** | `dev-v3.1.0` | Polish and quick wins | stays v6 |
| **3.2** | `dev-v3.2.0` | Organise your clips: tags, penalty, per-clip buffers, *Shared on*, single solves, backup | v7 |
| **3.3** | `dev-v3.3.0` | Better Find solves: no timer setup, more fonts, more accurate | — |
| **4.0** | `dev-v4.0.0` | Find solves runs in the background after import | v8 |

The owner's list had three versions (v3.1, v3.2, v4.0). The first was split in two so that the quick wins, which need no database change, ship first. The owner's "v3.2" (Find solves) became 3.3.

---

## v3.1: polish and quick wins

The phases go editor first, then viewer, then Gallery, so each thread opens only one of the big files. There is no schema change in this version.

### 3.1-A Editor quick wins ✅
Items:
- **#14 Manual cut before Find solves.** The *Add a missed solve* button shows as soon as a video is loaded.
- **#1 Zoomed timer in the Edit window.** The frame is cropped around the saved timer corners plus a margin.
- **#8 Warn on re-import.** Importing a video that was already imported shows a warning.

**Decided** (2026-11-23):
- The two switches stay hidden until Find solves has run or a solve exists.
- The Edit window has a *Whole frame* toggle. It starts zoomed each time the window opens.
- The crop is fixed at the saved corners (still camera assumed), with a bigger margin: half the timer's width on every side.
- Added by the owner during the phase: a solve added by hand is selected, scrolled to, and its card blinks (3 blinks, under 2 s). If the check filter would hide it, the filter turns off, as *Go to solve* already does.
- Found in the phone test: *Find solves again* / *Re-scan done* now depend on whether a scan ran (`scanned`), not on whether solves exist.

Built and phone-tested (2026-11-23): `Reimport.kt`, `SessionStore.findKnown`, `ThumbCache.timerFrame`. The warning covers both *Import video* buttons.

Where to start:
- **#14:** `showTools` in `EditorScreen` (`MainActivity.kt`) hides the button and both switches until Find solves has run or solves exist. `startManualCut` itself needs no scan.
- **#1:** the Edit window uses `ThumbCache.frame` (`Thumbnails.kt`). The corners come from `SessionStore.timerCornersOf(footage)`. Use the whole frame when no setup is saved.
- **#8:** `SessionStore.openOrCreate` already returns `isNew`. The editor's "when a video is chosen" effect reopens the saved session silently. Show a dialog first: "Imported before: N solves, last scanned …", with *Open saved* / *Cancel*. Identity rules: [ADR-0005](adr/0005-video-identity-heuristics.md).

Ask first:
- Do the two switches (scramble, check filter) stay hidden until solves exist?
- Should the Edit window have a *Whole frame* toggle?
- The crop assumes a still camera. With tracking, the display may have moved. Is that acceptable?

Rules:
- Invariant 1: disable the new actions while `analyzing`.
- The pinned layout (`app/README.md`) is an owner decision. Say so if the change moves anything.

### 3.1-B Viewer: auto-replay and double-tap skip ✅
Items:
- **#12 Auto-replay toggle.** Clips loop when it is on. The choice applies to every clip viewed from then on.
- **#13 Double-tap to skip.** Double-tap the left or right side to skip back or forward.

**Decided** (2026-11-23):
- The toggle is only on the overlay: a *Replay: on/off* chip next to *Skip 5s ▾*. Not in Settings.
- A single tap may wait about 0.3 s (`DOUBLE_TAP_MS`). A double-tap doesn't show or hide the controls.
- Left and right halves of the picture.
- Further quick taps on the same half keep skipping (`SKIP_STREAK_MS`), and the hint counts up.

Built and phone-tested (2026-11-23): `PictureTaps` / `onPictureTap` and `PlayerUse` in `Gallery.kt`; `ui.autoReplay` (pref `auto_replay`).

Where to start:
- `ClipViewer` and `PlayerOverlay` (`Gallery.kt`).
- The player is built in `ClipViewer` and never sets `repeatMode`. Auto-replay uses `REPEAT_MODE_ONE`.
- Persist the toggle as an app setting ([recipe](../ARCHITECTURE.md#new-persisted-app-setting-not-per-video)).
- A single tap toggles the controls (`detectTapGestures(onTap = …)`). Add `onDoubleTap` and skip by `skipSeconds`, with a short "−5 s" hint.

Ask first:
- Where does the toggle go: on the overlay, in Settings, or both?
- Adding double-tap delays a single tap by about 0.3 s. Is that acceptable?

Rules:
- The viewer flicker rules in [algorithms §6](algorithms.md#6-viewer-still-to-video-handover).
- Players take audio focus (settled).

### 3.1-C Flicker at the top and bottom of the player (low priority) ✅
**Found** (2026-11-23, 120 fps recording, every frame): the flicker shows only when a clip is opened from the Gallery, not after a swipe, and not every time (6 of 8 opens). For 2 frames, the first video picture is stretched over the whole page, because a new player doesn't know the video's size yet. The still covered the middle, but its transparent background let the stretched top and bottom show. Details: [algorithms §6](algorithms.md#6-viewer-still-to-video-handover).

**Fix:** the still's background is black on the playing page too, and it fades with the still. Nothing else in the handover changed. The one still per page is still never removed.

Where to start:
- `ClipViewer` and `PlayerOverlay` (`Gallery.kt`).
- `res/layout/video_player_view.xml`.

Before any change:
1. Read [algorithms §6](algorithms.md#6-viewer-still-to-video-handover). A previous flicker took five rounds.
2. The owner sends a 120 fps screen recording.
3. Inspect every frame with `ffmpeg -fps_mode passthrough`.

Keep one still per page that is never removed. This phase gets its own thread.

### 3.1-D Rethink check navigation ⬜
**#6:** items vanish from the filtered list without warning, and the selection after a delete is still confusing.

Where to start: in `EditorScreen`:
- `stepCut`;
- the *Only show solves to check* filter;
- `needsCheck` and `checkCuts`, which are recomputed whenever cuts change;
- the slide-out on *It's fine*;
- `deleteCut` / `requestDelete` and the Undo bar.

Likely cause (not yet verified): the filtered list is derived live. Any edit that clears a solve's check removes that solve at once, even off-screen. For example, changing a neighbour can fix an overlap.

Recommended direction (offer it as an option; the owner decides):
- **Frozen list.** While the filter is on, the list doesn't change. A solve that no longer needs a check stays in it, marked "✓ Fixed", until the filter is switched off and on again.
- **Clear position after a delete.** The next solve is selected, and the Undo bar says "Deleted Solve 7 · now on Solve 8".

Settled, so don't change:
- The filter switch and *Mark all as checked* stay on separate lines.
- Both solves of a bad pair are flagged.
- Playing doesn't change the selection.
- Delete comes with Undo.

### 3.1-E Gallery selection: batch delete and scramble for all ⬜
Items:
- **#2 Batch delete of exported clips**, with warnings.
- **#9 Scramble for all clips.** Batch scramble on a selection **already exists**: it is the switch in the selection header (`setScrambleForSelected`, `Gallery.kt`). What is missing is a *Select all (shown)*.

Where to start:
- The selection header in `GalleryScreen` (`runBatchExport` is next to it).
- Trash goes through `createTrashRequest` and `SessionStore.setExportTrashed`.

**Decided (earlier note):**
- A red bin next to Export.
- *Export only* and *Solve deleted* clips become tickable, and Export skips them.
- Warning lines:
  - ✕ files to trash;
  - ✕ clips that leave the Gallery;
  - ✓ solves and notes stay;
  - ↺ Recently deleted.

Ask first:
- Is delete part of the same selection mode, or a separate one?
- Should Settings also have a default "include scramble" for newly imported videos?
- Is a Gallery-wide scramble a video setting (no `edited`) or a change to each clip (`edited`)? See [ADR-0013](adr/0013-rescan-merge-keeps-user-edits.md).

Rules:
- [ADR-0008](adr/0008-never-delete-permanently.md): trash only, an `EffectLine` warning, a red `DangerButton`, one trash request for all the files.
- Re-export is allowed but pointed out (settled).

### 3.1-Z Release prep 3.1 ⬜
- Bump to `versionCode 5` / `versionName "3.1.0"`. This also fixes the "version not bumped for 3.0.1" [known issue](known-issues.md).
- See [How to run a phase](#how-to-run-a-phase) for the rest.

---

## v3.2: organise your clips (DB v7)

Order:
1. All schema plumbing first, with no UI (3.2-A), so the later threads only build screens and never touch `saveSession` or `mergeRescan`.
2. Tags, penalty, buffers and *Shared on* (3.2-B to E).
3. Single-solve import, which uses the tags, penalty and buffers (3.2-F).
4. Backup last, because its file must include every new field (3.2-G).

### 3.2-A Schema foundation (no UI) ⬜
Where to start: the [new per-video or per-clip field recipe](../ARCHITECTURE.md#new-per-video-or-per-clip-field-for-example-a-tag-or-a-penalty). Files:
- `SessionStore.kt` (`Helper.onCreate` / `onUpgrade`, `loadCuts`, `saveSession`, `loadGallery`);
- `ClipExporter.kt` (`CutItem`, `computeClips`);
- `RescanMerge.kt`;
- `app/src/test`.

Steps:
1. **Lock the current behaviour first.** Write unit tests for `computeClips`, `parseTimeSeconds`, `mergeRescan` and `checkCuts` on today's code, and commit them.
2. **Add one `if (oldVersion < 7)` step:**
   - `footage`: `puzzle TEXT ''`, `category TEXT ''`, `kind TEXT 'session'` (`'single'` for 3.2-F), plus the trim fields that 3.2-F needs.
   - `cuts`: `puzzle` and `category` (`''` = follows the video), `plus2_count INTEGER 0`, `dnf INTEGER 0`, `before_pad` and `after_pad` (`REAL`, NULL = follows the video), `shared_on TEXT ''`.
   - One-off: fill `cuts.shared_on` from `platformsIn(user_note)`. Leave the note text unchanged.
3. **Plumbing:**
   - `CutItem` fields with defaults; reads in `loadCuts`; writes in the `saveSession` insert; `GalleryClip`.
   - `mergeRescan` keeps every user-set field.
   - One `update…` function per Gallery write, shaped like `updateCutScramble`, which sets `user_edited = 1`.
4. **`computeClips`** uses per-cut pads only when they are set. The tests from step 1 must still pass unchanged.

Rules:
- Invariant 9: bump `DB_VERSION`, never edit old steps.
- Invariant 4: a user-set value marks the cut `edited`.
- Changing `computeClips` is the task here, so it is allowed. The tests prove nothing else moved.

Docs: [data-model.md](data-model.md), the [glossary](glossary.md) and the CHANGELOG.

Phone test: install over 3.1 and check that existing solves, notes and exports survive the upgrade.

### 3.2-B Puzzle and category tags ⬜
**#3**, the owner's requirements:
- The **WCA puzzles**, including FTO and Clock.
- **Other**, with free text.
- Categories such as relay and PLL attack.
- Encourage **one puzzle per video** at import.
- An easy fix for mixed footage:
  - in the editor, tag the whole video, then untick the clips that don't belong;
  - tag those clips in the Gallery.
- Adjust filters and sorts to match.

**Decided:**
- The editor is crowded, so it gets only *Puzzle for this video* (in the Setup box) and a per-card "not this puzzle" untick.
- Per-clip tagging lives in the Gallery: *Set puzzle…* on a selection and in the viewer sheet.
- A clip follows its video's tag unless the tag is set on the clip itself.

*Earlier note:*
- Two tags:
  - *Puzzle*: 3x3, 2x2, 4x4–7x7, Megaminx, Pyraminx, Skewb, Square-1, Clock, FTO, Other;
  - *Type*: Normal, OH, BLD, Relay, PLL attack, Other.
- Places to set them: an import dialog ("one puzzle per video works best", with *Decide later*), the Setup box, the Edit window, the Gallery selection, the viewer sheet and the Footage menu.

Ask first:
- The final puzzle and category lists.
- One list or two?
- Does the puzzle go into file names?
- Grouping or sorting by puzzle in the Gallery?

Where to start, Gallery filter and sort: `ClipKey`, `ExportFilter`, the Filter dialog and `GalleryUiState` (`Gallery.kt`).

### 3.2-C Penalty (+2 / DNF) ⬜
**#7:**
- A DNF shows as the time in brackets, for example `DNF(12.34)`.
- +2 can be added several times, and the time shown includes them.
- In the editor, and maybe in the Gallery.
- Adding a penalty to an exported clip renames the file, but the clip keeps its link to the export.

**Decided:**
- The penalty is set in the Edit window and in the viewer sheet.
- An exported file is **renamed**, and the export stays linked by `stop_sec`.

Ask first:
- The exact title and file-name format, for example `DNF(12.34)` and `16.34 (+2×2)` / `16.34+`.
- Does the clip start (solve-only clip) use the raw time or the penalised time? It should use the raw time on the timer, because that is what was filmed.

Where to start:
- `CutItem.timeText` and `parseTimeSeconds` (`ClipExporter.kt`);
- the Edit window;
- the Gallery viewer sheet;
- `ClipExporter.fileName`.

Rules:
- The time drives the solve-only clip start and the file name.
- The export link uses `stop_sec`, so it survives a time change ([data-model](data-model.md#how-exports-link-to-cuts)).
- **Renaming is new behaviour.** Today an exported file is never touched again.
  - Update `DISPLAY_NAME` through `ContentResolver.update`. This works while the app owns the file.
  - After a reinstall, the app no longer owns the file and needs `MediaStore.createWriteRequest`.
  - Store the new name in `exports.file_name`.
- Penalty marks on the timer display itself are a reader limitation; see 3.3-E.

### 3.2-D Per-clip buffers ⬜
**#11:** adjust the before and after buffers of one clip, in the editor and in the Gallery, instead of only one slider for the whole video.

**Decided:**
- Each clip is *Follow video* (the default) or *Custom*.
- **On an exported clip, a change offers "Re-export now?"** If the user agrees, a new file is made and the old one goes to the trash through the standard warning dialog.

Where to start:
- The editor Edit window (`MainActivity.kt`) and the viewer sheet (`Gallery.kt`).
- `computeClips` already reads the per-cut pads after 3.2-A.
- The re-export path is `exportFromSession` / `runBatchExport`.

Rules:
- [ADR-0008](adr/0008-never-delete-permanently.md).
- A buffer change is a user change: set `edited` (invariant 4).

### 3.2-E Shared on ⬜
**#10:** filter by where a clip was shared.

**Decided:**
- *Shared on* is a separate field, with its own platform chips in the viewer sheet. They replace the buttons that add words to the note.
- The Filter dialog gets *Shared on: X* and *Not shared yet*.
- 3.2-A fills the field once from existing notes. The note text is left as it is.

Where to start:
- `SocialChip` and the viewer sheet (`Gallery.kt`);
- `SOCIAL_PLATFORMS`, `platformsIn` and `togglePlatform` (`SessionStore.kt`);
- the Filter dialog and `GalleryUiState`.

Ask first: should a free-text search on notes be added too?

### 3.2-F Import clips of single solves ⬜
**#5:** add clips of single solves to the Gallery, with the steps that need, such as typing the solve time.

**Decided:**
- Import asks *A session* or *One solve*. A single solve (`footage.kind = 'single'`) skips Find solves and goes straight to Clips.
- The user types the time. Penalty and puzzle are optional.
- **Trimming is optional:**
  - an untrimmed clip plays the original file and needs no export;
  - a trimmed clip is exported as a copy.
- The re-import warning from 3.1-A covers this path too.

Ask first:
- Where is the solve's stop point: the end of the file, or a point the user marks?
- How does a single solve look in the Footage tab?
- Can a single solve later be turned into a session?

Where to start:
- Import in `GalleryScreen` / `EditorScreen` (`OpenDocument`, `keepAccess`, `openOrCreate`);
- `loadGallery`;
- `computeClips`, where the trim may reuse the per-clip buffers from 3.2-D.

Rules:
- The `OpenDocument` picker is settled.
- "Solve N" numbering is settled.

### 3.2-G Backup and restore ⬜
**#4:** a backup that survives an uninstall and reinstall, and moves the Gallery from an old phone to a new one.

**Decided:**
- **A backup file the user saves wherever they choose.** It contains no videos and needs no INTERNET permission.
- This phase starts with a **new ADR that supersedes [ADR-0009](adr/0009-no-backup-or-transfer.md)**.

Plan:
- **Back up:** write a versioned JSON file with `CreateDocument`. It holds:
  - every video, identified by name, size and duration;
  - every cut and export field, including the v3.2 additions.
- **Restore:** pick the file with `OpenDocument` and merge its contents into the database. Then reconnect videos in bulk:
  - the user picks many videos at once with `OpenMultipleDocuments`;
  - each one is matched by name, size and duration (`openOrCreate` step 2).

Ask first:
- After a reinstall, CubeCut no longer owns the files it exported. To relink them, either:
  - add `READ_MEDIA_VIDEO` (on-device only, so it doesn't break the offline promise), or
  - have the user pick the exported files.
- Does restore merge with an existing Gallery, or replace it?

Where to start:
- [data-model § Constraints](data-model.md#constraints-for-planned-features);
- `openOrCreate` and `relink` (`SessionStore.kt`);
- `res/xml/data_extraction_rules.xml`;
- `allowBackup` in the manifest;
- [android.md](android.md).

Rules:
- The README promises that nothing leaves the phone without the user's action.
- Stored uris are SAF grants that don't survive a reinstall.

### 3.2-Z Release prep 3.2 ⬜
Set `versionName "3.2.0"`. Test the upgrade from 3.1 on a phone with real data.

---

## v3.3: better Find solves

**The owner's request:**
- more accurate;
- no timer setup: the app finds the timer on the frame by itself;
- more fonts, from popular speedcubing timers on PC, phone and physical timers.

The owner rule "change the reader only when asked" is lifted for this version. Every change is still measured against the harness from 3.3-A.

### 3.3-A Ground-truth harness ⬜
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

Ask first:
- Which timers matter most?
- Where are the reference videos stored on the phone?

### 3.3-B Scan accuracy ⬜
- *Earlier note:* a held time misread without its last digit (`1151` while `11517` is held) should count as the same hold in `scan`.
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

### 3.3-E Penalty marks and long times on the display ⬜
- Read +2 and DNF marks next to the time, and times of one hour or more.
- When a mark is read, fill in the 3.2 penalty automatically. The user can still change it.

### 3.3-Z Release prep 3.3 ⬜
Write the before and after numbers into [algorithms §8](algorithms.md#8-reference-measurements).

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
