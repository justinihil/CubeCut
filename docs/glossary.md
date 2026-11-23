# Glossary

Domain words, what they mean in CubeCut, and their names in the code.

## Domain terms → code

| Term (as the UI says it) | Meaning | In code |
|---|---|---|
| **Footage / video / session** | One imported raw recording, plus everything saved about it | `Footage`, table `footage`, `footageId`; "session" in older names (`OpenedSession`, `SessionStore`) |
| **Solve** | One timed attempt found in the footage | A `CutItem` that is not a left-over |
| **Cut / stop / first frame** | The first frame on which the stopped timer shows the final time. Everything is measured from it | `CutItem.stopSec`, `cuts.stop_sec`, `FoundSolve.stopSec` |
| **Time** | The solve time as displayed, e.g. `11.263` or `1:02.34` | `timeText`; seconds via `parseTimeSeconds` |
| **Clip** | The piece of video exported for one solve. Always computed, never stored | `Clip`, `computeClips` |
| **Solve only / with scramble** | The two clip modes. *Solve only* starts at the time on the timer plus the before buffer, counted back from the cut. *With scramble* starts where the previous cut's clip ended | `includeScramble` (per cut), `scrambleAll` (per video: the editor's all-clips switch, or the Gallery's selection switch with a whole video ticked), `Clip.withScramble` |
| **Extra time** (before / after; "buffer" in code and older docs) | Extra seconds before the solve (solve-only clips) and after the cut (all clips). Default 2.5 s. From 3.2 a clip can have its own (*Custom*) instead of *Same as video* | Per video: `Footage.beforePad` / `afterPad`, `footage.before_pad` / `after_pad`. Per clip: `CutItem.beforePad` / `afterPad`, `cuts.before_pad` / `after_pad` (null = follows the video), `PadEditor` (`ClipSettings.kt`) |
| **Puzzle / type tag** | What was solved (3x3, Megaminx…, or *Other* with a typed name) and how (*Type*: Normal, OH, BLD, Relay, PLL attack, or *Other*). Set on a video; a clip follows it unless the clip has its own. *Not this puzzle* (editor) = the clip is not tagged. In the code the type is called *category* | `PUZZLES`, `CATEGORIES`, `NO_TAG`, `resolveTag`, `tagLabel` (`Tags.kt`); `TagDialog` (`TagPicker.kt`); `Footage.puzzle` / `category`, `CutItem.puzzle` / `category` (`''` = follows the video), `GalleryClip.puzzle` / `category` / `tagText`, `updateFootageTags`, `updateCutTags` |
| **Penalty (+2 / DNF)** | +2 can be added several times; DNF shows the time in brackets. The time on the timer is kept as it was filmed. Shown as `13.263 (+2)`, `15.263 (+2×2)`, `DNF(11.263)`; in file names `13.263s_plus2`, `15.263s_plus2x2`, `DNF_11.263s` | `CutItem.plus2Count`, `CutItem.dnf`, `cuts.plus2_count` / `dnf`, `updateCutPenalty`; `Penalty.kt` (`shownTime`, `titleTime`, `fileTimePart`, `penalisedSeconds`, `renamedExportName`); `PenaltyPicker`, `rememberExportRenamer` (`ClipSettings.kt`) |
| **Shared on** | The platforms a clip was posted to, separate from the note. Set with the viewer's chips; *Filter* → *Shared on: X* / *Not shared yet* | `CutItem.sharedOn`, `GalleryClip.sharedList`, `cuts.shared_on`, `encodeSharedOn` / `decodeSharedOn`, `toggleShared`, `sharedMatches`, `updateCutSharedOn`, `GalleryUiState.sharedFilter`, `SocialChip` |
| **Session / single solve (*One solve*)** | A video of many solves (found with Find solves), or a clip of one solve imported as it is (3.2-F): its time is typed, it may be trimmed, it stops where its clip ends | `Footage.kind`: `KIND_SESSION` / `KIND_SINGLE`, `Footage.isSingle` / `isTrimmed`; trim in `Footage.trimStartSec` / `trimEndSec`; `singleRange`, `clipsFor`; `SessionStore.createSingle` / `updateSingle`; `ImportKindDialog`, `SingleSolveDialog`, `KnownSingleDialog` (`SingleSolve.kt`); `GalleryClip.placeText` ("One solve") |
| **Boundary (previous cut)** | Where a with-scramble clip starts: the end of the previous cut's clip, whether or not that cut is exported | Inside `computeClips` |
| **Left over** | A time already on the display when the video began, from an earlier solve. Switched off and ignored like a deleted cut | `isLeftover()`, `SolveAnalyzer.LEFTOVER_NOTE_START`, `included = false` |
| **Check / needs a check** | A solve flagged for the user to look at: a scan note or a timing warning | `CutItem.noteNeedsCheck()`, `checkCuts` / `CutChecks.warnings`, `GalleryClip.needsCheck`, `AppColors.check` (amber) |
| **Too close / overlap / too fast** | The three timing checks | `checkCuts`, `MIN_SOLVE_CYCLE_SEC`, `SLACK_SEC` |
| **It's fine / Mark all as checked** | Clears a check | `CutItem.reviewed`, `cuts.reviewed`, `markCutReviewed` |
| **Edited** | The user changed this cut, so a re-scan must leave it alone | `CutItem.edited`, `cuts.user_edited` |
| **Added by you** | A solve added with *Add a missed solve at the playhead* | `ADDED_BY_YOU_NOTE`, `startManualCut` |
| **Not found again** | An untouched cut that the latest Find solves didn't see | `NOT_FOUND_AGAIN_NOTE` (`mergeRescan`) |
| **Find solves / scan** | Reading the timer along the whole video to find the cuts | `runFindSolves`, `SolveAnalyzer.analyze` / `scan`, `analyzing` flag |
| **Re-scan** | Find solves on a video that already has cuts. The results are merged | `mergeRescan`, `RescanResult` |
| **Ground truth / reference video** | (3.3-A) A video whose correct solves were fixed by hand, and the JSON file listing them | `GroundTruth`, `ExpectedSolve`, `src/test/resources/groundtruth/` |
| **Scan report** | (3.3-A, debug builds) Find solves again without changing the list, compared with the ground truth: found, missed, false, wrong time, cut offsets | `ScanReportHost`, `runScanReport`, `compareScan`, `scanReportText` |
| **Set up timer / timer setup** | Marking the display's corners and typing the number shown, so the reader can learn the layout | `TimerSetupScreen`, `TimerVision.createSetup`, `TimerSetup`, `saveTimerSetup` |
| **Read timer here** | A one-off read at the playhead, with the debug picture | `engine.readFrame` in `EditorScreen` |
| **Exported** | A file exists in `Movies/CubeCut`, is not trashed, and opens | Table `exports`, `ExportedClip`, `recordExports` |
| **Export only** | A clip whose raw video is gone, shown from its exported file | `GalleryClip.exportOnly` |
| **Solve deleted** | An exported file whose cut was deleted in the editor | `GalleryClip.solveDeleted`, `index = -1` |
| **Recently deleted / trash** | Items CubeCut moved to Android's trash | `trashed_ms`, `loadTrash`, `createTrashRequest` |
| **Forget (session)** | Delete CubeCut's records of a video. The file is untouched | `SessionStore.delete`, `releaseAccess` |
| **Reconnect video** | Attach a new file link to a saved session | `SessionStore.relink` |
| **Backup / restore / reconnect in bulk** | A JSON file of the saved data (no videos) the user saves; restore adds what the phone lacks; *Reconnect videos* / *Reconnect exported clips* match many picked files at once | `Backup.write` / `restore`, `BackupDialog` (`Backup.kt`), `SessionStore.restoreVideo` / `reconnectVideos` / `reconnectExports`, `matchVideo`, `matchExport`, `BACKUP_FORMAT` |
| **Gallery: Clips / Footage tabs** | The two tabs of the start screen | `GalleryScreen`, `GalleryUiState.tab` (0 = Clips, 1 = Footage) |
| **Viewer** | The full-screen clip player with the swipe-up sheet | `ClipViewer`, `ui.detail` |
| **Replay** | The viewer's auto-replay switch: clips loop while it is on | `GalleryUiState.autoReplay`, `REPEAT_MODE_ONE` |
| **Editor** | The per-video screen for finding and fixing solves | `EditorScreen`, `EditorRequest` |
| **Where you were** | The brief blue highlight after returning to the Gallery | `flashClip`, `flashFootage`, `FlashTarget` |

