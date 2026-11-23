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
Phase 3.2-A from docs/roadmap.md, branch dev-v3.2.0.
Read CLAUDE.md, then that phase section, then only the files it names.
Ask me the phase's open questions first. Then build it, update the owning docs + CHANGELOG in the same commit,
run assembleDebug + testDebugUnitTest, commit, and give me numbered phone test steps.
```
**During the phase:**
- `Phone test: step 4 failed — <what I saw>` (attach a screen recording for anything visual).
- `All steps pass — close the phase`: Claude marks the phase ✅ here and commits.

**To end a version** (its `-Z` phase):
```
Release prep for v3.2 (phase 3.2-Z).
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
| **3.2** | `dev-v3.2.0` | Organise your clips: tags, penalty, per-clip buffers, *Shared on*, single solves, backup | v7 |
| **3.3** | `dev-v3.3.0` | Better Find solves: no timer setup, more fonts, more accurate | — |
| **4.0** | `dev-v4.0.0` | Find solves runs in the background after import | v8 |

The owner's list had three versions (v3.1, v3.2, v4.0). The first was split in two so that the quick wins, which need no database change, ship first. The owner's "v3.2" (Find solves) became 3.3.

---

## v3.2: organise your clips (DB v7)

Order:
1. All schema plumbing first, with no UI (3.2-A), so the later threads only build screens and never touch `saveSession` or `mergeRescan`.
2. Tags, penalty, buffers and *Shared on* (3.2-B to E).
3. Single-solve import, which uses the tags, penalty and buffers (3.2-F).
4. Backup last, because its file must include every new field (3.2-G).

### 3.2-A Schema foundation (no UI) ✅
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

**Decided** (2026-11-23):
- The single-solve trim is two `footage` columns, `trim_start_sec` / `trim_end_sec` (NULL = untrimmed, the whole file plays), so v7 stays the only schema step in 3.2.
- Until 3.2-E, `updateCutNote` also refreshes `shared_on` from the note, so *Shared on* stays right while the platform buttons still write into the note.

Docs: [data-model.md](data-model.md), the [glossary](glossary.md) and the CHANGELOG.

Phone test: install over 3.1 and check that existing solves, notes and exports survive the upgrade.

### 3.2-B Puzzle and category tags ✅
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

**Decided** at the start of the phase (2026-11-23):
- Lists: the earlier note's puzzles (WCA + FTO) and types, each with *Other* (typed name) and *Not tagged*.
- Two tags: *Puzzle* and *Type*.
- The tag goes into exported file names after the title (*Normal* left out; untagged clips keep today's name).
- Gallery: filter only (Puzzle and Type, with *Not tagged*); no new sort, no grouping. Cards show the tag.
- The editor's untick makes the clip *not tagged* (`NO_TAG`, puzzle and type both). Re-ticking follows the video again.
- Import asks *Which puzzle is this?* once for a new video, with *Decide later*.
- The Footage menu also gets *Set puzzle for this video…*.
- Built without asking (flag for the owner): a Gallery selection that covers every clip of a video tags the video instead of each clip, like the scramble switch, so those solves are not marked `edited`. The import question starts on *Normal*.

Where to start, Gallery filter and sort: `ClipKey`, `ExportFilter`, the Filter dialog and `GalleryUiState` (`Gallery.kt`).

### 3.2-C Penalty (+2 / DNF) 🔶
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

**Decided** at the start of the phase (2026-11-23):
- Format: title `13.263 (+2)`, `15.263 (+2×2)`, `DNF(11.263)`; file `…_Solve_03_13.263s_plus2.mp4`, `…_15.263s_plus2x2.mp4`, `…_DNF_11.263s.mp4`.
- The solve-only clip start uses the raw time on the timer.
- Built without asking (flag for the owner): DNF and +2 exclude each other in the picker; sorting by time and *best* use the penalised time (DNF sorts last); the rename replaces only the time part of the existing name, so a file renamed by hand is left alone; changing only the time does not rename.

### 3.2-D Per-clip buffers 🔶
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

Built (2026-11-23), flag for the owner:
- The editor has no export controls (settled), so its Edit window only says *Other changes need a new export: tap View*. *Re-export now?* is in the viewer.
- After the re-export, the old file's warning offers *Move old file to trash* or *Keep both*. Moving it closes the viewer, like *Delete file*.
- The before buffer only counts for solve-only clips; the window says so.

### 3.2-E Shared on 🔶
**#10:** filter by where a clip was shared.

**Decided:**
- *Shared on* is a separate field, with its own platform chips in the viewer sheet. They replace the buttons that add words to the note.
- The Filter dialog gets *Shared on: X* and *Not shared yet*.
- 3.2-A fills the field once from existing notes. The note text is left as it is.
- **To undo from 3.2-A:** remove the `shared_on` line marked "until 3.2-E" in `updateCutNote`, and switch `GalleryClip.sharedList` to `cut.sharedOn`.

Where to start:
- `SocialChip` and the viewer sheet (`Gallery.kt`);
- `SOCIAL_PLATFORMS`, `platformsIn` and `togglePlatform` (`SessionStore.kt`);
- the Filter dialog and `GalleryUiState`.

Ask first: should a free-text search on notes be added too?

**Decided** at the start of the phase (2026-11-23): no note search for now. The 3.2-A interim line in `updateCutNote` is gone and `GalleryClip.sharedList` reads `cut.sharedOn`. The Filter dialog lists every platform, not only the ones in use.

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
- `computeClips`; the trim columns `footage.trim_start_sec` / `trim_end_sec` and `footage.kind` already exist from 3.2-A.

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
