# ADR-0019: Keep the processor awake while Find solves runs

- **Status:** Accepted
- **Date:** 2026-11-28

## Context
Find solves runs in an app-wide queue kept alive by a foreground service ([ADR-0018](0018-app-wide-analysis-queue.md)). A foreground service stops Android from ending the app, but not the phone from falling asleep when the screen goes off. The owner's *Copy report* showed 49 to 434 s per video in no line of the report: its parts are timed with a clock that stops while the phone sleeps, while "Took" goes on. In daily use, a batch left with the screen off stood still and felt stuck.

## Decision
The queue holds a partial wake lock (`PowerManager.PARTIAL_WAKE_LOCK`) while it runs: taken again for every video with a 2 h limit (`AWAKE_PER_VIDEO_MS`), let go when the queue is empty. The screen may go off; the processor stays on. The report says how long the phone slept during a video (*Phone asleep*), which should now be 0.

## Consequences
- Scans go on with the screen off. They use the same power as before, only sooner; nothing runs when the queue is empty.
- `WAKE_LOCK` is declared by the app. Media3 already declared it for playback, so the merged manifest is unchanged and the user is asked nothing.
- A stuck queue can keep the phone awake at most 2 h per video.

## Alternatives considered
- **Keep the screen on while scanning** (as the editor and the batch export do): only works while CubeCut is on screen, and the screen costs more power than the processor.
- **WorkManager**: would bring its own wake lock, but a new library and a rework of ADR-0018's queue for the same effect.
