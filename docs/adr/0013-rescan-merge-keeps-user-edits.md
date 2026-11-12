# ADR-0013: Re-scans merge with saved cuts and keep user edits

- **Status:** Accepted (documented retroactively)
- **Date:** 3.0.0 Phase 3a (2026-11)

## Context
Once sessions were saved (3.0.0), running Find solves again would wipe the user's corrections and break the link to exported files.

## Decision
`mergeRescan` matches found solves to saved cuts by overlapping **solve spans**. It never uses the scramble, because a missed solve can hide inside the next solve's scramble.

| Cut | Result |
|---|---|
| `edited` or exported | Kept as is |
| Untouched | Takes the new values |
| New solve | Added |
| Untouched and not found again | Kept and flagged (`NOT_FOUND_AGAIN_NOTE`) |

## Consequences
- **Every user action that changes a cut must set `edited`** (`user_edited`): time, position, per-clip scramble, notes, cuts added by hand, *It's fine*. The all-clips scramble switch is a video setting and does not count.
- Deleted cuts leave no trace, so a re-scan may bring one back. This was the owner's choice; deleting again is quick.
- Solve numbers ("Solve N") can shift after a re-scan. That is accepted. Notes and exports link by stop time, and files already on disk keep their names.
