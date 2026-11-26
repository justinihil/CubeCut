# ADR-0018: Find solves runs in an app-wide queue, kept alive by the foreground service

- **Status:** Accepted
- **Date:** 2026-11-26 (4.0-B)

## Context
In 4.0, importing starts *Find solves* by itself and doesn't open the editor, and several videos can be imported at once (roadmap 4.0-C). The scan therefore can't live in `EditorScreen` any more ([ADR-0011](0011-foreground-service-for-notification-only.md)): the user goes on using the Gallery, or opens another video, while it runs. The app has no ViewModel or DI ([ADR-0003](0003-single-module-plain-compose-state.md)), and the setup by hand with its native timer engine is gone (4.0-0), so a scan needs nothing from a screen.

## Decision
- **`AnalysisQueue`** (`AnalysisQueue.kt`), a Kotlin `object`, scans the videos one after another, on its own coroutine scope (main thread for state, `Default` and `IO` for the work). A scan saves its own result: it reads the saved cuts again at the end, merges with `mergeRescan` and writes cuts, hints, the timer's place and "scanned" in one go.
- **`AnalysisService`** still does no work. The queue starts it while anything waits or runs and stops it when the queue is empty; its notification shows the video and the progress, and a second one says when everything is done.
- **The lock is per video** (invariant 1): while a video waits or is scanned, its editor list, extra-time sliders, Forget, Delete video and Reconnect are off. Everything else in the app works, including leaving the editor.
- The queue's jobs are Compose state (`AnalysisQueue.jobs`, `ScanJob`, `AnalysisQueue.finished`), so screens redraw by themselves; the Gallery reloads when a scan ends, the editor loads the saved result when its video's scan ends.
- One video at a time: the hardware decoder is the bottleneck ([algorithms § 2](../algorithms.md#2-where-frames-come-from)), and a phone gets hot.

## Consequences
- The editor no longer holds scan state that matters. A list shown while its video is scanned is stale (`staleCuts`): the editor never saves it over the scan's result, and loads the result when the scan ends.
- Swiping CubeCut out of the recent apps no longer cancels the scan by itself: it goes on while Android keeps the process (the foreground service asks for that). Not tested yet on the phone; vendor battery savers may still stop it.
- The queue lives in memory: when Android ends the process, the scan in progress is lost. 4.0-C added a mark in the database (`footage.scan_state`, DB v9), so waiting videos are queued again at the next start (`AnalysisQueue.resume`).
- The scan's question (*Which one is your timer?*) can come while no editor is open: the job waits in state `ASKS` until the user answers, in the editor or from the Gallery's progress panel (4.0-C).
- A Gallery write during a scan (a note) is kept, because the scan reads the saved cuts again just before it saves. A write in the few milliseconds between that read and the save would be lost.

## Alternatives considered
- **Do the work inside `AnalysisService`:** the same lifetime as the queue, but the state would have to travel to the screens through a binder or broadcasts. An `object` gives Compose state for free.
- **WorkManager:** made for deferrable work; a scan the user waits for, with progress and a question, fits a foreground service better, and it would add a dependency.
- **A ViewModel:** scoped to one screen's back stack, so it still wouldn't survive leaving the editor, and ADR-0003 keeps the app without them.
- **Several videos at once:** no gain, the decoder is shared (see Decision).
