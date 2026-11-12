# ADR-0002: The cut is the first frame showing the final time; no sound analysis

- **Status:** Accepted (documented retroactively). Superseded the 1.x sound-based method.
- **Date:** 2.0.0 (early November 2026)

## Context
1.x found loud sounds (the slap on the timer) and confirmed each one by reading the timer. Sounds also happen mid-solve and at the timer start, and the method needed a quiet room. The timer's frozen non-zero time was always the real proof of a stop.

## Decision
- Read the timer along the whole video.
- A stop is the same non-zero time on at least 2 reads with nothing clearly different in between, followed by a vote.
- The **first frame that shows that time** is the cut (`stopSec`).
- Sound analysis and the sensitivity slider were removed.

Details: [algorithms §1](../algorithms.md#1-finding-solves).

## Consequences
- **Setting up the timer is essential** and comes first on the screen. It must never be a dead end: it saves with a warning when unsure, and the user can always add solves by hand.
- **Solve-only clips are possible**, because the clip start can be counted back from the time on the timer.
- A final time shown for less than about 1.5 s can be missed. Timers that blank the display immediately are not supported.
- The scan costs one read every 0.5 s across the whole video, which made [ADR-0007](0007-mediacodec-frame-source-with-fallback.md) necessary.

## Alternatives considered
- Rejected for 1.x: a rule requiring the time to be steady for N consecutive frames. It rejected real solves in dim footage where hands cover the display.
- Removed after 2.0.0: a "check these parts of the video" list of unreadable stretches. It was replaced by one reminder to check every solve was captured.