## Technical terms

| Term | Meaning |
|---|---|
| **Hold** | The stretch during which a stopped timer keeps showing its final time |
| **Look-alike** | Two readings of the same length that differ in one digit by 1↔4 or 1↔7, the reader's known errors (`confusable`) |
| **Reader** | Turns a straightened black-and-white picture of the display into digits. `DisplayReader`; `SegmentReader` (seven-segment), `GlyphReader` (placeholder) |
| **Verified / unverified setup** | Verified means the learned layout reproduces the typed number exactly. Unverified means the closest layout was saved with a warning |
| **Fixed-camera mode** | Setup for plain screens: the saved corners are used without shape tracking (`TimerSetup.fixedPosition`) |
| **Quick read** | Reading at the display's previous position without running the shape finder (`lastQuad`) |
| **Frame source** | Where the scan gets frames: `GraySource`; `CodecSource` (fast) with the `RetrieverSource` fallback, both behind `AutoSource` |
| **Y plane** | The brightness part of a decoded frame, which is all the reader needs |
| **ORB / RANSAC / homography** | Feature points, robust fitting and perspective transform. Together they locate the timer (`ShapeFinder`) |
| **Keyframe** | A frame that decodes on its own. Lossless clips must start on one |
| **`release()`** | Frees OpenCV native memory, which the garbage collector can't see |
| **MediaStore** | Android's media database. Exports are inserted there; trash requests go through it |
| **Foreground service** | A service with a visible notification that keeps the app alive (`AnalysisService`) |
