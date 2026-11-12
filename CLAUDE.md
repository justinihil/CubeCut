# CLAUDE.md

CubeCut is an offline Android app (Kotlin, Jetpack Compose, Media3, OpenCV). It reads the timer in a speedcubing session video and cuts one clip per solve. It has one module (`:app`) and one flat package (`com.example.cubecut`), with no ViewModel, Navigation or DI.

**Start here, then go one hop down:**
- [ARCHITECTURE.md](ARCHITECTURE.md): diagrams, invariants, **"Where to look for X"**, recipes for common changes.
- [app/README.md](app/README.md): every source file, with its entry points and gotchas.
- [docs/](docs/): [algorithms](docs/algorithms.md) · [data-model](docs/data-model.md) · [android](docs/android.md) · [glossary](docs/glossary.md) · [known-issues](docs/known-issues.md) · [roadmap](docs/roadmap.md) · [ADRs](docs/adr/README.md)

## Commands

These run from the repo root. In PowerShell or cmd use `.\gradlew.bat`. The JDK toolchain (25) is provisioned by Gradle (foojay).

| Task | Command | Status (2026-11-12) |
|---|---|---|
| Build debug APK | `./gradlew assembleDebug` | ✅ passes |
| Unit tests | `./gradlew testDebugUnitTest` | ✅ passes (template test only) |
| Lint | `./gradlew lintDebug` | ❌ fails on 2 existing errors (see known-issues). Report → `app/build/reports/lint-results-debug.html` |
| Merged manifest | `./gradlew processDebugManifest` | ✅ |
| Install on phone | `./gradlew installDebug` | Needs a connected device |
| Instrumented tests | `./gradlew connectedDebugAndroidTest` | Needs a device; template test only |

Signed release builds are made by hand in Android Studio ([docs/android.md § Release](docs/android.md#release-process)). There is no device or emulator in CI, so behaviour is verified on the owner's phone.

## Conventions

For each pattern, copy the example named in the last column.

| Pattern | How | Copy |
|---|---|---|
| New file | Same GPL header as every `.kt` file, then `package com.example.cubecut` | Top of `Notes.kt` |
| Screen state / dialogs | `var x by remember { mutableStateOf<T?>(null) }` in the owning screen + `AlertDialog` shown when non-null | `confirmTrashFootage` / `forgetting` in `GalleryScreen` |
| Destructive action | Picture/header, `EffectLine` rows (✓ / ✕ / ↺), red `DangerButton` at the bottom, then `createTrashRequest` | The *Delete video* dialog in `GalleryScreen` |
| Messages to the user | `InfoNote`, `InfoButton`, `BulletLines`. Short lines (~12 words, one idea, says what to do), no grey paragraphs | `Notes.kt`; Setup box in `EditorScreen` |
| UI text | Written directly in Kotlin (`strings.xml` only has `app_name`). Keep that style unless localisation is planned | Anywhere |
| Colours | `MaterialTheme.colorScheme.*` or `AppColors.check/keep/danger`. Fixed colours only over pictures, in the viewer, or in the timeline panel | `Theme.kt` |
| App setting | Field in `GalleryUiState` + `save()` + `ui.save()` at every change | `themeMode` in `Gallery.kt` |
| Schema change | Bump `DB_VERSION`, add an `if (oldVersion < N)` step, never edit old steps | `SessionStore.Helper.onUpgrade` |
| Single cut update from the Gallery | Find by `stop_sec`, set `user_edited = 1` | `SessionStore.updateCutScramble` |
| Pure logic | Top-level functions over `List<CutItem>`, no Android imports | `RescanMerge.kt` |
| Tuning numbers | `private const val` at the top of the file, with a comment on the trade-off | Top of `SolveAnalyzer.kt` |
| Background work | `withContext(Dispatchers.IO)` for storage/media, `Default` for CPU. Rethrow `CancellationException` first. Record results inside the same IO block | `runBatchExport` in `Gallery.kt` |
| Playhead-driven drawing | Pass `position: () -> Long` and read it while drawing; callbacks via `rememberUpdatedState` | `Timeline.kt` |
| OpenCV memory | `release()` every `Mat` / engine you create, in `finally` | `TimerVision.prep` |
| Comments | KDoc in plain English explaining **why**; reference params as `[name]`; no `@param` tags | `SessionStore.saveSession` |

## Never

- **Never permanently delete user media.** Use `MediaStore.createTrashRequest` after a visual warning ([ADR-0008](docs/adr/0008-never-delete-permanently.md)).
- **Never add the INTERNET permission, networking or cloud services.** ML needs an owner decision first ([ADR-0001](docs/adr/0001-offline-classic-cv-no-ml.md)).
- **Never start video, timer-engine or `cuts` work while `analyzing`** (or while a batch export runs). OpenCV objects are not thread-safe.
- **Never change a cut without setting `edited`.** The exception is the all-clips scramble switch. Otherwise a re-scan overwrites the change.
- **Never link anything to `cuts.id`.** It changes on every save. Exports link by `stop_sec` within `SAME_SOLVE_SEC`.
- **Never store clips or pictures in the database.** Clips come from `computeClips`; pictures come from `ThumbCache`.
- **Never change the scan, the digit reader or `computeClips` as a side effect.** Only change them when that is the task, and check against the known-good footage ([algorithms §8](docs/algorithms.md#8-reference-measurements)).
- **Never remove the `RetrieverSource` fallback** or the author credit (`AUTHOR_NAME`, `AboutDialog`, GPL headers; see `NOTICE.md`).
- **Never import `com.example.cubecut.ui.theme`.** It is dead template code; the real theme is `Theme.kt`.
- **Never add `SDK_INT` checks below 30, remove `configChanges` entries, or enable R8** without the steps in [docs/android.md](docs/android.md).
- **Never commit APKs, keystores, `local.properties` or videos.**
- **Don't reopen settled UX decisions without asking:**
  - the `OpenDocument` picker;
  - "Solve N" numbering;
  - no export controls in the editor;
  - delete with Undo (exported solves ask first);
  - re-export allowed but pointed out;
  - the check filter and *Mark all as checked* on separate lines;
  - portrait only;
  - players take audio focus;
  - playback doesn't change the editor selection.

## Workflow

- Branch from `dev` and open a PR into `dev`. Releases go `dev` → `main`, then a tag ([CONTRIBUTING.md](CONTRIBUTING.md)).
- **Commits use Conventional Commits:** `feat(gallery): …`, `fix(editor): …`, `docs: …`.
- **Update the docs in the same commit as the code.** Add a CHANGELOG `[Unreleased]` line for anything a user would notice.
- When a behaviour change is ambiguous, ask before building. Flag any change the owner didn't ask for.
- Give the owner exact, numbered test steps for the phone: which screen, what to tap, what to expect.

## Where facts live

Keep one home per fact. Update the owning doc and link to it from elsewhere.

| Fact | Owner |
|---|---|
| Module/file roles and entry points | `app/README.md` |
| Cross-file rules, data flow, navigation, recipes | `ARCHITECTURE.md` |
| Algorithms, thresholds explained, measurements | `docs/algorithms.md` |
| Schema, video identity, export↔cut linking | `docs/data-model.md` |
| SDK, permissions, manifest, signing, release | `docs/android.md` |
| Why a decision was made | `docs/adr/NNNN-*.md` (add a new ADR for a new decision) |
| Bugs, limits, untested areas | `docs/known-issues.md` |
| Planned work | `docs/roadmap.md` |
| User-facing how-to and troubleshooting | `docs/user-guide.md` |
| Released changes | `CHANGELOG.md` |
| Domain words ↔ code names | `docs/glossary.md` |
