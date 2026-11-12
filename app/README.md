# `:app` module

The only module. It contains the whole app: UI, image processing, storage and export. Everything is in `src/main/java/com/example/cubecut/`, one flat package ([ADR-0003](../docs/adr/0003-single-module-plain-compose-state.md)). To find a file by task, see [ARCHITECTURE § Where to look](../ARCHITECTURE.md#where-to-look-for-x). For the rules that span files, see [ARCHITECTURE § Invariants](../ARCHITECTURE.md#invariants-break-these-and-something-breaks).

**Dependencies:** Compose BOM + Material3, activity-compose, core-ktx, lifecycle-runtime-ktx, Media3 ExoPlayer/UI, OpenCV. See [build.gradle.kts](build.gradle.kts).

## Files

Rough sizes are given to warn you what you are opening.

### UI
| File | Responsibility | Main entry points | Gotchas |
|---|---|---|---|
| `MainActivity.kt` (~2.2k lines) | Activity, OpenCV init, app root, and **the whole editor** | `MainActivity`, `AppRoot`, `EditorScreen(request, onClose)`; inside the editor: `runFindSolves`, `openTimerSetup`, `startManualCut`, `deleteCut` / `requestDelete`, `pickSolveAt`, `stepCut`, `leaveToGallery`, engine refcount `startUsing` / `stopUsing` / `retireEngine`. Top level: `DEFAULT_INCLUDE_SCRAMBLE`, `AUTHOR_NAME`, `AUTHOR_FIND_ME`, `CHECK_KEYWORDS`, `highlightCheck` | About 50 `remember` states. Everything is gated on `analyzing`. Debounced autosave (400 ms `LaunchedEffect`). `footageId` ordering. The player pauses on `ON_PAUSE`. Layout rule (owner decision): pinned are the header, video, play row, timeline, review bar, Undo bar, and, after Find solves has run, the add-solve button and the two switches. Everything else scrolls. `PlayerView` is inflated from `res/layout/video_player_view.xml` |
| `Gallery.kt` (~3.3k lines) | Start screen: Clips and Footage tabs, selection, batch export, every Gallery dialog, the full-screen viewer, shared widgets | `GalleryUiState` (persisted settings + navigation fields), `GalleryScreen`, `ClipViewer`, `PlayerOverlay`, `EditorRequest`, `ClipKey` / `FootageSort` / `ExportFilter` / `sortClips` / `sortFootage`, `exportFromSession`, `AboutDialog`, `AuthorCreditLine`, `DangerButton`, `EffectLine`, `TrashIcon`, `findActivity` | `ui.refresh++` reloads data (also on `ON_RESUME`). Every settings change must call `ui.save()`. Viewer flicker rules are in [algorithms §6](../docs/algorithms.md#6-viewer-still-to-video-handover). `AboutDialog` holds licence-required text (NOTICE.md) |
| `Timeline.kt` | The editor's film strip, clip bars, markers and playhead | `Timeline(…, position: () -> Long, onSeek, onPick)`, `ClipBar` | A deliberately dark panel in both themes (fixed colours at the top of the file). Reads the playhead only while drawing. Callbacks go through `rememberUpdatedState` |
| `TimerSetupScreen.kt` | Full-screen tap / drag-corners / type-number setup | `TimerSetupScreen(frame, busy, message, onCancel, onSave(corners, typed))` | Has **no `BackHandler`** ([known issue](../docs/known-issues.md)). Typing `102.34` means 1:02.34 here only |
| `Notes.kt` | Shared message style | `InfoNote`, `InfoButton`, `BulletLines`, `LeadAndBullets`, `NoteKind`, `kindOfMessage` | Use these instead of grey paragraphs |
| `Theme.kt` | Colours and dark mode | `CubeCutTheme(mode)`, `ThemeMode`, `AppColors` (`check`, `keep`, `danger`), `LogoBlue` | The **only** theme. `ui/theme/*` is dead template code; don't import it |

### Logic (no Android UI)
| File | Responsibility | Main entry points | Gotchas |
|---|---|---|---|
| `SolveAnalyzer.kt` | Find solves: coarse scan, vote, first-frame search | `SolveAnalyzer.analyze(context, uri, dur, engine, onProgress, isCancelled)`, `scan`, `FoundSolve`, `ScanResult`, `ScanCancelled`, `LEFTOVER_NOTE_START` | Tuning constants at the top. The comment at the top predates the binary search ([algorithms §1](../docs/algorithms.md#1-finding-solves) is current) |
| `RescanMerge.kt` | Pure functions over `CutItem` lists | `mergeRescan`, `RescanResult`, `checkCuts`, `CutChecks`, `isLeftover`, `normalizeIncluded`, `solveAtPlayhead`, `solveOverlappedBy`, `NOT_FOUND_AGAIN_NOTE` | The easiest place to add unit tests |
| `ClipExporter.kt` | The cut model, clip maths and lossless export | `CutItem`, `Clip`, `computeClips`, `parseTimeSeconds`, `SAME_SOLVE_SEC`, `ADDED_BY_YOU_NOTE`, `CutItem.noteNeedsCheck()`, `ClipExporter.export` / `fileName` / `safeTitle` | Data model and exporter share a file. Records the `DISPLAY_NAME` that Android actually used |

### Vision (OpenCV)
| File | Responsibility | Main entry points | Gotchas |
|---|---|---|---|
| `TimerReader.kt` (~1k lines) | Finding, straightening, binarising and reading the display; learning a setup | `TimerVision` (`createSetup`, `prep`, `learn`, `readBin`, `tightenQuad`, `formatDigits`), `ShapeFinder`, `TimerEngine` (`readDigitsGray`, `readFrame`, `forgetPosition`, `release`), `DigitLayout`, `TimerSetup`, `TimerStore` (cleanup shim) | The most heavily tuned file. **Owner rule: don't change the digit reader unless asked.** Every `Mat` must be released. Not thread-safe |
| `DisplayReaders.kt` | Font-specific step behind an interface | `DisplayReader`, `DisplayReaders.all` / `byId`, `SegmentReader`, `GlyphReader` (disabled) | Return `null` when unsure |
| `FrameSource.kt` | Frames for the scan | `GraySource`, `openFrameSource`, `CodecSource`, `RetrieverSource`, `AutoSource` / `calibrate` | The caller releases each `Mat`. Logs `Frame source:` under tag `CubeCut`. Keep the fallback |

### Storage and platform
| File | Responsibility | Main entry points | Gotchas |
|---|---|---|---|
| `SessionStore.kt` | SQLite persistence and Gallery data assembly | `openOrCreate`, `loadCuts`, `saveSession`, `loadGallery`, `recordExports`, `loadExports`, `relink`, `renameFootage`, `saveTimerSetup` / `timerCornersOf`, `updateCutScramble` / `updateCutNote` / `markCutReviewed`, trash helpers, `delete`; `Footage`, `GalleryClip`, `ExportedClip`, `cutKey`, social-note helpers | Schema and rules in [data-model.md](../docs/data-model.md). Call it from `Dispatchers.IO`. The header comment's "Version 3" is the app version; the DB is v6 |
| `Thumbnails.kt` | On-demand picture cache | `ThumbCache.get` / `peek` / `frame` / `forgetFootage`, `Thumb` composable | At most 2 pictures made at once. Disk capped at 100 MB |
| `MediaTools.kt` | Small media helpers | `readDurationMs`, `buildThumbnails` (film strip), `grabFrame`, `formatTime` | — |
| `AnalysisService.kt` | Foreground notification during Find solves | `AnalysisService.start` / `update` / `stop` / `notifyDone` | Does no work itself ([ADR-0011](../docs/adr/0011-foreground-service-for-notification-only.md)) |

### Resources
- `res/layout/video_player_view.xml`: the TextureView `PlayerView`, required for editor pinch zoom and for the viewer.
- `res/values(-night)/themes.xml` and `colors.xml`: window background only (`window_background`). The `black` and `white` colours are template leftovers.
- `res/xml/data_extraction_rules.xml`: excludes everything from backup and transfer.
- `res/drawable/cubecut_logo.png`: the header logo. `mipmap-*`: the launcher icon.
- `strings.xml` holds only `app_name`. **UI text is written directly in Kotlin** (see [CLAUDE.md conventions](../CLAUDE.md#conventions)).

## Tests

`src/test` (`ExampleUnitTest`) and `src/androidTest` (`ExampleInstrumentedTest`) are the untouched Android Studio templates. There is no real coverage yet. Good first targets are the pure functions: `computeClips`, `parseTimeSeconds`, `mergeRescan`, `checkCuts`, and `SolveAnalyzer.scan` with a fake `read(t)`.
