# Roadmap

Planned work, as the owner listed it on 2026-11-12. **Nothing here is designed or decided** unless it says so. Each item lists where to start and the rules that constrain it. Earlier design notes (from the 3.1 hand-over, written before this list) are included where they overlap, marked *earlier note*.

When an item ships, move it to `CHANGELOG.md` and delete it here.

## 1. Flicker at the top and bottom of the player when opening a clip from the Gallery
- **Start:** `ClipViewer` and `PlayerOverlay` (`Gallery.kt`); `res/layout/video_player_view.xml`.
- **Read first:** [algorithms §6](algorithms.md#6-viewer-still-to-video-handover). A previous flicker took five rounds. Record the screen at 120 fps and inspect every frame before changing anything. Keep one never-removed still per page.

## 2. Edit window: a zoomed-in frame of the timer instead of the whole frame
- **Start:** the Edit window in `EditorScreen` (`MainActivity.kt`) uses `ThumbCache.frame`. The saved corners come from `SessionStore.timerCornersOf(footage)`.
- *Earlier note:* crop the full-size frame around the saved timer corners plus a margin, and fall back to the whole frame when no setup is saved. This assumes a still camera; with tracking, the display may have moved. Open question: should there be a "show whole frame" toggle?

## 3. Batch delete of exported clips, with warnings
- **Start:** the selection header in `GalleryScreen` (`runBatchExport` sits next to it); trash via `createTrashRequest` and `SessionStore.setExportTrashed`.
- **Rules:** [ADR-0008](adr/0008-never-delete-permanently.md): trash only, a visual `EffectLine` warning, a red `DangerButton`, one trash request for all files.
- *Earlier note:* a red bin next to Export in the selection header. *Export only* and *Solve deleted* clips become tickable, and Export skips them. Warning lines: ✕ files to trash, ✕ clips that leave the Gallery, ✓ solves and notes stay, ↺ Recently deleted. Open question: same selection mode, or a separate one?

## 4. Puzzle and category tags for clips and footage
The owner's requirements:
- The **WCA puzzles**, including FTO and Clock.
- **Other** with free text.
- Categories such as relay and PLL attack.
- Encourage **one puzzle per video** at import time.
- An easy way to fix mixed footage: tag the whole video in the editor, untick the clips that don't belong, then tag those in the Gallery.
- Adjust filters and sorts to match.

- **Start:** the recipe [new per-video or per-clip field](../ARCHITECTURE.md#new-per-video-or-per-clip-field-for-example-a-tag-or-a-penalty). Gallery filter and sort: `ClipKey`, `ExportFilter`, the Filter dialog, `GalleryUiState`.
- **Rules:** a per-clip tag the user sets must mark the cut `edited` ([ADR-0013](adr/0013-rescan-merge-keeps-user-edits.md)). The DB goes to v7.
- *Earlier note:*
  - Two tags, *Puzzle* (3x3, 2x2, 4x4–7x7, Megaminx, Pyraminx, Skewb, Square-1, Clock, FTO, Other) and *Type* (Normal, OH, BLD, Relay, PLL attack, Other).
  - The clip follows the video's tag unless it is set on the clip itself. Columns: `footage.puzzle` / `style` and `cuts.puzzle` / `style`, where `''` means "follows the video".
  - Places to set it: an import dialog ("one puzzle per video works best", *Decide later*), the Setup box, the Edit window, the Gallery selection, the viewer sheet and the Footage menu.
  - Open questions: one list or two; whether the puzzle goes into file names.

## 5. Backup / reinstall / phone-to-phone transfer
- **Start:** [data-model § Constraints](data-model.md#constraints-for-planned-features); `openOrCreate` / `relink`.
- **Rules:** [ADR-0009](adr/0009-no-backup-or-transfer.md) must be superseded by a new ADR. Uris and grants don't survive a reinstall, so a restore must re-identify videos by name, size and duration, and needs bulk reconnect. The README promises that nothing leaves the phone.
- *Earlier note:* "Phone backup would need a way to reconnect many videos at once." This was deferred in 3.0.0.

## 6. Import clips of individual solves
- **Start:** import in `EditorScreen` (`OpenDocument`, `openOrCreate`) and `loadGallery`. Today every video is treated as a session to scan.
- Open questions: is such a clip a `footage` row with one cut? How is its time found (scan, or typed in)? Is it exported or only listed?

## 7. Better Find solves: more accurate, no timer setup, more fonts
- **Start:** [algorithms §1–4](algorithms.md); `SolveAnalyzer.kt`, `TimerReader.kt`, `DisplayReaders.kt`.
- **Rules:**
  - [ADR-0001](adr/0001-offline-classic-cv-no-ml.md): decide first whether an offline digit classifier counts as ML.
  - [ADR-0014](adr/0014-display-reader-seam.md): the reader seam.
  - Owner rule: the digit reader is changed only when asked. This item is that ask, but behaviour changes should be measured against the known-good footage ([algorithms §8](algorithms.md#8-reference-measurements)).
  - "No setup" means automatic display detection, which replaces `TimerSetupScreen` and the per-video timer columns.
- *Earlier note:* a misread held time without its last digit (`1151` while `11517` is held) should count as the same hold in `scan`. Also check whether readings like `0.004` of 0.000 occur. The too-fast check stays as a safety net.
- There is no simulator or prototype in the repo. Consider Kotlin unit tests around `scan` with a fake `read(t)`.

## 8. Rethink check navigation (items vanish from the filtered list unexpectedly)
- **Start:** `EditorScreen`: `stepCut`, the *Only show solves to check* filter, `checkCuts` recomputed when cuts change, the slide-out on *It's fine*.
- Likely cause (not yet verified): the filtered list is derived live, so any edit that clears a solve's check (for example a neighbour's change fixing an overlap) removes it immediately, even off-screen.
- Constraint: the owner decided that the filter switch and *Mark all as checked* stay on separate lines, and that both solves of a bad pair are flagged.

## 9. Penalty tag (+2 / DNF) that changes the solve time
- **Start:** `CutItem.timeText` and `parseTimeSeconds` (`ClipExporter.kt`); the Edit window; the Gallery viewer sheet.
- **Rules:**
  - The time drives the solve-only clip start and the file name.
  - The export link uses `stop_sec`, so it survives a time change ([data-model](data-model.md#how-exports-link-to-cuts)).
  - Renaming an already exported file is new behaviour. Today exported files are never touched after export (`exports.file_name` records the name Android gave).
  - Penalty marks on the timer display itself are a known reader limitation.

## 10. Warn when importing a video that was already imported
- **Start:** `SessionStore.openOrCreate` already returns `isNew`. The editor's import flow in `MainActivity.kt` currently reopens the session silently. Identity rules: [ADR-0005](adr/0005-video-identity-heuristics.md).

## 11. Scramble for all clips in the Gallery, plus batch scramble on selection
- **Start:** `setScrambleForSelected` (`Gallery.kt`) already sets the scramble for ticked clips. The editor's all-clips switch writes `scrambleAll` plus every cut.
- **Rule:** the all-clips switch is a video setting and doesn't mark cuts `edited`, but a per-clip change does ([ADR-0013](adr/0013-rescan-merge-keeps-user-edits.md)). Decide which one a Gallery-wide switch is.

## Deferred earlier (not planned)
- Keep every export copy (both delete buttons would then need to delete all copies).
- A "too short" time check. Rejected: some solvers get very short times.
- Remember deleted cuts so a re-scan can't bring them back.
- Permanent cut ids.
- Large-gallery speed (`canOpen` on every resume; the non-lazy editor list).
- Splitting `Gallery.kt` and `EditorScreen`.
- TalkBack labels and 48 dp touch targets.
- Remove `TimerStore.clear`.
- An exact (re-encoding) export option.
- A cropped ORB search near the last position (declined).
