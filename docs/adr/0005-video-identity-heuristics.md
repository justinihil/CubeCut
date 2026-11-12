# ADR-0005: OpenDocument picker with persisted grants; recognise videos by uri, then name, size and duration

- **Status:** Accepted (documented retroactively)
- **Date:** 3.0.0 (2026-11)

## Context
Saved sessions must reopen their video after a restart. The photo picker's uri does not survive a restart. Users also rename files, and Android sometimes gives a moved or restored file a new uri.

## Decision
- **Import with `OpenDocument`** and `takePersistableUriPermission` (`SessionStore.keepAccess`).
- **`openOrCreate`** recognises a video by, in order:
  1. the same uri;
  2. the same name, size and duration;
  3. the same size and duration.
- **`relink`** (*Reconnect video*) attaches a new uri when the durations match.

## Consequences
- Renaming a file doesn't create a second session.
- A video whose size or length changes is treated as new.
- There is no content hash, so two different files with identical size and duration would collide. This is considered unlikely for phone recordings.
- Android caps persisted grants at 512. *Forget* releases one.
- Re-importing a known video silently reopens its session ([roadmap](../roadmap.md) item 10 wants a warning).

## Alternatives considered
- The photo picker, and a "Videos view" hint. Both were tried and reverted at the owner's request, because re-picking was worse than the file-manager look. **Do not change the picker again unless asked.**
