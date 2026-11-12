# ADR-0003: One module, flat package, plain Compose state; no ViewModel, Navigation or DI

- **Status:** Accepted (documented retroactively)
- **Date:** evident from 1.0.0 onward

## Context
The project started from Android Studio's *Empty Activity* (Compose) template and grew file by file. Most code was written without a compiler, then built and tested on the phone by the owner.

## Decision
- One `:app` module and one package, `com.example.cubecut`.
- Navigation is a state switch in `AppRoot` plus `if (x != null)` overlays.
- Screen state is `remember { mutableStateOf }` inside `EditorScreen` and `GalleryScreen`. App settings live in `GalleryUiState`, backed by SharedPreferences.
- Services are Kotlin `object` singletons (`SessionStore`, `ClipExporter`, `SolveAnalyzer`, `ThumbCache`, …).

## Consequences
- Every file is easy to find and attach, with no framework concepts to learn.
- `MainActivity.kt` (about 2.2k lines) and `Gallery.kt` (about 3.3k lines) are large. Splitting them was deferred.
- State is lost on activity recreation, which led to [ADR-0010](0010-portrait-lock-config-changes.md). A process death drops the user back in the Gallery, and work in progress survives only through the 400 ms autosave.
- Long jobs run in a composable's coroutine scope and die with it ([ADR-0011](0011-foreground-service-for-notification-only.md)).
- Testing is hard: there are no seams except the pure functions in `RescanMerge.kt`, `computeClips` and `SolveAnalyzer.scan`.

## Alternatives considered
- Moving the scan into a ViewModel or service was noted as the fix for surviving a swipe-away or rotation, but was judged "a bigger change".
- TODO(owner): confirm whether avoiding ViewModel, Navigation and DI was a deliberate choice, or just how the template grew. Say whether introducing them is acceptable for the coming features.
