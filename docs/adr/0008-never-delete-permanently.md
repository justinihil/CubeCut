# ADR-0008: Never delete permanently; use the MediaStore trash after a visual warning

- **Status:** Accepted (documented retroactively)
- **Date:** 3.0.0 (2026-11)

## Context
CubeCut can remove raw session videos and exported clips. These are the user's own files.

## Decision
- Only *move to trash* (`MediaStore.createTrashRequest`) and *Restore*. Deleting for good was explicitly ruled out.
- Destructive actions are red, sit at the bottom of their menu, and show a **visual warning** before Android's own prompt: a picture, then ✓ stays / ✕ goes / ↺ can be restored lines (`DangerButton`, `EffectLine`).
- *Forget this session* removes only CubeCut's records and says "Can't be undone".
- Deleting a **solve** in the editor needs no warning, because Undo covers it. The exception is an **exported** solve, which asks whether to keep its file.

## Consequences
- Android empties its trash after about 30 days, so *Recently deleted* in the Gallery lists what CubeCut trashed.
- Only items with a MediaStore uri can be trashed (`mediaStoreUriOf`). A video picked through a folder view can't be.
- New bulk-delete features ([roadmap](../roadmap.md) item 3) must use one trash request plus this warning pattern.
